import {
	PROTOCOL_VERSION,
	type ClientMessage,
	type Command,
	type ErrorInfo,
	type Event,
	type Permission,
	type RejectReason,
	type RoomState,
	type Song,
	type ServerMessage
} from './protocol.gen';
import { identity } from './account';
import { appUpdate } from './appUpdate.svelte';
import { deviceName } from './deviceVolume';
import { effectivePermissions } from './permissions';
import { settings } from './settings.svelte';
import { saved } from './storage';
import { applyEvent } from './roomEvents';
import { predict, type Sender } from './optimistic';

/** What interrupts the music for everyone: in a party room it's asked first (see [RoomConnection.run]). */
const INTERRUPTS = new Set<Command['kind']>(['Pause', 'Seek', 'Skip', 'Previous', 'JumpTo', 'PlayNow', 'StartRadio']);

/** How long a host notice (e.g. "Couldn't play …") stays on screen. */
const NOTICE_MS = 8000;

export type ConnectionStatus = 'connecting' | 'connected' | 'reconnecting' | 'rejected';

const REJECT_MESSAGES: Record<RejectReason, string> = {
	room_not_found: "This room doesn't exist (anymore).",
	version_mismatch: 'This app and the host are different versions. Reload the page.',
	invalid_name: 'Pick a name of 1–32 characters.',
	replaced: 'You opened this room somewhere else.',
	private_room: 'This is a solo room; only its owner can join.',
	room_moved: 'This room moved to your other device.',
	kicked: 'You were removed from this room. You can join again.',
	banned: "You're banned from this room.",
	account_required: 'This server is for accounts only. Log in to join.',
	left: 'You left this room.',
	migrated: 'Everyone moved to another room.',
	password_required: 'This room has a password.',
	wrong_password: "That's not the room's password.",
	too_many_attempts: 'Too many wrong passwords. Try again in a few minutes.'
};

/**
 * The connection to a room. Keeps an up-to-date copy of the room state by applying the
 * host's events, and reconnects as the same participant when the connection drops.
 */
export class RoomConnection {
	status = $state<ConnectionStatus>('connecting');
	state = $state<RoomState | null>(null);
	participantId = $state<string | null>(null);
	rejectMessage = $state<string | null>(null);
	/** The room moved to another of its owner's hosts, or everyone was moved to another room: the link, if known. */
	movedTo = $state<string | null>(null);
	rejectReason = $state<RejectReason | null>(null);
	/** Only mine: takes my role along to a clone of this room (see [migrationLink]). */
	migrationKey: string | null = null;
	notices = $state<{ id: number; text: string }[]>([]);
	/** A command waiting for "Pause for everyone?" (InterruptDialog): [answer] true runs it. */
	asking = $state<{ command: Command; answer: (go: boolean) => void } | null>(null);

	/** Host clock minus our clock, in ms. */
	clockOffset = $state(0);

	/**
	 * Joins as this participant instead of the one saved in this browser: a party screen on a
	 * Chromecast, whose page may not be allowed to keep anything (it runs in DashCast's frame).
	 */
	guestToken: string | null = null;

	private socket: WebSocket | null = null;
	private seq = 0;
	private waitingForSnapshot = false;
	private nextCommandId = 1;
	private pending = new Map<string, (error: ErrorInfo | null) => void>();
	/** The room as the host has it: [state] is this with my commands that wait for an answer on top. */
	private hostState: RoomState | null = null;
	/** My commands the host hasn't answered yet, already shown in [state] (optimistic.ts). */
	private guesses: { id: string; command: Command; by: Sender }[] = [];
	/** Errors already shown as a notice, so whoever ran the command doesn't say it again. */
	private noticed = new WeakSet<ErrorInfo>();
	private bestRtt = Infinity;
	private pingTimer: ReturnType<typeof setInterval> | undefined;
	private retryDelay = 1000;
	private closed = false;
	private noticeId = 0;
	private sentAccountToken = false;

	/** The room's code; it follows the room when it changes (SetRoomCode). */
	roomCode = $state('');

	constructor(
		roomCode: string,
		private readonly name: string,
		/** Moved here from another room: the key from there, to get my role back (sent until I'm in). */
		private arrivingWithKey: string | null = null,
		/** The room's password, if it has one. */
		private password: string | null = null
	) {
		this.roomCode = roomCode;
	}

	/** Tries again with the room's password (after `password_required` or `wrong_password`). */
	retryWithPassword(password: string): void {
		this.password = password;
		this.status = 'connecting';
		this.rejectReason = null;
		this.rejectMessage = null;
		this.connect();
	}

	/** A link to [url] (a clone of this room) that takes my role along. */
	migrationLink(url: string): string {
		return this.migrationKey ? `${url}#migrate=${encodeURIComponent(this.migrationKey)}` : url;
	}

	get me() {
		return this.state?.participants.find((p) => p.id === this.participantId) ?? null;
	}

	/**
	 * Nobody else plays along: no other device has "Play here" on and no speaker is on. Then
	 * there's nothing to stay in sync with, and small corrections would only be audible jumps.
	 */
	get listeningAlone(): boolean {
		const s = this.state;
		if (!s) return true;
		// Speakers near a phone (also our own) play along too.
		return (
			!s.participants.some((p) => (p.id !== this.participantId && p.online && p.listening) || p.speakers.length > 0) &&
			!s.outputs.some((o) => o.active)
		);
	}

	/** What I may do here (see permissions.ts). */
	get permissions(): Set<Permission> {
		return effectivePermissions(this.me, this.state?.roles ?? []);
	}

	can(permission: Permission): boolean {
		return this.permissions.has(permission);
	}

	/**
	 * What the Next button does for you: skip right away, vote to skip (the room votes and you
	 * may only vote), or nothing.
	 */
	get skipMode(): 'skip' | 'vote' | 'none' {
		if (this.can('skip')) return 'skip';
		if (!this.can('vote_skip')) return 'none';
		return this.state?.settings.voteSkip ? 'vote' : 'skip';
	}

	/** Back to the previous song (or the start): for whoever may skip right away. */
	get canPrevious(): boolean {
		return this.skipMode === 'skip';
	}

	/** You voted to skip the current song. */
	get votedToSkip(): boolean {
		return !!this.participantId && !!this.state?.skipVotes?.voters.includes(this.participantId);
	}

	/** Next: skips (playing the next song also when paused, if this device's setting says so), or votes (a second press takes the vote back). */
	next(ask = true): Promise<ErrorInfo | null> {
		return this.run(this.skipMode === 'vote' ? { kind: 'VoteSkip', on: !this.votedToSkip } : { kind: 'Skip', play: settings.nextPlays.value }, ask);
	}

	/** What "play next" did, for a toast: with fair order a listener's song goes in their next turn. */
	playNextText(name: string): string {
		return this.state?.settings.fairOrder && !this.can('reorder') ? `Added ${name} in your next turn` : `Playing ${name} next`;
	}

	/** The host's current time, as far as we can tell. */
	hostNow(): number {
		return Date.now() + this.clockOffset;
	}

	connect(): void {
		this.closed = false;
		const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
		const socket = new WebSocket(`${protocol}//${location.host}/ws`);
		this.socket = socket;

		socket.onopen = () => {
			this.retryDelay = 1000;
			// Back after the host restarted, maybe for an update: a newer version of this page?
			if (this.status === 'reconnecting') appUpdate.check();
			const accountToken = identity.token;
			this.sentAccountToken = !!accountToken;
			this.sendRaw({
				type: 'hello',
				protocolVersion: PROTOCOL_VERSION,
				roomCode: this.roomCode,
				guestName: this.name,
				guestToken: this.guestToken ?? saved.guestToken(this.roomCode),
				ownerToken: saved.ownerToken(this.roomCode),
				accountToken,
				hideFromHistory: saved.hideFromHistory,
				migrationKey: this.arrivingWithKey,
				password: this.password,
				device: deviceName()
			});
		};
		socket.onmessage = (event) => this.receive(JSON.parse(event.data) as ServerMessage);
		socket.onclose = () => {
			if (this.socket !== socket) return;
			clearInterval(this.pingTimer);
			this.failPending();
			if (this.closed || this.status === 'rejected') return;
			this.status = 'reconnecting';
			setTimeout(() => !this.closed && this.connect(), this.retryDelay);
			this.retryDelay = Math.min(this.retryDelay * 2, 10_000);
		};
	}

	close(): void {
		this.closed = true;
		clearInterval(this.pingTimer);
		this.socket?.close();
		this.socket = null;
	}

	/** Something that was just removed from the queue and can be put back (shown for a few seconds). */
	undo = $state<{ text: string; songs: Song[]; index: number } | null>(null);
	private undoTimer: ReturnType<typeof setTimeout> | undefined;

	/** What [command] takes out of the queue, to put back: a song, or all of them. */
	private undoFor(command: Command): { text: string; songs: Song[]; index: number } | null {
		const queue = this.state?.queue ?? [];
		if (command.kind === 'RemoveQueueItem') {
			const index = queue.findIndex((item) => item.itemId === command.itemId);
			return index < 0 ? null : { text: `Removed "${queue[index].song.title}"`, songs: [queue[index].song], index };
		}
		if (command.kind === 'ClearQueue' && queue.length) {
			return { text: `Cleared the queue (${queue.length} ${queue.length === 1 ? 'song' : 'songs'})`, songs: queue.map((item) => item.song), index: 0 };
		}
		return null;
	}

	/** Puts back what was just removed. */
	undoRemove(): void {
		const undo = this.undo;
		this.undo = null;
		clearTimeout(this.undoTimer);
		if (undo) void this.run({ kind: 'RestoreSongs', songs: undo.songs, index: undo.index });
	}

	/**
	 * Sends a command. Resolves with the host's error, or null when it worked. What would
	 * interrupt the music for the others in a party room is asked first, unless you said
	 * Always (Settings → Playback); [ask] false: never (media buttons, the sleep timer), there's
	 * no page to ask on. Not going ahead resolves with an error that counts as noticed.
	 */
	run(command: Command, ask = true): Promise<ErrorInfo | null> {
		if (ask && this.interruptsOthers(command)) return this.askFirst(command);
		return this.send(command).done;
	}

	/** [command] stops or changes what the others in this party room hear right now. */
	interruptsOthers(command: Command): boolean {
		const s = this.state;
		if (!s || !INTERRUPTS.has(command.kind) || !settings.askInParty.value) return false;
		return s.room.visibility !== 'private' && (s.playback.playing || s.playback.starting) && !this.listeningAlone && !this.onlyMyDevices;
	}

	/** Everyone else here (party screens aside) is logged in with my account: my other devices. */
	get onlyMyDevices(): boolean {
		const mine = this.me?.accountId;
		return !!mine && !!this.state?.participants.every((p) => p.id === this.participantId || !p.online || p.screen || p.accountId === mine);
	}

	private async askFirst(command: Command): Promise<ErrorInfo | null> {
		this.asking?.answer(false);
		const go = await new Promise<boolean>((answer) => (this.asking = { command, answer }));
		this.asking = null;
		if (go) return this.send(command).done;
		const cancelled: ErrorInfo = { code: 'invalid', message: 'Not done' };
		this.noticed.add(cancelled);
		return cancelled;
	}

	/**
	 * Sends a command, and shows what it will most likely do right away ([shown]) instead of
	 * waiting for the host. When the host says no, that's undone and a notice says why.
	 */
	send(command: Command): { shown: boolean; done: Promise<ErrorInfo | null> } {
		const undo = this.undoFor(command);
		const id = `c${this.nextCommandId++}`;
		if (this.socket?.readyState !== WebSocket.OPEN) {
			return { shown: false, done: Promise.resolve({ code: 'invalid', message: 'Not connected' }) };
		}
		const shown = this.guess(id, command);
		if (shown && undo) this.showUndo(undo, id);
		const done = new Promise<ErrorInfo | null>((resolve) => {
			this.pending.set(id, (error) => {
				const guessed = this.guesses.some((g) => g.id === id);
				if (guessed) {
					this.guesses = this.guesses.filter((g) => g.id !== id);
					this.show();
				}
				// Not allowed, or what was shown is undone: say so wherever the command came from.
				if (error && (error.code === 'permission_denied' || guessed)) {
					this.addNotice(error.message);
					this.noticed.add(error);
				}
				if (error && this.undoCommand === id) this.undo = null;
				if (!error && undo && !shown) this.showUndo(undo, id);
				resolve(error);
			});
			this.sendRaw({ type: 'command', id, command });
		});
		return { shown, done };
	}

	/** [error] was already shown as a notice. */
	wasNoticed(error: ErrorInfo): boolean {
		return this.noticed.has(error);
	}

	/** The command whose removal [undo] puts back. */
	private undoCommand: string | null = null;

	private showUndo(undo: { text: string; songs: Song[]; index: number }, commandId: string): void {
		this.undo = undo;
		this.undoCommand = commandId;
		clearTimeout(this.undoTimer);
		this.undoTimer = setTimeout(() => (this.undo = null), 8000);
	}

	/** Shows what [command] will most likely do, until the host answers. False when that can't be told. */
	private guess(id: string, command: Command): boolean {
		const me = this.me;
		if (!this.state || !this.hostState || !me) return false;
		const permissions = this.permissions;
		const by: Sender = {
			id: me.id,
			name: me.name,
			can: (permission) => permissions.has(permission),
			// The host runs it about half a round trip from now.
			at: Math.round(this.hostNow() + (Number.isFinite(this.bestRtt) ? this.bestRtt / 2 : 0)),
			itemId: (index) => `${id}.${index}`
		};
		if (!predict(structuredClone(this.hostState), command, by)) return false;
		this.guesses.push({ id, command, by });
		this.show();
		return true;
	}

	/**
	 * Shows the host's room with my unanswered commands on top. Only what differs is replaced,
	 * so the page redraws just that.
	 */
	private show(): void {
		if (!this.hostState) return;
		const next = structuredClone(this.hostState);
		for (const g of this.guesses) predict(next, g.command, g.by);
		const state = this.state;
		if (!state) {
			this.state = next;
			return;
		}
		for (const key of Object.keys(next) as (keyof RoomState)[]) {
			if (JSON.stringify(state[key]) !== JSON.stringify(next[key])) (state as Record<keyof RoomState, unknown>)[key] = next[key];
		}
	}

	dismissNotice(id: number): void {
		this.notices = this.notices.filter((n) => n.id !== id);
	}

	private sendRaw(message: ClientMessage): void {
		this.socket?.send(JSON.stringify(message));
	}

	/** The room has another code now (or was joined by an earlier one): follow it, with what's saved for it. */
	private followCode(state: RoomState): void {
		const now = state.room.code;
		if (now === this.roomCode) return;
		saved.renameRoom(this.roomCode, now);
		this.roomCode = now;
	}

	private receive(message: ServerMessage): void {
		switch (message.type) {
			case 'welcome':
				saved.setGuestToken(this.roomCode, message.guestToken);
				this.arrivingWithKey = null;
				this.migrationKey = message.migrationKey;
				this.participantId = message.participantId;
				this.hostState = message.state;
				this.state = structuredClone(message.state);
				this.followCode(message.state);
				if (this.sentAccountToken && !message.accountId) {
					// Expired, or from an auth server this host doesn't trust (any more).
					identity.forget();
					this.addNotice("Your login isn't accepted here, so you joined as a guest.");
				}
				this.seq = message.seq;
				this.waitingForSnapshot = false;
				this.status = 'connected';
				this.startClockSync();
				break;
			case 'snapshot':
				this.hostState = message.state;
				if (this.guesses.length) this.show();
				else this.state = structuredClone(message.state);
				this.followCode(message.state);
				this.seq = message.seq;
				this.waitingForSnapshot = false;
				break;
			case 'event':
				if (this.waitingForSnapshot) return;
				if (message.seq !== this.seq + 1) {
					// We missed something; get the full state again.
					this.waitingForSnapshot = true;
					this.sendRaw({ type: 'request_snapshot' });
					return;
				}
				this.seq = message.seq;
				if (this.hostState) this.apply(this.hostState, message.event);
				break;
			case 'result':
				this.pending.get(message.id)?.(message.error);
				this.pending.delete(message.id);
				break;
			case 'pong':
				this.onPong(message.clientTime, message.hostTime);
				break;
			case 'rejected':
				this.status = 'rejected';
				this.rejectReason = message.reason;
				this.rejectMessage = REJECT_MESSAGES[message.reason] ?? 'You were disconnected from this room.';
				this.movedTo = message.movedTo ?? null;
				break;
			case 'owner_token':
				// I'm the owner now: keep the proof, like after creating a room.
				saved.setOwnerToken(this.roomCode, message.ownerToken);
				break;
		}
	}

	private apply(s: RoomState, e: Event): void {
		applyEvent(s, e);
		// With my commands on top (when there are any), or the same change to what's shown.
		if (this.guesses.length) this.show();
		else if (this.state) applyEvent(this.state, structuredClone(e));
		if (e.kind === 'RoomUpdated') this.followCode(s);
		else if (e.kind === 'Notice') this.addNotice(e.message);
	}

	private addNotice(text: string): void {
		const id = ++this.noticeId;
		this.notices.push({ id, text });
		setTimeout(() => this.dismissNotice(id), NOTICE_MS);
	}

	private startClockSync(): void {
		this.bestRtt = Infinity;
		clearInterval(this.pingTimer);
		// A few quick pings for a good first estimate, then one now and then.
		let quick = 5;
		const ping = () => this.sendRaw({ type: 'ping', clientTime: Date.now() });
		ping();
		this.pingTimer = setInterval(() => {
			ping();
			if (--quick === 0) {
				clearInterval(this.pingTimer);
				this.pingTimer = setInterval(ping, 15_000);
			}
		}, 300);
	}

	private onPong(clientTime: number, hostTime: number): void {
		const now = Date.now();
		const rtt = now - clientTime;
		// The sample with the shortest round trip is the most accurate. Let old ones age out.
		this.bestRtt = Math.min(this.bestRtt * 1.05, this.bestRtt + 20);
		if (rtt <= this.bestRtt) {
			this.bestRtt = rtt;
			this.clockOffset = hostTime + rtt / 2 - now;
		}
	}

	private failPending(): void {
		// What they'd do isn't shown any more: the host's room comes back on reconnecting.
		this.guesses = [];
		this.show();
		for (const resolve of this.pending.values()) resolve({ code: 'invalid', message: 'Disconnected' });
		this.pending.clear();
	}
}

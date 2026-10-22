import {
	PROTOCOL_VERSION,
	type ClientMessage,
	type Command,
	type ErrorInfo,
	type Event,
	type Permission,
	type RejectReason,
	type RoomState,
	type ServerMessage
} from './protocol.gen';
import { identity } from './account';
import { appUpdate } from './appUpdate.svelte';
import { deviceName } from './deviceVolume';
import { effectivePermissions } from './permissions';
import { saved } from './storage';
import { applyEvent } from './roomEvents';

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
	wrong_password: "That's not the room's password."
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
	next(): Promise<ErrorInfo | null> {
		return this.run(this.skipMode === 'vote' ? { kind: 'VoteSkip', on: !this.votedToSkip } : { kind: 'Skip', play: saved.nextPlays });
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

	/** Sends a command. Resolves with the host's error, or null when it worked. */
	run(command: Command): Promise<ErrorInfo | null> {
		const id = `c${this.nextCommandId++}`;
		if (this.socket?.readyState !== WebSocket.OPEN) {
			return Promise.resolve({ code: 'invalid', message: 'Not connected' });
		}
		return new Promise((resolve) => {
			this.pending.set(id, (error) => {
				// Not allowed: say so wherever the command came from.
				if (error?.code === 'permission_denied') this.addNotice(error.message);
				resolve(error);
			});
			this.sendRaw({ type: 'command', id, command });
		});
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
				this.state = message.state;
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
				this.state = message.state;
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
				if (this.state) this.apply(this.state, message.event);
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
		for (const resolve of this.pending.values()) resolve({ code: 'invalid', message: 'Disconnected' });
		this.pending.clear();
	}
}

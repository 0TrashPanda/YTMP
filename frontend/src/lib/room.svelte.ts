import {
	PROTOCOL_VERSION,
	type ClientMessage,
	type Command,
	type ErrorInfo,
	type Event,
	type RejectReason,
	type RoomState,
	type ServerMessage
} from './protocol.gen';
import { saved } from './storage';

/** How long a host notice (e.g. "Couldn't play …") stays on screen. */
const NOTICE_MS = 8000;

export type ConnectionStatus = 'connecting' | 'connected' | 'reconnecting' | 'rejected';

const REJECT_MESSAGES: Record<RejectReason, string> = {
	room_not_found: "This room doesn't exist (anymore).",
	version_mismatch: 'This app and the host are different versions. Reload the page.',
	invalid_name: 'Pick a name of 1–32 characters.',
	replaced: 'You opened this room somewhere else.',
	private_room: 'This is a solo room; only the phone that hosts it can join.'
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
	notices = $state<{ id: number; text: string }[]>([]);

	/** Host clock minus our clock, in ms. */
	clockOffset = $state(0);

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

	constructor(
		readonly roomCode: string,
		private readonly name: string
	) {}

	get me() {
		return this.state?.participants.find((p) => p.id === this.participantId) ?? null;
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
			this.sendRaw({
				type: 'hello',
				protocolVersion: PROTOCOL_VERSION,
				roomCode: this.roomCode,
				guestName: this.name,
				guestToken: saved.guestToken(this.roomCode),
				ownerToken: saved.ownerToken(this.roomCode)
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
			this.pending.set(id, resolve);
			this.sendRaw({ type: 'command', id, command });
		});
	}

	dismissNotice(id: number): void {
		this.notices = this.notices.filter((n) => n.id !== id);
	}

	private sendRaw(message: ClientMessage): void {
		this.socket?.send(JSON.stringify(message));
	}

	private receive(message: ServerMessage): void {
		switch (message.type) {
			case 'welcome':
				saved.setGuestToken(this.roomCode, message.guestToken);
				this.participantId = message.participantId;
				this.state = message.state;
				this.seq = message.seq;
				this.waitingForSnapshot = false;
				this.status = 'connected';
				this.startClockSync();
				break;
			case 'snapshot':
				this.state = message.state;
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
				this.rejectMessage = REJECT_MESSAGES[message.reason];
				break;
		}
	}

	private apply(s: RoomState, e: Event): void {
		switch (e.kind) {
			case 'ParticipantJoined':
			case 'ParticipantUpdated': {
				const index = s.participants.findIndex((p) => p.id === e.participant.id);
				if (index >= 0) s.participants[index] = e.participant;
				else s.participants.push(e.participant);
				break;
			}
			case 'ParticipantLeft':
				s.participants = s.participants.filter((p) => p.id !== e.participantId);
				break;
			case 'QueueItemsAdded':
				s.queue.splice(e.index, 0, ...e.items);
				break;
			case 'QueueItemRemoved':
				s.queue = s.queue.filter((i) => i.itemId !== e.itemId);
				break;
			case 'QueueItemMoved': {
				const item = s.queue.find((i) => i.itemId === e.itemId);
				if (!item) break;
				s.queue = s.queue.filter((i) => i !== item);
				s.queue.splice(e.toIndex, 0, item);
				break;
			}
			case 'HistoryAppended':
				s.history.push(e.item);
				break;
			case 'HistoryItemRemoved':
				s.history = s.history.filter((i) => i.itemId !== e.itemId);
				break;
			case 'NowPlayingChanged':
				s.nowPlaying = e.item ? { item: e.item, streamUrl: null } : null;
				break;
			case 'StreamReady':
				if (s.nowPlaying?.item.itemId === e.itemId) s.nowPlaying.streamUrl = e.streamUrl;
				break;
			case 'PlaybackChanged':
				s.playback = e.playback;
				break;
			case 'RoomUpdated':
				s.room = e.room;
				break;
			case 'Notice': {
				const id = ++this.noticeId;
				this.notices.push({ id, text: e.message });
				setTimeout(() => this.dismissNotice(id), NOTICE_MS);
				break;
			}
		}
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

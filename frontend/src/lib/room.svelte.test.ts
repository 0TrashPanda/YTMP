import { describe, expect, it, vi } from 'vitest';
import type { ClientMessage, QueueItem, RoomState, ServerMessage } from './protocol.gen';

// A socket the test answers for the host.
class FakeSocket {
	static OPEN = 1;
	static last: FakeSocket;
	readyState = 1;
	sent: ClientMessage[] = [];
	onopen: (() => void) | null = null;
	onmessage: ((e: { data: string }) => void) | null = null;
	onclose: (() => void) | null = null;
	constructor() {
		FakeSocket.last = this;
	}
	send(data: string) {
		this.sent.push(JSON.parse(data));
	}
	close() {}
	receive(message: ServerMessage) {
		this.onmessage?.({ data: JSON.stringify(message) });
	}
}

const memory = new Map<string, string>();
vi.stubGlobal('WebSocket', FakeSocket);
vi.stubGlobal('location', { protocol: 'http:', host: 'localhost' });
vi.stubGlobal('localStorage', {
	getItem: (k: string) => memory.get(k) ?? null,
	setItem: (k: string, v: string) => memory.set(k, v),
	removeItem: (k: string) => memory.delete(k)
});

const { RoomConnection } = await import('./room.svelte');

const item = (itemId: string): QueueItem =>
	({ itemId, song: { id: itemId, title: itemId, durationMs: 200_000 }, addedBy: 'p1', addedByName: 'Anna', addedAt: 0, result: null, origin: 'manual' }) as QueueItem;

function connected(party = false) {
	const room = new RoomConnection('ROOM', 'Anna');
	room.connect();
	const socket = FakeSocket.last;
	const state = {
		room: { code: 'ROOM', name: 'Room', visibility: party ? 'public' : 'private', hasPassword: false },
		participants: [
			{ id: 'p1', name: 'Anna', isOwner: true, online: true, listening: false, speakers: [], allow: [], deny: [], roleId: 'owner' },
			...(party ? [{ id: 'p2', name: 'Bram', isOwner: false, online: true, listening: true, speakers: [], allow: [], deny: [], roleId: 'owner' }] : [])
		],
		queue: [item('q1'), item('q2')],
		history: [],
		nowPlaying: { item: item('now'), streamUrl: 'https://stream' },
		playback: { playing: true, positionMs: 0, hostTimeMs: Date.now(), starting: false, speed: 1, loading: false },
		outputs: [],
		roles: [],
		settings: { fairOrder: false, voteSkip: false },
		bans: [],
		autoplay: [],
		autoplaySeed: null,
		migration: null,
		adminless: false,
		repeat: 'off',
		skipVotes: null
	} as unknown as RoomState;
	socket.receive({ type: 'welcome', participantId: 'p1', guestToken: 't', migrationKey: 'k', accountId: null, state, seq: 1 } as unknown as ServerMessage);
	const lastCommand = () => socket.sent.filter((m) => m.type === 'command').at(-1) as Extract<ClientMessage, { type: 'command' }>;
	return { room, socket, lastCommand };
}

describe('RoomConnection', () => {
	it('asks before pausing for the others in a party room', async () => {
		const { room, socket } = connected(true);
		const commands = () => socket.sent.filter((m) => m.type === 'command').length;
		const no = room.run({ kind: 'Pause' });
		expect(room.asking?.command.kind).toBe('Pause');
		room.asking!.answer(false);
		const error = await no;
		expect(error && room.wasNoticed(error)).toBe(true);
		expect(commands()).toBe(0);
		expect(room.state?.playback.playing).toBe(true);

		const once = room.run({ kind: 'Skip', play: true });
		room.asking!.answer(true);
		await Promise.resolve();
		expect(commands()).toBe(1);
		void once;
		// Media buttons don't ask; adding songs never does.
		void room.run({ kind: 'Pause' }, false);
		void room.run({ kind: 'AddSongs', songs: [], position: 'end' });
		expect(room.asking).toBeNull();
		expect(commands()).toBe(3);
	});

	it('asks before turning off a speaker that plays, and counts it as someone listening', () => {
		const { room } = connected(true);
		room.state!.participants[0].accountId = 'me';
		room.state!.participants[1].accountId = 'me';
		room.state!.outputs = [{ id: 'tv', name: 'Living room', kind: 'chromecast', active: true }] as RoomState['outputs'];
		expect(room.onlyMyDevices).toBe(false);
		void room.run({ kind: 'SetOutput', outputId: 'tv', active: false, screen: false });
		expect(room.asking?.command.kind).toBe('SetOutput');
		room.asking!.answer(false);
	});

	it("doesn't show my own notice", () => {
		const { room, socket } = connected(true);
		socket.receive({ type: 'event', seq: 2, event: { kind: 'Notice', message: 'Anna paused', notFor: 'p1' } });
		socket.receive({ type: 'event', seq: 3, event: { kind: 'Notice', message: 'Bram skipped', notFor: 'p2' } });
		expect(room.notices.map((n) => n.text)).toEqual(['Bram skipped']);
	});

	it("doesn't ask when everyone else is on my account", () => {
		const { room, socket } = connected(true);
		room.state!.participants[0].accountId = 'me';
		room.state!.participants[1].accountId = 'me';
		void room.run({ kind: 'Pause' });
		expect(room.asking).toBeNull();
		expect(socket.sent.filter((m) => m.type === 'command').length).toBe(1);
	});

	it('shows a command right away, and the host room once it answered', async () => {
		const { room, socket, lastCommand } = connected();
		const done = room.run({ kind: 'Pause' });
		expect(room.state?.playback.playing).toBe(false);
		// Someone else's song arrives first: it shows, with the pause still on top.
		socket.receive({ type: 'event', seq: 2, event: { kind: 'QueueItemsAdded', items: [item('q3')], index: 2 } });
		expect(room.state?.queue.map((i) => i.itemId)).toEqual(['q1', 'q2', 'q3']);
		expect(room.state?.playback.playing).toBe(false);
		socket.receive({ type: 'event', seq: 3, event: { kind: 'PlaybackChanged', playback: { playing: false, positionMs: 1234, hostTimeMs: 5, starting: false, speed: 1, loading: false } } });
		socket.receive({ type: 'result', id: lastCommand().id, error: null });
		expect(await done).toBeNull();
		expect(room.state?.playback.positionMs).toBe(1234);
		expect(room.notices).toEqual([]);
	});

	it('undoes what was shown when the host says no, and says why', async () => {
		const { room, socket, lastCommand } = connected();
		const done = room.run({ kind: 'Skip', play: false });
		expect(room.state?.nowPlaying?.item.itemId).toBe('q1');
		socket.receive({ type: 'result', id: lastCommand().id, error: { code: 'invalid', message: 'Nope' } });
		const error = await done;
		expect(room.state?.nowPlaying?.item.itemId).toBe('now');
		expect(room.state?.queue.map((i) => i.itemId)).toEqual(['q1', 'q2']);
		expect(room.notices.map((n) => n.text)).toEqual(['Nope']);
		expect(room.wasNoticed(error!)).toBe(true);
	});

	it('shows the undo bar right away for a removed song', () => {
		const { room } = connected();
		room.run({ kind: 'RemoveQueueItem', itemId: 'q2' });
		expect(room.state?.queue.map((i) => i.itemId)).toEqual(['q1']);
		expect(room.undo?.text).toBe('Removed "q2"');
	});

	it('drops what was shown when the connection is lost', () => {
		const { room, socket } = connected();
		room.run({ kind: 'ClearQueue' });
		expect(room.state?.queue).toEqual([]);
		socket.onclose?.();
		expect(room.state?.queue.map((i) => i.itemId)).toEqual(['q1', 'q2']);
		room.close();
	});
});

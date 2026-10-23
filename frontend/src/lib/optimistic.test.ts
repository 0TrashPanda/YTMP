import { describe, expect, it } from 'vitest';
import { predict, type Sender } from './optimistic';
import type { Permission, QueueItem, RoomState } from './protocol.gen';

const item = (itemId: string, addedBy = 'p1'): QueueItem => ({
	itemId,
	song: { id: `song-${itemId}`, title: itemId, durationMs: 200_000 } as QueueItem['song'],
	addedBy,
	addedByName: addedBy,
	addedAt: 0,
	result: null,
	origin: 'manual'
});

const room = (over: Partial<RoomState> = {}): RoomState =>
	({
		participants: [{ id: 'p1', name: 'Anna', listening: false }],
		queue: [item('q1'), item('q2')],
		history: [item('h1')],
		autoplay: [item('a1'), item('a2')],
		autoplaySeed: null,
		nowPlaying: { item: item('now'), streamUrl: 'https://stream' },
		playback: { playing: true, positionMs: 10_000, hostTimeMs: 1_000, starting: false, speed: 1, loading: false },
		outputs: [],
		settings: { fairOrder: false, voteSkip: false },
		skipVotes: null,
		repeat: 'off',
		...over
	}) as RoomState;

const sender = (allowed: Permission[] | 'all' = 'all'): Sender => ({
	id: 'p1',
	name: 'Anna',
	can: (p) => allowed === 'all' || allowed.includes(p),
	at: 5_000,
	itemId: (i) => `c1.${i}`
});

const ids = (items: QueueItem[]) => items.map((i) => i.itemId);

describe('predict', () => {
	it('pauses where the song is now, and plays on from there', () => {
		const s = room();
		expect(predict(s, { kind: 'Pause' }, sender())).toBe(true);
		expect(s.playback).toMatchObject({ playing: false, positionMs: 14_000, hostTimeMs: 5_000 });
		expect(predict(s, { kind: 'Play' }, { ...sender(), at: 9_000 })).toBe(true);
		expect(s.playback).toMatchObject({ playing: true, positionMs: 14_000, hostTimeMs: 9_000 });
	});

	it('plays a paused song without its stream as loading', () => {
		const s = room({ nowPlaying: { item: item('now'), streamUrl: null } });
		s.playback.playing = false;
		predict(s, { kind: 'Play' }, sender());
		expect(s.playback).toMatchObject({ playing: false, loading: true });
	});

	it('skips to the next song, which loads', () => {
		const s = room();
		expect(predict(s, { kind: 'Skip', play: false }, sender())).toBe(true);
		expect(s.nowPlaying).toEqual({ item: item('q1'), streamUrl: null });
		expect(ids(s.queue)).toEqual(['q2']);
		expect(s.history.at(-1)).toMatchObject({ itemId: 'now', result: 'skipped' });
		expect(s.playback).toMatchObject({ playing: false, loading: true, positionMs: 0 });
	});

	it("next while paused only plays when asked", () => {
		const paused = () => room({ playback: { playing: false, positionMs: 0, hostTimeMs: 0, starting: false, speed: 1, loading: false } });
		const s = paused();
		predict(s, { kind: 'Skip', play: false }, sender());
		expect(s.playback.loading).toBe(false);
		const t = paused();
		predict(t, { kind: 'Skip', play: true }, sender());
		expect(t.playback.loading).toBe(true);
	});

	it('takes the next song from autoplay when the queue is empty', () => {
		const s = room({ queue: [] });
		predict(s, { kind: 'Skip', play: false }, sender());
		expect(s.nowPlaying?.item.itemId).toBe('a1');
		expect(ids(s.autoplay)).toEqual(['a2']);
	});

	it("leaves repeat queue's next round to the host", () => {
		const s = room({ queue: [], repeat: 'queue' });
		const before = structuredClone(s);
		expect(predict(s, { kind: 'Skip', play: false }, sender())).toBe(false);
		expect(s).toEqual(before);
	});

	it('votes instead of skipping when it may only vote', () => {
		const s = room({ settings: { voteSkip: true } as RoomState['settings'], skipVotes: { voters: [], needed: 2 } });
		predict(s, { kind: 'Skip', play: false }, sender(['vote_skip']));
		expect(s.nowPlaying?.item.itemId).toBe('now');
		expect(s.skipVotes?.voters).toEqual(['p1']);
	});

	it("doesn't guess what isn't allowed", () => {
		const s = room();
		expect(predict(s, { kind: 'Pause' }, sender([]))).toBe(false);
		expect(predict(s, { kind: 'RemoveQueueItem', itemId: 'q1' }, sender(['remove_own']))).toBe(true);
		const other = room({ queue: [item('q1', 'p2')] });
		expect(predict(other, { kind: 'RemoveQueueItem', itemId: 'q1' }, sender(['remove_own']))).toBe(false);
	});

	it('goes back to the previous song, or to the start after a few seconds', () => {
		const s = room({ playback: { playing: true, positionMs: 1_000, hostTimeMs: 5_000, starting: false, speed: 1, loading: false } });
		predict(s, { kind: 'Previous' }, sender());
		expect(s.nowPlaying?.item.itemId).toBe('h1');
		expect(ids(s.queue)).toEqual(['now', 'q1', 'q2']);
		expect(s.history).toEqual([]);
		const t = room();
		predict(t, { kind: 'Previous' }, sender());
		expect(t.nowPlaying?.item.itemId).toBe('now');
		expect(t.playback.positionMs).toBe(0);
	});

	it('jumps ahead in the queue, skipping the songs before it', () => {
		const s = room();
		predict(s, { kind: 'JumpTo', itemId: 'q2' }, sender());
		expect(s.nowPlaying?.item.itemId).toBe('q2');
		expect(ids(s.history)).toEqual(['h1', 'now', 'q1']);
		expect(s.queue).toEqual([]);
	});

	it('plays a song now, and adds songs next or last', () => {
		const s = room();
		const song = item('new').song;
		predict(s, { kind: 'PlayNow', song }, sender());
		expect(s.nowPlaying?.item).toMatchObject({ itemId: 'c1.0', song, addedBy: 'p1' });
		predict(s, { kind: 'AddSongs', songs: [song, song], position: 'next' }, sender());
		expect(ids(s.queue)).toEqual(['c1.0', 'c1.1', 'q1', 'q2']);
		predict(s, { kind: 'AddSongs', songs: [song], position: 'end' }, sender());
		expect(ids(s.queue).at(-1)).toBe('c1.0');
	});

	it('leaves fair order to the host', () => {
		const s = room({ settings: { fairOrder: true } as RoomState['settings'] });
		expect(predict(s, { kind: 'AddSongs', songs: [item('x').song], position: 'end' }, sender())).toBe(false);
	});

	it('starts playing what is added when nothing plays', () => {
		const s = room({ nowPlaying: null, queue: [] });
		predict(s, { kind: 'AddSongs', songs: [item('x').song], position: 'end' }, sender());
		expect(s.nowPlaying?.item.itemId).toBe('c1.0');
		expect(s.playback.loading).toBe(true);
	});

	it('starts a radio: the song plays now and the queue empties', () => {
		const s = room();
		const song = item('r').song;
		predict(s, { kind: 'StartRadio', song, songs: null }, sender());
		expect(s.nowPlaying?.item.song).toEqual(song);
		expect(s.queue).toEqual([]);
		expect(s.autoplay).toEqual([]);
		const t = room();
		predict(t, { kind: 'StartRadio', song: t.nowPlaying!.item.song, songs: null }, sender());
		expect(t.nowPlaying?.item.itemId).toBe('now');
	});

	it('moves songs between the lists', () => {
		const s = room();
		predict(s, { kind: 'MoveItem', itemId: 'a2', list: 'queue', toIndex: 0 }, sender());
		expect(ids(s.queue)).toEqual(['a2', 'q1', 'q2']);
		expect(s.queue[0]).toMatchObject({ addedBy: 'p1', origin: 'manual' });
		expect(ids(s.autoplay)).toEqual(['a1']);
		predict(s, { kind: 'MoveQueueItem', itemId: 'q2', toIndex: 0 }, sender());
		expect(ids(s.queue)).toEqual(['q2', 'a2', 'q1']);
	});

	it('removes the current song without keeping it in the history', () => {
		const s = room();
		predict(s, { kind: 'RemoveQueueItem', itemId: 'now' }, sender());
		expect(s.nowPlaying?.item.itemId).toBe('q1');
		expect(ids(s.history)).toEqual(['h1']);
	});
});

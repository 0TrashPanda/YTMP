import { describe, expect, it, vi } from 'vitest';
import { mergeActions, playList, type ToastAction } from './songActions';
import type { Song } from './protocol.gen';
import type { RoomConnection } from './room.svelte';

describe('mergeActions', () => {
	const undo = (ids: string[]): ToastAction => ({ label: 'Undo', added: () => ids, run: () => {} });

	it('one Undo takes out the songs of both messages, each once', () => {
		const run = vi.fn();
		const room = { run } as unknown as RoomConnection;
		const merged = mergeActions(room, undo(['a', 'b']), undo(['b', 'c']));
		merged!.run();
		expect(run).toHaveBeenCalledWith({ kind: 'RemoveItems', itemIds: ['a', 'b', 'c'] });
	});

	it("keeps the newer message's button when they aren't both Undos of added songs", () => {
		const change: ToastAction = { label: 'Change', run: () => {} };
		expect(mergeActions({} as RoomConnection, undo(['a']), change)).toBe(change);
		expect(mergeActions({} as RoomConnection, change, undefined)).toBeUndefined();
	});
});

describe('playList', () => {
	const song = (id: string) => ({ id }) as unknown as Song;
	const fakeRoom = (queued: number, mayAutoplay = true) => {
		const run = vi.fn(async () => null);
		const room = {
			run,
			state: { queue: Array.from({ length: queued }, () => ({})) },
			can: (p: string) => p !== 'autoplay_from_here' || mayAutoplay,
			wasNoticed: () => false
		} as unknown as RoomConnection;
		return { room, run };
	};

	it('from a tapped song in an empty room: that song plays, the rest go into autoplay', async () => {
		const { room, run } = fakeRoom(0);
		await playList(room, [song('b'), song('c'), song('d')], () => {}, true);
		expect(run.mock.calls).toEqual([
			[{ kind: 'PlayNow', song: song('b') }],
			[{ kind: 'AutoplaySongs', songs: [song('c'), song('d')] }]
		]);
	});

	it('the rest come next in the queue with songs queued, without permission for autoplay, or for Play', async () => {
		for (const [room, run, toAutoplay] of [
			[...Object.values(fakeRoom(2)), true],
			[...Object.values(fakeRoom(0, false)), true],
			[...Object.values(fakeRoom(0)), false]
		] as [RoomConnection, ReturnType<typeof vi.fn>, boolean][]) {
			await playList(room, [song('b'), song('c')], () => {}, toAutoplay);
			expect(run.mock.calls[1]).toEqual([{ kind: 'AddSongs', songs: [song('c')], position: 'next' }]);
		}
	});

	it('adds nothing when playing it was cancelled', async () => {
		const { room, run } = fakeRoom(0);
		run.mockResolvedValueOnce({ message: 'Cancelled' } as never);
		await playList(room, [song('b'), song('c')], () => {}, true);
		expect(run).toHaveBeenCalledTimes(1);
	});
});

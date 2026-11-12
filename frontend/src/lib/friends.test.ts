import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { FriendsOverview, ReceivedShare } from './protocol.gen';

const call = vi.fn();
vi.mock('./listening.svelte', () => ({ listening: { available: true, load: async () => {}, call: (...args: unknown[]) => call(...args) } }));

const { friends } = await import('./friends.svelte');

const share = (id: string, at: number): ReceivedShare => ({
	id,
	song: { id: `ytm:${id}`, title: id, artists: [], album: null, durationMs: 1000, thumbnails: [] } as unknown as ReceivedShare['song'],
	from: { username: 'bob', displayName: 'Bob' },
	group: null,
	note: '',
	at
});

const overview = (hiddenFriends: string[], hiddenGroups: string[]): FriendsOverview => ({
	me: { username: 'anna', displayName: 'Anna' },
	friends: [],
	incoming: [],
	outgoing: [],
	groups: [],
	hiddenFriends,
	hiddenGroups
});

describe('friends', () => {
	beforeEach(() => call.mockReset());

	it('puts a song taken off Home back where it was (Undo)', async () => {
		friends.shares = [share('c', 3), share('b', 2), share('a', 1)];
		call.mockResolvedValue(null);
		const b = friends.shares[1];
		await friends.dismiss('b');
		expect(friends.shares.map((s) => s.id)).toEqual(['c', 'a']);
		await friends.restore(b);
		expect(call).toHaveBeenLastCalledWith('/api/account/shares/b/restore', 'POST');
		expect(friends.shares.map((s) => s.id)).toEqual(['c', 'b', 'a']);
	});

	it("knows who's kept off Home, and asks a friend's or a group's history", async () => {
		friends.overview = overview(['bob'], ['g1']);
		expect(friends.onHome({ kind: 'friend', username: 'bob', name: 'Bob' })).toBe(false);
		expect(friends.onHome({ kind: 'friend', username: 'carl', name: 'Carl' })).toBe(true);
		expect(friends.onHome({ kind: 'group', id: 'g1', name: 'Hardcore' })).toBe(false);
		call.mockResolvedValue({ shares: [] });
		await friends.history({ kind: 'group', id: 'g 1', name: 'X' });
		expect(call).toHaveBeenLastCalledWith('/api/account/shares?group=g%201');
		await friends.history({ kind: 'friend', username: 'bob', name: 'Bob' });
		expect(call).toHaveBeenLastCalledWith('/api/account/shares?from=bob');
	});
});

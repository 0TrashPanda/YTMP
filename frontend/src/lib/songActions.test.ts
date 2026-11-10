import { describe, expect, it, vi } from 'vitest';
import { mergeActions, type ToastAction } from './songActions';
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

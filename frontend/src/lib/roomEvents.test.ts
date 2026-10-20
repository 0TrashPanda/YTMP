import { describe, expect, it } from 'vitest';
import fixture from './testdata/room-events.json';
import type { Event, RoomState } from './protocol.gen';
import { applyEvent } from './roomEvents';

// Events a real room sent (core's RoomTest writes them, with the host's room before and
// after): the web app must end up with the same room as the host.
describe('applyEvent', () => {
	it('gets the room the host has from its events', () => {
		const state = structuredClone(fixture.start) as RoomState;
		for (const event of fixture.events as Event[]) applyEvent(state, event);
		const end = fixture.end as RoomState;
		expect({ ...state, playback: end.playback }).toEqual(end);
	});

	it('moves, removes and replaces queue items', () => {
		const item = (itemId: string) => ({ itemId }) as RoomState['queue'][number];
		const state = { queue: [item('a'), item('b'), item('c')] } as RoomState;
		applyEvent(state, { kind: 'QueueItemMoved', itemId: 'c', toIndex: 0 });
		expect(state.queue.map((i) => i.itemId)).toEqual(['c', 'a', 'b']);
		applyEvent(state, { kind: 'QueueItemRemoved', itemId: 'a' });
		applyEvent(state, { kind: 'QueueItemMoved', itemId: 'gone', toIndex: 0 });
		expect(state.queue.map((i) => i.itemId)).toEqual(['c', 'b']);
		applyEvent(state, { kind: 'QueueItemsAdded', items: [item('d')], index: 1 });
		expect(state.queue.map((i) => i.itemId)).toEqual(['c', 'd', 'b']);
	});
});

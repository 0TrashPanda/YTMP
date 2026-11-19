import { describe, expect, it } from 'vitest';
import { isDefaultName, roomLine, roomTitle } from './roomLabels';
import type { RoomSummary } from './protocol.gen';

const lofi: RoomSummary = { covers: [], artists: ['Nujabes', 'J Dilla'], song: 'Feather · Nujabes', playing: true };

describe('room labels', () => {
	it('knows the names nobody typed', () => {
		expect(isDefaultName("0trashpanda's room")).toBe(true);
		expect(isDefaultName('Solo room')).toBe(true);
		expect(isDefaultName('Road trip')).toBe(false);
	});

	it('calls an unnamed room after its artists, with the song under it', () => {
		expect(roomTitle("Anna's room", lofi)).toBe('Nujabes, J Dilla');
		expect(roomLine("Anna's room", lofi)).toBe('▶ Feather · Nujabes');
		expect(roomLine("Anna's room", { ...lofi, playing: false })).toBe('Last: Feather · Nujabes');
	});

	it('keeps a name of its own, with the artists under it', () => {
		expect(roomTitle('Road trip', lofi)).toBe('Road trip');
		expect(roomLine('Road trip', lofi)).toBe('Nujabes, J Dilla');
	});

	it('keeps the name while nothing has played', () => {
		const empty: RoomSummary = { covers: [], artists: [], song: null, playing: false };
		expect(roomTitle("Anna's room", empty)).toBe("Anna's room");
		expect(roomLine("Anna's room", empty)).toBeNull();
		expect(roomLine("Anna's room", null)).toBeNull();
	});
});

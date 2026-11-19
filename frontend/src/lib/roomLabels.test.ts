import { describe, expect, it } from 'vitest';
import { isDefaultName, roomLine, roomTitle } from './roomLabels';
import type { RoomSummary } from './protocol.gen';

const lofi: RoomSummary = { covers: [], artists: ['Nujabes', 'J Dilla'], song: 'Feather · Nujabes', playing: true, genres: [], mixed: false };

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
		const empty: RoomSummary = { covers: [], artists: [], song: null, playing: false, genres: [], mixed: false };
		expect(roomTitle("Anna's room", empty)).toBe("Anna's room");
		expect(roomLine("Anna's room", empty)).toBeNull();
		expect(roomLine("Anna's room", null)).toBeNull();
	});

	it('adds the genres, and names a mix of artists after them', () => {
		const hiphop = { ...lofi, genres: ['Hip-Hop/Rap'] };
		expect(roomTitle("Anna's room", hiphop)).toBe('Nujabes, J Dilla');
		expect(roomLine("Anna's room", hiphop)).toBe('Hip-Hop/Rap · ▶ Feather · Nujabes');
		expect(roomLine('Road trip', hiphop)).toBe('Hip-Hop/Rap · Nujabes, J Dilla');
		const mix = { ...lofi, genres: ['Pop', 'Hip-Hop/Rap'], mixed: true };
		expect(roomTitle("Anna's room", mix)).toBe('Pop and Hip-Hop/Rap mix');
		expect(roomLine("Anna's room", mix)).toBe('▶ Feather · Nujabes');
		// A mix whose genres aren't known yet: still its artists.
		expect(roomTitle("Anna's room", { ...lofi, mixed: true })).toBe('Nujabes, J Dilla');
	});
});

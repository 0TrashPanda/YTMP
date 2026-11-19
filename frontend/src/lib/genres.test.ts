import { describe, expect, it } from 'vitest';
import { genrePageFor, genreShares, topGenres } from './genres';
import type { Song } from './protocol.gen';

const song = (genre: string | null, podcast = false): Song => ({
	id: `ytm:${Math.random()}`,
	title: 'Song',
	artists: [],
	album: null,
	durationMs: 0,
	thumbnails: [],
	explicit: false,
	podcast: podcast ? { id: 'p', name: 'P' } : null,
	genre
});

// YouTube Music's genres, as Explore → Moods & genres names them.
const YTM = ['Pop', 'Hip-hop', 'R&B & soul', 'Dance & electronic', 'Indie & alternative', 'Rock', 'K-Pop', 'Latin', 'Soundtracks & musicals', 'Country & Americana'].map((title) => ({ id: title, title }));

describe('genres', () => {
	it('finds YouTube Music’s page for Apple’s and Deezer’s genre names', () => {
		const page = (genre: string) => genrePageFor(genre, YTM)?.title ?? null;
		expect(page('Pop')).toBe('Pop');
		expect(page('Hip-Hop/Rap')).toBe('Hip-hop');
		expect(page('Rap/Hip Hop')).toBe('Hip-hop');
		expect(page('R&B/Soul')).toBe('R&B & soul');
		expect(page('Dance')).toBe('Dance & electronic');
		expect(page('Electro')).toBe('Dance & electronic');
		expect(page('Alternative')).toBe('Indie & alternative');
		expect(page('K-Pop')).toBe('K-Pop');
		expect(page('Latin Music')).toBe('Latin');
		expect(page('Soundtrack')).toBe('Soundtracks & musicals');
		expect(page('Country')).toBe('Country & Americana');
		expect(page('Opera')).toBeNull();
	});

	it('counts what songs are mostly, like the room list does', () => {
		const songs = [song('Pop'), song('Pop'), song('Pop'), song('Hip-Hop/Rap'), song('Hip-Hop/Rap'), song('Jazz'), song(null), song('Podcast', true)];
		expect(topGenres(songs)).toEqual(['Pop', 'Hip-Hop/Rap']);
		expect(genreShares(songs).map((g) => g.genre)).toEqual(['Pop', 'Hip-Hop/Rap', 'Jazz']);
		// Under half of the music has a genre yet: none.
		expect(topGenres([song('Pop'), song(null), song(null)])).toEqual([]);
		// "" is looked up, none found.
		expect(topGenres([song('Pop'), song(''), song('Pop')])).toEqual(['Pop']);
	});
});

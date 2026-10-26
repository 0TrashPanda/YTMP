import { describe, expect, it } from 'vitest';
import { addChoice, pickSongs, songsFrom } from './addLimit';

describe('adding a long playlist', () => {
	it('adds a short one whole, without asking', () => {
		expect(addChoice(40, 200, 100)).toEqual({ ask: false, count: 40 });
	});

	it('asks above the set number, with all of it up to the most', () => {
		expect(addChoice(150, 200, 100)).toEqual({ ask: true, few: 100, many: 150 });
		expect(addChoice(2000, 200, 100)).toEqual({ ask: true, few: 100, many: 200 });
		// Not known how long: a long one.
		expect(addChoice(null, 200, 100)).toEqual({ ask: true, few: 100, many: 200 });
	});

	it('never asks when asking is off, or the most is below it', () => {
		expect(addChoice(2000, 200, 0)).toEqual({ ask: false, count: 200 });
		expect(addChoice(2000, 50, 100)).toEqual({ ask: false, count: 50 });
	});

	it('takes the first songs, or a random pick in the playlist order', () => {
		const songs = [1, 2, 3, 4, 5, 6];
		expect(pickSongs(songs, 3, 'first')).toEqual([1, 2, 3]);
		const random = pickSongs(songs, 3, 'random', () => 0.99);
		expect(random).toHaveLength(3);
		expect([...random].sort()).toEqual(random);
		expect(pickSongs(songs, 10, 'random')).toBe(songs);
	});

	it('says how many of what', () => {
		expect(songsFrom(200, 2000, 'Liked music')).toBe('200 songs from Liked music');
		expect(songsFrom(12, 12, 'Discovery')).toBe('Discovery');
	});
});

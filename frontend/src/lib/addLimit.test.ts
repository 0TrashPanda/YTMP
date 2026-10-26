import { describe, expect, it } from 'vitest';
import { addChoice, pickSongs, songsFrom } from './addLimit';

describe('adding a long playlist', () => {
	it('adds a short one whole, without asking', () => {
		expect(addChoice(40, 200, 100)).toEqual({ ask: false, count: 40 });
	});

	it('asks above the set number, with the set choices below its length, and all of it', () => {
		expect(addChoice(2000, 200, 100, [50, 100, 500], true)).toEqual({ ask: true, counts: [50, 100, 500], everything: true });
		// A choice as long as the playlist is all of it.
		expect(addChoice(150, 200, 100, [100, 200], false)).toEqual({ ask: true, counts: [100], everything: true });
		expect(addChoice(2000, 200, 100, [200, 100], false)).toEqual({ ask: true, counts: [100, 200], everything: false });
		// Not known how long: a long one.
		expect(addChoice(null, 200, 100, [100], true)).toEqual({ ask: true, counts: [100], everything: true });
	});

	it('adds without asking when asking is off, or there is nothing to choose', () => {
		expect(addChoice(2000, 200, 0, [100], true)).toEqual({ ask: false, count: 200 });
		expect(addChoice(2000, 200, 100, [], false)).toEqual({ ask: false, count: 200 });
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

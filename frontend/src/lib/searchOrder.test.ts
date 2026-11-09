import { describe, expect, it, vi } from 'vitest';

vi.mock('./settings.svelte', () => ({ settings: { searchOrder: { value: [], set: vi.fn() }, home: { value: {} } } }));

const { arrangeSections, fullOrder, moved, SEARCH_SECTIONS } = await import('./searchOrder.svelte');

const DEFAULT = SEARCH_SECTIONS.map(([key]) => key);
const keys = (order: string[], shown: string[]) => arrangeSections(shown, (k) => k, order);

describe('the order of the search page', () => {
	it('starts with top result, songs, your library, artists, albums, videos, then the rest', () => {
		expect(DEFAULT.slice(0, 6)).toEqual(['top', 'songs', 'library', 'artists', 'albums', 'videos']);
		expect(keys([], ['episodes', 'videos', 'songs', 'library', 'top', 'artists'])).toEqual(['top', 'songs', 'library', 'artists', 'videos', 'episodes']);
	});

	it('uses a saved order', () => {
		expect(keys(['videos', 'top'], ['top', 'songs', 'videos'])[0]).toBe('videos');
	});

	it('places a section the saved order does not have after the one before it by default', () => {
		const old = ['videos', 'songs', 'top', 'artists'];
		const full = fullOrder(old);
		// Library, new since this order was saved, follows Songs.
		expect(full.indexOf('library')).toBe(full.indexOf('songs') + 1);
		expect(new Set(full)).toEqual(new Set(DEFAULT));
	});

	it('shows a section this version has no name for last, in the order it came', () => {
		expect(keys(['videos', 'top'], ['audiobooks', 'top', 'radio', 'videos'])).toEqual(['videos', 'top', 'audiobooks', 'radio']);
	});

	it('moves one section and saves nothing when that is the default again', () => {
		const once = moved([], 'albums', 'songs');
		expect(once.slice(0, 3)).toEqual(['top', 'albums', 'songs']);
		expect(moved(once, 'albums', 'artists')).toEqual([]);
	});
});

import { describe, expect, it, vi } from 'vitest';

vi.mock('./settings.svelte', () => ({
	settings: { searchOrder: { value: [], set: vi.fn() }, artistOrder: { value: [], set: vi.fn() }, home: { value: {} } }
}));

const { artistOrder, searchOrder } = await import('./sectionOrder.svelte');
const { settings } = await import('./settings.svelte');

const DEFAULT = searchOrder.defaults;
const keys = (order: string[], shown: string[]) => searchOrder.arrange(shown, (k) => k, order);

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
		const full = searchOrder.full(old);
		// Library, new since this order was saved, follows Songs.
		expect(full.indexOf('library')).toBe(full.indexOf('songs') + 1);
		expect(new Set(full)).toEqual(new Set(DEFAULT));
	});

	it('shows a section this version has no name for last, in the order it came', () => {
		expect(keys(['videos', 'top'], ['audiobooks', 'top', 'radio', 'videos'])).toEqual(['videos', 'top', 'audiobooks', 'radio']);
	});

	it('moves one section and saves nothing when that is the default again', () => {
		const once = searchOrder.moved([], 'albums', 'songs');
		expect(once.slice(0, 3)).toEqual(['top', 'albums', 'songs']);
		expect(searchOrder.moved(once, 'albums', 'artists')).toEqual([]);
	});
});

describe("the order of an artist's page", () => {
	const arrange = (order: string[], shown: string[]) => artistOrder.arrange(shown, (k) => k, order);

	it('has your library right above songs, then albums and singles', () => {
		expect(artistOrder.defaults).toEqual(['library', 'songs', 'albums', 'singles']);
		expect(arrange([], ['songs', 'albums', 'singles', 'library'])).toEqual(['library', 'songs', 'albums', 'singles']);
	});

	it('keeps an order saved before the library section came, with library above songs', () => {
		expect(artistOrder.full(['albums', 'songs', 'singles'])).toEqual(['albums', 'library', 'songs', 'singles']);
	});

	it('still shows sections it has no name for (podcasts, episodes, new ones), last', () => {
		expect(arrange(['singles', 'songs'], ['episodes', 'songs', 'podcasts', 'singles', 'library'])).toEqual(['singles', 'library', 'songs', 'episodes', 'podcasts']);
	});

	it('is its own setting, apart from the search page', () => {
		artistOrder.move('albums', 'library');
		expect(settings.artistOrder.set).toHaveBeenCalledWith(['albums', 'library', 'songs', 'singles']);
		expect(settings.searchOrder.set).not.toHaveBeenCalled();
		artistOrder.reset();
		expect(settings.artistOrder.set).toHaveBeenLastCalledWith([]);
	});

	it('lists every named section for the settings page, in your order', () => {
		expect(artistOrder.list().map((s) => s.name)).toEqual(['From your library', 'Songs', 'Albums', 'Singles & EPs']);
		expect(artistOrder.isDefault()).toBe(true);
	});
});

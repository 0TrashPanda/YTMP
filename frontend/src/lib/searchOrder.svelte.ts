// The order of the sections on the search page (Settings → Search). One of your settings
// (settings.svelte.ts): only the sections you moved, as a list; what it doesn't know (a section
// added in a later version, say) goes where the default order puts it, and a section this
// version has no name for goes last.
import { learn } from './home.svelte';
import { settings } from './settings.svelte';

/** The search page's own "From your library" section (the others are YouTube Music's). */
export const LIBRARY_SECTION = 'library';
/** The big card on top: a section without a search type. */
export const TOP_SECTION = 'top';

/** Every section, in the default order, with its name. */
export const SEARCH_SECTIONS: [key: string, name: string][] = [
	[TOP_SECTION, 'Top result'],
	['songs', 'Songs'],
	[LIBRARY_SECTION, 'From your library'],
	['artists', 'Artists'],
	['albums', 'Albums'],
	['videos', 'Videos'],
	['community_playlists', 'Community playlists'],
	['featured_playlists', 'Featured playlists'],
	['podcasts', 'Podcasts'],
	['episodes', 'Episodes']
];

const DEFAULT_ORDER = SEARCH_SECTIONS.map(([key]) => key);

const same = (a: string[], b: string[]) => a.length === b.length && a.every((key, i) => key === b[i]);

/** [saved] with every section in it: one it doesn't have goes after the one before it in the default order. */
export const fullOrder = (saved: string[]): string[] => learn(saved, DEFAULT_ORDER);

/** [sections] in the order [saved] gives; ones it can't place go last, as they came. */
export function arrangeSections<T>(sections: T[], keyOf: (section: T) => string, saved: string[]): T[] {
	const order = fullOrder(saved);
	const rank = (section: T) => {
		const at = order.indexOf(keyOf(section));
		return at < 0 ? order.length : at;
	};
	return [...sections].sort((a, b) => rank(a) - rank(b));
}

/** Moves [key] to where [to] is. */
export function moved(saved: string[], key: string, to: string): string[] {
	const order = fullOrder(saved);
	const from = order.indexOf(key);
	const target = order.indexOf(to);
	if (from < 0 || target < 0) return order;
	order.splice(from, 1);
	order.splice(target, 0, key);
	return same(order, DEFAULT_ORDER) ? [] : order;
}

/** Sections (named, in your order) for the settings page. */
export function searchSectionList(): { key: string; name: string }[] {
	const names = new Map(SEARCH_SECTIONS);
	return fullOrder(settings.searchOrder.value).flatMap((key) => (names.has(key) ? [{ key, name: names.get(key)! }] : []));
}

export const moveSearchSection = (key: string, to: string): void => settings.searchOrder.set(moved(settings.searchOrder.value, key, to));

export const isDefaultSearchOrder = (): boolean => same(fullOrder(settings.searchOrder.value), DEFAULT_ORDER);

export const resetSearchOrder = (): void => settings.searchOrder.set([]);

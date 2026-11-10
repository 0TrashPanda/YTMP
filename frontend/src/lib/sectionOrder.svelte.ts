// The order of the sections on a page you can rearrange: the search page (Settings → Search)
// and an artist's page (Settings → Artist pages). Each is one of your settings
// (settings.svelte.ts): only the sections you moved, as a list; what it doesn't know (a section
// added in a later version, say) goes where the default order puts it, and a section this
// version has no name for (one YouTube Music adds) goes last.
import { settings, type Setting } from './settings.svelte';

/** A page's own "From your library" section (the others are YouTube Music's). */
export const LIBRARY_SECTION = 'library';
/** The big card on top of search: a section without a search type. */
export const TOP_SECTION = 'top';

const same = (a: string[], b: string[]) => a.length === b.length && a.every((key, i) => key === b[i]);

export class SectionOrder {
	readonly #defaults: string[];
	readonly #names: Map<string, string>;

	constructor(
		/** Every section, in the default order, with its name. */
		readonly sections: [key: string, name: string][],
		readonly setting: Setting<string[]>
	) {
		this.#defaults = sections.map(([key]) => key);
		this.#names = new Map(sections);
	}

	get defaults(): string[] {
		return [...this.#defaults];
	}

	/**
	 * [saved] with every section in it: one it doesn't have goes after the one before it in the
	 * default order, or, first by default, before the one after it.
	 */
	full(saved: string[] = this.setting.value): string[] {
		const out = [...saved];
		this.#defaults.forEach((key, i) => {
			if (out.includes(key)) return;
			const before = this.#defaults.slice(0, i).findLast((k) => out.includes(k));
			const after = this.#defaults.slice(i + 1).find((k) => out.includes(k));
			out.splice(before !== undefined ? out.indexOf(before) + 1 : after !== undefined ? out.indexOf(after) : out.length, 0, key);
		});
		return out;
	}

	/** [sections] in the order [saved] gives; ones it can't place go last, as they came. */
	arrange<T>(sections: T[], keyOf: (section: T) => string, saved: string[] = this.setting.value): T[] {
		const order = this.full(saved);
		const rank = (section: T) => {
			const at = order.indexOf(keyOf(section));
			return at < 0 ? order.length : at;
		};
		return [...sections].sort((a, b) => rank(a) - rank(b));
	}

	/** [saved] with [key] moved to where [to] is; nothing when that's the default again. */
	moved(saved: string[], key: string, to: string): string[] {
		const order = this.full(saved);
		const from = order.indexOf(key);
		const target = order.indexOf(to);
		if (from < 0 || target < 0) return order;
		order.splice(from, 1);
		order.splice(target, 0, key);
		return same(order, this.#defaults) ? [] : order;
	}

	/** Sections (named, in your order) for the settings page. Reactive. */
	list(): { key: string; name: string }[] {
		return this.full().flatMap((key) => (this.#names.has(key) ? [{ key, name: this.#names.get(key)! }] : []));
	}

	move(key: string, to: string): void {
		this.setting.set(this.moved(this.setting.value, key, to));
	}

	/** Reactive. */
	isDefault(): boolean {
		return same(this.full(), this.#defaults);
	}

	reset(): void {
		this.setting.set([]);
	}
}

/** The search page. */
export const searchOrder = new SectionOrder(
	[
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
	],
	settings.searchOrder
);

/** An artist's page (under its name, bio and buttons, which stay on top). */
export const artistOrder = new SectionOrder(
	[
		[LIBRARY_SECTION, 'From your library'],
		['songs', 'Songs'],
		['albums', 'Albums'],
		['singles', 'Singles & EPs']
	],
	settings.artistOrder
);

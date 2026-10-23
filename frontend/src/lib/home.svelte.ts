// How Home looks, as set in Settings → Home (or a section's ⋮ on Home): which of YouTube
// Music's sections show, in which order, and each as a sideways carousel (like YTM) or a list.
// YTM names its sections itself ("Quick picks", "Listen again"…), so they're known by name;
// a name not seen before goes after the section YTM shows it after. Above them come your pins:
// playlists (Liked music too), albums, artists and podcasts pinned from their page. Saved on this device.
import type { HomeSection } from './protocol.gen';
import { read, write } from './storage';

export type Layout = 'carousel' | 'list';

export interface HomePrefs {
	/** Every section name seen, in the order they show. */
	order: string[];
	hidden: string[];
	/** Sections shown as a list (the others are carousels). */
	lists: string[];
	/** Sections that are (mostly) podcasts and episodes don't show. */
	hidePodcasts: boolean;
	pins: Pin[];
}

/** A page pinned to the top of Home. */
export interface Pin {
	kind: 'playlist' | 'album' | 'artist' | 'podcast';
	id: string;
	title: string;
	/** The picture's URL, when pinned. */
	art?: string;
	/** One of your own playlists (it opens like from your library). */
	personal?: boolean;
}

const PIN_KINDS: string[] = ['playlist', 'album', 'artist', 'podcast'];

function pins(value: unknown): Pin[] {
	if (!Array.isArray(value)) return [];
	return value.flatMap((p) =>
		p && PIN_KINDS.includes(p.kind) && typeof p.id === 'string' && typeof p.title === 'string'
			? [{ kind: p.kind, id: p.id, title: p.title, art: typeof p.art === 'string' ? p.art : undefined, personal: p.personal === true || undefined }]
			: []
	);
}

const KEY = 'ytmp.home';

const strings = (value: unknown): string[] => (Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : []);

export function loadPrefs(text: string | null): HomePrefs {
	try {
		const stored = JSON.parse(text ?? '{}') as Record<string, unknown>;
		return {
			order: [...new Set(strings(stored.order))],
			hidden: strings(stored.hidden),
			lists: strings(stored.lists),
			hidePodcasts: stored.hidePodcasts === true,
			pins: pins(stored.pins)
		};
	} catch {
		return { order: [], hidden: [], lists: [], hidePodcasts: false, pins: [] };
	}
}

/** [order] with the names in [titles] not in it yet, each after the one YTM shows before it. */
export function learn(order: string[], titles: string[]): string[] {
	const out = [...order];
	titles.forEach((title, i) => {
		if (out.includes(title)) return;
		const before = titles.slice(0, i).findLast((t) => out.includes(t));
		out.splice(before === undefined ? 0 : out.indexOf(before) + 1, 0, title);
	});
	return out;
}

/** Most of its items are podcasts or episodes. */
export function isPodcasts(section: HomeSection): boolean {
	const podcasts = section.items.filter((i) => i.kind === 'podcast' || (i.kind === 'song' && i.song.podcast)).length;
	return podcasts > 0 && podcasts * 2 >= section.items.length;
}

/** The sections to show, in order, each with its layout. */
export function arrange(sections: HomeSection[], prefs: HomePrefs): (HomeSection & { layout: Layout })[] {
	const order = learn(
		prefs.order,
		sections.map((s) => s.title)
	);
	return sections
		.filter((s) => !prefs.hidden.includes(s.title) && !(prefs.hidePodcasts && isPodcasts(s)))
		.map((s, i) => ({
			...s,
			layout: (prefs.lists.includes(s.title) ? 'list' : 'carousel') as Layout,
			at: order.indexOf(s.title),
			i
		}))
		.sort((a, b) => a.at - b.at || a.i - b.i)
		.map(({ at: _, i: __, ...s }) => s);
}

export const home = $state<HomePrefs & { podcastSections: string[] }>({
	...loadPrefs(read(KEY)),
	podcastSections: []
});

function save() {
	const { order, hidden, lists, hidePodcasts, pins } = home;
	write(KEY, JSON.stringify({ order, hidden, lists, hidePodcasts, pins }));
}

/** Home loaded these sections: new names are remembered, for Settings → Home. */
export function seen(sections: HomeSection[]): void {
	const order = learn(
		home.order,
		sections.map((s) => s.title)
	);
	home.podcastSections = [...new Set([...home.podcastSections, ...sections.filter(isPodcasts).map((s) => s.title)])];
	if (order.length === home.order.length) return;
	home.order = order;
	save();
}

const toggle = (list: string[], title: string, on: boolean) => (on ? [...new Set([...list, title])] : list.filter((t) => t !== title));

export function setHidden(title: string, hidden: boolean): void {
	home.hidden = toggle(home.hidden, title, hidden);
	save();
}

export function setLayout(title: string, layout: Layout): void {
	home.lists = toggle(home.lists, title, layout === 'list');
	save();
}

export function setHidePodcasts(on: boolean): void {
	home.hidePodcasts = on;
	save();
}

function moved<T>(list: T[], from: number, to: number): T[] {
	const out = [...list];
	const [item] = out.splice(from, 1);
	if (item !== undefined) out.splice(Math.max(0, Math.min(to, out.length)), 0, item);
	return out;
}

/** Moves a section from one place in the order to another. */
export function moveSection(from: number, to: number): void {
	home.order = moved(home.order, from, to);
	save();
}

export const isPinned = (kind: Pin['kind'], id: string) => home.pins.some((p) => p.kind === kind && p.id === id);

/** Pins it (at the end) or unpins it. */
export function togglePin(pin: Pin): void {
	home.pins = isPinned(pin.kind, pin.id) ? home.pins.filter((p) => !(p.kind === pin.kind && p.id === pin.id)) : [...home.pins, pin];
	save();
}

export function movePin(from: number, to: number): void {
	home.pins = moved(home.pins, from, to);
	save();
}

/** One step up (-1) or down (1), among the sections Home shows (hidden ones are skipped). */
export function nudge(title: string, step: -1 | 1, shown: string[]): void {
	const at = shown.indexOf(title);
	const other = shown[at + step];
	if (at < 0 || other === undefined) return;
	moveSection(home.order.indexOf(title), home.order.indexOf(other));
}

/** The sections as YTM has them (your pins stay). */
export function resetHome(): void {
	home.order = [];
	home.hidden = [];
	home.lists = [];
	home.hidePodcasts = false;
	save();
}

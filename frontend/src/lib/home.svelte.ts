// How Home looks, as set in Settings → Home (or a section's ⋮ on Home): which sections show, in
// which order, and each as a sideways carousel (like YTM), a list or a speed dial (YTM's 3×3 grid of
// covers, paging sideways). The sections are YTM's own,
// your Pinned section (tiles of what you pinned) and the pins shown with their songs, each its
// own section. YTM names its sections itself ("Quick picks", "Listen again"…), so they're known
// by name; a name not seen before goes after the section YTM shows it after. Saved on this device.
import type { HomeSection } from './protocol.gen';
import { read, write } from './storage';

export type Layout = 'carousel' | 'list' | 'dial';
const LAYOUTS: string[] = ['carousel', 'list', 'dial'];

export interface HomePrefs {
	/** Every section seen (its key), in the order they show. */
	order: string[];
	hidden: string[];
	/** Sections not shown their usual way (see [defaultLayout]). */
	layouts: Record<string, Layout>;
	/** YTM's sections that are (mostly) podcasts and episodes don't show. */
	hidePodcasts: boolean;
	pins: Pin[];
}

/** A page pinned to Home: a tile in the Pinned section, or its own section with its songs. */
export interface Pin {
	kind: 'playlist' | 'album' | 'artist' | 'podcast';
	id: string;
	title: string;
	/** The picture's URL, when pinned. */
	art?: string;
	/** One of your own playlists (it opens like from your library). */
	personal?: boolean;
	/** Its own section, with its songs (or episodes), instead of a tile. */
	contents?: boolean;
}

/** A section on Home: what it shows, and its key (YTM's sections: their name). */
export type Block = { key: string; title: string; layout: Layout } & (
	| { kind: 'pinned'; pins: Pin[] }
	| { kind: 'pin'; pin: Pin }
	| { kind: 'ytm'; section: HomeSection }
);

/** The Pinned section's key; a pin's section is `ytmp:pin:…`. YTM's names don't look like this. */
export const PINNED = 'ytmp:pinned';
export const pinKey = (pin: Pin) => `ytmp:pin:${pin.kind}:${pin.id}`;

const PIN_KINDS: string[] = ['playlist', 'album', 'artist', 'podcast'];

function pins(value: unknown): Pin[] {
	if (!Array.isArray(value)) return [];
	return value.flatMap((p) =>
		p && PIN_KINDS.includes(p.kind) && typeof p.id === 'string' && typeof p.title === 'string'
			? [
					{
						kind: p.kind,
						id: p.id,
						title: p.title,
						art: typeof p.art === 'string' ? p.art : undefined,
						personal: p.personal === true || undefined,
						contents: p.contents === true || undefined
					}
				]
			: []
	);
}

const KEY = 'ytmp.home';

function layouts(value: unknown): Record<string, Layout> {
	if (!value || typeof value !== 'object') return {};
	return Object.fromEntries(Object.entries(value).filter(([, l]) => LAYOUTS.includes(l as string))) as Record<string, Layout>;
}

/** Pinned and YTM's own Speed dial are a speed dial, like on YTM; the rest carousels. */
export const defaultLayout = (key: string): Layout => (key === PINNED || key === 'Speed dial' ? 'dial' : 'carousel');

const strings = (value: unknown): string[] => (Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : []);

export function loadPrefs(text: string | null): HomePrefs {
	try {
		const stored = JSON.parse(text ?? '{}') as Record<string, unknown>;
		return {
			order: [...new Set(strings(stored.order))],
			hidden: strings(stored.hidden),
			layouts: layouts(stored.layouts),
			hidePodcasts: stored.hidePodcasts === true,
			pins: pins(stored.pins)
		};
	} catch {
		return { order: [], hidden: [], layouts: {}, hidePodcasts: false, pins: [] };
	}
}

/** [order] with the keys in [keys] not in it yet, each after the one shown before it. */
export function learn(order: string[], keys: string[]): string[] {
	const out = [...order];
	keys.forEach((key, i) => {
		if (out.includes(key)) return;
		const before = keys.slice(0, i).findLast((k) => out.includes(k));
		out.splice(before === undefined ? 0 : out.indexOf(before) + 1, 0, key);
	});
	return out;
}

/** Most of its items are podcasts or episodes. */
export function isPodcasts(section: HomeSection): boolean {
	const podcasts = section.items.filter((i) => i.kind === 'podcast' || (i.kind === 'song' && i.song.podcast)).length;
	return podcasts > 0 && podcasts * 2 >= section.items.length;
}

/**
 * Every section there is, before your order and hiding: Pinned (when a pin is a tile), the pins
 * with their songs, then YTM's ([sections], null while loading).
 */
function allBlocks(sections: HomeSection[] | null, prefs: HomePrefs): Block[] {
	const layout = (key: string): Layout => prefs.layouts[key] ?? defaultLayout(key);
	const tiles = prefs.pins.filter((p) => !p.contents);
	return [
		...(tiles.length ? [{ key: PINNED, title: 'Pinned', layout: layout(PINNED), kind: 'pinned' as const, pins: tiles }] : []),
		...prefs.pins.filter((p) => p.contents).map((pin) => ({ key: pinKey(pin), title: pin.title, layout: layout(pinKey(pin)), kind: 'pin' as const, pin })),
		...(sections ?? [])
			.filter((s) => !(prefs.hidePodcasts && isPodcasts(s)))
			.map((section) => ({ key: section.title, title: section.title, layout: layout(section.title), kind: 'ytm' as const, section }))
	];
}

/** The sections to show, in order, each with its layout. */
export function arrange(sections: HomeSection[] | null, prefs: HomePrefs): Block[] {
	const blocks = allBlocks(sections, prefs);
	const order = learn(
		prefs.order,
		blocks.map((b) => b.key)
	);
	return blocks
		.filter((b) => !prefs.hidden.includes(b.key))
		.map((b, i) => ({ b, at: order.indexOf(b.key), i }))
		.sort((x, y) => x.at - y.at || x.i - y.i)
		.map(({ b }) => b);
}

export const home = $state<HomePrefs & { podcastSections: string[] }>({
	...loadPrefs(read(KEY)),
	podcastSections: []
});

function save() {
	const { order, hidden, layouts, hidePodcasts, pins } = home;
	write(KEY, JSON.stringify({ order, hidden, layouts, hidePodcasts, pins }));
}

/** [order] with the sections of [prefs]' pins in it, without those of pins that are gone. */
export function withPins(order: string[], prefs: HomePrefs): string[] {
	const keys = allBlocks(null, prefs).map((b) => b.key);
	return learn(order, keys).filter((k) => !k.startsWith('ytmp:') || keys.includes(k));
}

const currentOrder = () => withPins(home.order, home);

/** Every section for Settings → Home, in order: its key and name. */
export function sectionList(): { key: string; title: string; pin?: Pin }[] {
	return currentOrder().map((key) => {
		const pin = home.pins.find((p) => pinKey(p) === key);
		return { key, title: key === PINNED ? 'Pinned' : (pin?.title ?? key), pin };
	});
}

/** Home loaded these sections: new names are remembered, for Settings → Home. */
export function seen(sections: HomeSection[]): void {
	const order = learn(
		currentOrder(),
		allBlocks(sections, home).map((b) => b.key)
	);
	home.podcastSections = [...new Set([...home.podcastSections, ...sections.filter(isPodcasts).map((s) => s.title)])];
	if (order.length === home.order.length && order.every((k, i) => home.order[i] === k)) return;
	home.order = order;
	save();
}

const toggle = (list: string[], key: string, on: boolean) => (on ? [...new Set([...list, key])] : list.filter((k) => k !== key));

export function setHidden(key: string, hidden: boolean): void {
	home.hidden = toggle(home.hidden, key, hidden);
	save();
}

export function setLayout(key: string, layout: Layout): void {
	const { [key]: _, ...others } = home.layouts;
	home.layouts = layout === defaultLayout(key) ? others : { ...others, [key]: layout };
	save();
}

export const layoutOf = (key: string): Layout => home.layouts[key] ?? defaultLayout(key);

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

/** Moves a section to where [other] is (before it going up, after it going down). */
export function moveSection(key: string, other: string): void {
	const order = currentOrder();
	const from = order.indexOf(key);
	const to = order.indexOf(other);
	if (from < 0 || to < 0) return;
	home.order = moved(order, from, to);
	save();
}

export const isPinned = (kind: Pin['kind'], id: string) => home.pins.some((p) => p.kind === kind && p.id === id);

/** Pins it (a tile, at the end) or unpins it. */
export function togglePin(pin: Pin): void {
	home.pins = isPinned(pin.kind, pin.id) ? home.pins.filter((p) => !(p.kind === pin.kind && p.id === pin.id)) : [...home.pins, pin];
	home.order = currentOrder();
	save();
}

/** A pin as its own section with its songs (true), or a tile in Pinned. */
export function setPinContents(pin: Pin, contents: boolean): void {
	const key = pinKey(pin);
	let order = currentOrder();
	// Its section goes right after Pinned (or first, without Pinned).
	if (contents && !order.includes(key)) order = order.includes(PINNED) ? learn(order, [PINNED, key]) : [key, ...order];
	home.pins = home.pins.map((p) => (p.kind === pin.kind && p.id === pin.id ? { ...p, contents: contents || undefined } : p));
	home.order = withPins(order, home);
	save();
}

/** Moves a pin among the tiles in Pinned. */
export function movePin(from: number, to: number): void {
	home.pins = moved(home.pins, from, to);
	save();
}

/** One step up (-1) or down (1), among the sections Home shows (hidden ones are skipped). */
export function nudge(key: string, step: -1 | 1, shown: string[]): void {
	const other = shown[shown.indexOf(key) + step];
	if (shown.includes(key) && other !== undefined) moveSection(key, other);
}

/** The sections as they come (your pins stay). */
export function resetHome(): void {
	home.order = [];
	home.hidden = [];
	home.layouts = {};
	home.hidePodcasts = false;
	save();
}

// How Home looks, as set in Settings → Home (or a section's ⋮ on Home): which of YouTube
// Music's sections show, in which order, and each as a sideways carousel (like YTM) or a list.
// YTM names its sections itself ("Quick picks", "Listen again"…), so they're known by name;
// a name not seen before goes after the section YTM shows it after. Saved on this device.
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
			hidePodcasts: stored.hidePodcasts === true
		};
	} catch {
		return { order: [], hidden: [], lists: [], hidePodcasts: false };
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
	const { order, hidden, lists, hidePodcasts } = home;
	write(KEY, JSON.stringify({ order, hidden, lists, hidePodcasts }));
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

/** Moves a section from one place in the order to another. */
export function moveSection(from: number, to: number): void {
	const order = [...home.order];
	const [title] = order.splice(from, 1);
	if (title === undefined) return;
	order.splice(Math.max(0, Math.min(to, order.length)), 0, title);
	home.order = order;
	save();
}

/** One step up (-1) or down (1), among the sections Home shows (hidden ones are skipped). */
export function nudge(title: string, step: -1 | 1, shown: string[]): void {
	const at = shown.indexOf(title);
	const other = shown[at + step];
	if (at < 0 || other === undefined) return;
	moveSection(home.order.indexOf(title), home.order.indexOf(other));
}

export function forget(title: string): void {
	home.order = home.order.filter((t) => t !== title);
	home.hidden = home.hidden.filter((t) => t !== title);
	home.lists = home.lists.filter((t) => t !== title);
	save();
}

export function resetHome(): void {
	home.order = [];
	home.hidden = [];
	home.lists = [];
	home.hidePodcasts = false;
	save();
}

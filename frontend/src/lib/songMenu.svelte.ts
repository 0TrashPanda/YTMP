// The song menu's layout, as set in Settings → Song menu: the order of the actions, which are
// hidden, where the dividers go, and the big buttons at the top of the phone's menu. One of your
// settings (settings.svelte.ts).
import { settings } from './settings.svelte';
import type { IconName } from './components/Icon.svelte';

export type MenuAction =
	| 'playNext'
	| 'addToQueue'
	| 'playNow'
	| 'radio'
	| 'autoplay'
	| 'similar'
	| 'graph'
	| 'like'
	| 'dislike'
	| 'removeFromPlaylist'
	| 'save'
	| 'download'
	| 'sleepTimer'
	| 'goPodcast'
	| 'goArtist'
	| 'goAlbum'
	| 'openYtm'
	| 'markPlayed'
	| 'removeListened'
	| 'remove';
export type MenuItem = MenuAction | 'divider';

/** How the settings name each action, and its icon there (the menu itself can say it more precisely, like "Remove like"). */
export const MENU_ACTIONS: Record<MenuAction, { name: string; icon: IconName; hint?: string }> = {
	playNext: { name: 'Play next', icon: 'playNext' },
	addToQueue: { name: 'Add to queue', icon: 'playlistAdd' },
	playNow: { name: 'Play now', icon: 'play', hint: 'The song that plays: play from the start' },
	radio: { name: 'Start radio', icon: 'radio' },
	autoplay: { name: 'Autoplay this', icon: 'autoplay' },
	similar: { name: 'Find similar', icon: 'similar' },
	graph: { name: 'Song graph', icon: 'graph', hint: 'With your YTMP account' },
	like: { name: 'Like', icon: 'like', hint: 'With YouTube Music linked' },
	dislike: { name: 'Dislike', icon: 'dislike', hint: 'With YouTube Music linked' },
	removeFromPlaylist: { name: 'Remove from this playlist', icon: 'remove', hint: 'On your own playlists' },
	save: { name: 'Save to playlist', icon: 'save', hint: 'With YouTube Music linked' },
	download: { name: 'Download', icon: 'download' },
	sleepTimer: { name: 'Sleep timer', icon: 'sleep', hint: 'The song that plays' },
	goPodcast: { name: 'Go to podcast', icon: 'podcast' },
	goArtist: { name: 'Go to artist', icon: 'person' },
	goAlbum: { name: 'Go to album', icon: 'album' },
	openYtm: { name: 'Open in YouTube Music', icon: 'open' },
	markPlayed: { name: 'Mark as played', icon: 'check', hint: 'Podcast episodes' },
	removeListened: { name: 'Remove from your history', icon: 'remove', hint: 'Library → History' },
	remove: { name: 'Remove from the queue', icon: 'remove', hint: 'Up next' }
};

export const DEFAULT_ITEMS: MenuItem[] = [
	'playNext',
	'addToQueue',
	'playNow',
	'divider',
	'radio',
	'autoplay',
	'similar',
	'graph',
	'divider',
	'like',
	'dislike',
	'removeFromPlaylist',
	'save',
	'download',
	'sleepTimer',
	'divider',
	'goPodcast',
	'goArtist',
	'goAlbum',
	'openYtm',
	'markPlayed',
	'divider',
	'removeListened',
	'divider',
	'remove'
];

/** How many big buttons the phone's menu has at the top, like YouTube Music. */
export const BUTTON_COUNT = 3;
export const DEFAULT_BUTTONS: (MenuAction | null)[] = ['playNext', 'addToQueue', 'save'];

/** Buttons outside the song menu that can be held too (the player's). */
export type PlayerButton = 'shuffle' | 'playOn' | 'next';
export type Holdable = MenuAction | PlayerButton;

/**
 * What holding an action offers (a sheet of choices; Settings → Song menu can hide each, or
 * switch holding off). An action listed with no choices does one thing straight away when held.
 */
export const HOLD_CHOICES: Partial<Record<Holdable, { id: string; name: string }[]>> = {
	radio: [
		{ id: 'from', name: 'Radio from' },
		{ id: 'discover', name: 'Songs you know or new ones' },
		{ id: 'account', name: 'With or without your YouTube Music account' },
		{ id: 'taste', name: 'Your taste or everyone’s (in parties)' }
	],
	autoplay: [
		{ id: 'from', name: 'Radio from' },
		{ id: 'discover', name: 'Songs you know or new ones' },
		{ id: 'account', name: 'With or without your YouTube Music account' },
		{ id: 'taste', name: 'Your taste or everyone’s (in parties)' }
	],
	similar: [{ id: 'from', name: 'Radio from' }],
	playNext: [
		{ id: 'next', name: 'Play next' },
		{ id: 'end', name: 'Add to the end of the queue' },
		{ id: 'autoplay', name: 'Add to autoplay' }
	],
	addToQueue: [
		{ id: 'next', name: 'Play next' },
		{ id: 'end', name: 'Add to the end of the queue' },
		{ id: 'autoplay', name: 'Add to autoplay' }
	],
	save: [],
	dislike: [
		{ id: 'artist', name: 'Don’t play this artist in radios' },
		{ id: 'never', name: 'Never put this song in radios' }
	],
	remove: [
		{ id: 'artist', name: 'Remove the songs by this artist' },
		{ id: 'person', name: 'Remove the songs this person added' }
	],
	goArtist: [{ id: 'pick', name: 'Pick one of the artists (else each artist has its own line)' }],
	download: [
		{ id: 'file', name: 'Save as a music file' },
		{ id: 'offline', name: 'Only keep it offline in the app' }
	],
	sleepTimer: [],
	shuffle: [
		{ id: 'queue', name: 'Shuffle the queue' },
		{ id: 'autoplay', name: 'Shuffle the queue and autoplay' }
	],
	playOn: [],
	next: [
		{ id: 'other', name: 'Next song from someone else' },
		{ id: 'album', name: 'Skip to after this album' }
	]
};

/** How each holdable thing is named in the settings when it isn't in the song menu. */
export const PLAYER_BUTTONS: Record<PlayerButton, { name: string; icon: IconName; held: string }> = {
	shuffle: { name: 'Shuffle', icon: 'shuffle', held: 'Shuffle what?' },
	playOn: { name: 'Play on', icon: 'cast', held: 'Plays on the device you used last' },
	next: { name: 'Next', icon: 'next', held: 'Skip to where?' }
};

/** What holding does when it has no sheet. */
export const HOLD_DOES: Partial<Record<Holdable, string>> = {
	save: 'Saves straight to the playlist you used last',
	sleepTimer: 'Starts the time you used last',
	playOn: 'Plays on the device you used last'
};

/** What holding [action] is set to: off, or which of its choices are hidden. */
export interface HoldSetting {
	off: boolean;
	hidden: string[];
}

export interface MenuLayout {
	items: MenuItem[];
	hidden: MenuAction[];
	/** The phone's big buttons (null: none in that place). Not repeated in the list below them. */
	buttons: (MenuAction | null)[];
	/** Holding actions (and the player's buttons): only what's changed from "on, everything shown". */
	hold: Partial<Record<Holdable, HoldSetting>>;
}

const isAction = (value: unknown): value is MenuAction => typeof value === 'string' && value in MENU_ACTIONS;

export function defaultLayout(): MenuLayout {
	return { items: [...DEFAULT_ITEMS], hidden: [], buttons: [...DEFAULT_BUTTONS], hold: {} };
}

/** Reads a saved layout, keeping what's still known; actions added since then go at the end. */
export function parseLayout(saved: unknown): MenuLayout {
	const layout = defaultLayout();
	if (!saved || typeof saved !== 'object') return layout;
	const stored = saved as Partial<Record<'items' | 'hidden' | 'buttons', unknown[]>> & { hold?: unknown };
	if (stored.hold && typeof stored.hold === 'object') {
		for (const [key, value] of Object.entries(stored.hold as Record<string, unknown>)) {
			if (!(key in HOLD_CHOICES) || !value || typeof value !== 'object') continue;
			const v = value as { off?: unknown; hidden?: unknown };
			const ids = new Set(HOLD_CHOICES[key as Holdable]!.map((c) => c.id));
			const hidden = Array.isArray(v.hidden) ? v.hidden.filter((h): h is string => typeof h === 'string' && ids.has(h)) : [];
			if (v.off === true || hidden.length) layout.hold[key as Holdable] = { off: v.off === true, hidden };
		}
	}
	if (Array.isArray(stored.items)) {
		const items = stored.items.filter((i): i is MenuItem => i === 'divider' || isAction(i));
		const unique = items.filter((i, n) => i === 'divider' || items.indexOf(i) === n);
		for (const action of Object.keys(MENU_ACTIONS) as MenuAction[]) if (!unique.includes(action)) unique.push(action);
		layout.items = unique;
	}
	if (Array.isArray(stored.hidden)) layout.hidden = [...new Set(stored.hidden.filter(isAction))];
	if (Array.isArray(stored.buttons)) {
		const buttons = stored.buttons.slice(0, BUTTON_COUNT).map((b) => (isAction(b) ? b : null));
		layout.buttons = buttons.map((b, i) => (b && buttons.indexOf(b) === i ? b : null));
		while (layout.buttons.length < BUTTON_COUNT) layout.buttons.push(null);
	}
	return layout;
}

let parsedFrom: unknown = null;
let parsed = defaultLayout();

/** Your layout. Reactive: it changes when your account answers, or in the settings. */
export function songMenuLayout(): MenuLayout {
	const saved = settings.songMenu.value;
	if (saved !== parsedFrom) {
		parsedFrom = saved;
		parsed = parseLayout(saved);
	}
	return parsed;
}

function change(next: Partial<MenuLayout>): void {
	settings.songMenu.set({ ...songMenuLayout(), ...next });
}

export function isDefaultLayout(layout: MenuLayout = songMenuLayout()): boolean {
	return JSON.stringify(layout) === JSON.stringify(defaultLayout());
}

export function resetLayout(): void {
	settings.songMenu.reset();
}

export function setShown(action: MenuAction, shown: boolean): void {
	const { hidden } = songMenuLayout();
	change({ hidden: shown ? hidden.filter((a) => a !== action) : [...hidden, action] });
}

/** Moves the item at [index] one place up (-1) or down (1). */
export function moveItem(index: number, by: -1 | 1): void {
	const items = [...songMenuLayout().items];
	const to = index + by;
	if (to < 0 || to >= items.length) return;
	[items[index], items[to]] = [items[to], items[index]];
	change({ items });
}

export function addDivider(): void {
	change({ items: [...songMenuLayout().items, 'divider'] });
}

export function removeDivider(index: number): void {
	const { items } = songMenuLayout();
	if (items[index] === 'divider') change({ items: items.filter((_, i) => i !== index) });
}

/** Puts [action] (or nothing) on big button [index]; it leaves any other button it was on. */
export function setButton(index: number, action: MenuAction | null): void {
	change({ buttons: songMenuLayout().buttons.map((b, i) => (i === index ? action : b === action ? null : b)) });
}

/**
 * Lays out a menu: [available] has the entries this song's menu can show, per action (Go to
 * artist can be several). Returns the big buttons (when [withButtons]) and the list (null: a
 * divider), without hidden actions or dividers at the ends or twice in a row.
 */
export function arrange<T>(layout: MenuLayout, available: Partial<Record<MenuAction, T[]>>, withButtons: boolean): { buttons: T[]; list: (T | null)[] } {
	const onButtons = withButtons ? layout.buttons.filter((b): b is MenuAction => !!b && !!available[b]?.length) : [];
	const buttons = onButtons.map((b) => available[b]![0]);
	const list: (T | null)[] = [];
	let divider = false;
	for (const item of layout.items) {
		if (item === 'divider') divider = list.length > 0;
		else if (!layout.hidden.includes(item) && !onButtons.includes(item) && available[item]?.length) {
			if (divider) list.push(null);
			divider = false;
			list.push(...available[item]);
		}
	}
	return { buttons, list };
}

/** Whether holding [action] does something (it has choices or one thing to do, and isn't switched off). */
export function holds(action: Holdable, layout: MenuLayout = songMenuLayout()): boolean {
	const choices = HOLD_CHOICES[action];
	if (!choices || layout.hold[action]?.off) return false;
	return choices.length === 0 || shownChoices(action, layout).length > 0;
}

/** The choices of holding [action] that show. */
export function shownChoices(action: Holdable, layout: MenuLayout = songMenuLayout()): string[] {
	const hidden = layout.hold[action]?.hidden ?? [];
	return (HOLD_CHOICES[action] ?? []).map((c) => c.id).filter((id) => !hidden.includes(id));
}

function setHold(action: Holdable, setting: HoldSetting): void {
	const hold = { ...songMenuLayout().hold };
	if (!setting.off && !setting.hidden.length) delete hold[action];
	else hold[action] = setting;
	change({ hold });
}

export function setHoldOn(action: Holdable, on: boolean): void {
	setHold(action, { off: !on, hidden: songMenuLayout().hold[action]?.hidden ?? [] });
}

export function setChoiceShown(action: Holdable, choice: string, shown: boolean): void {
	const current = songMenuLayout().hold[action] ?? { off: false, hidden: [] };
	const hidden = shown ? current.hidden.filter((h) => h !== choice) : [...new Set([...current.hidden, choice])];
	setHold(action, { off: current.off, hidden });
}

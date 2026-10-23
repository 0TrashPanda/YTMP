// The song menu's layout, as set in Settings → Song menu: the order of the actions, which are
// hidden, where the dividers go, and the big buttons at the top of the phone's menu. Saved on
// this device.
import { read, write } from './storage';
import type { IconName } from './components/Icon.svelte';

export type MenuAction =
	| 'playNext'
	| 'addToQueue'
	| 'playNow'
	| 'radio'
	| 'autoplay'
	| 'similar'
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

export interface MenuLayout {
	items: MenuItem[];
	hidden: MenuAction[];
	/** The phone's big buttons (null: none in that place). Not repeated in the list below them. */
	buttons: (MenuAction | null)[];
}

const KEY = 'ytmp.songMenu';
const isAction = (value: unknown): value is MenuAction => typeof value === 'string' && value in MENU_ACTIONS;

export function defaultLayout(): MenuLayout {
	return { items: [...DEFAULT_ITEMS], hidden: [], buttons: [...DEFAULT_BUTTONS] };
}

/** Reads a saved layout, keeping what's still known; actions added since then go at the end. */
export function parseLayout(text: string | null): MenuLayout {
	const layout = defaultLayout();
	if (!text) return layout;
	try {
		const stored = JSON.parse(text) as Partial<Record<keyof MenuLayout, unknown[]>>;
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
	} catch {
		// Unreadable: the defaults.
	}
	return layout;
}

export const songMenu = $state(parseLayout(read(KEY)));

function save(): void {
	write(KEY, JSON.stringify(songMenu));
}

export function isDefaultLayout(layout: MenuLayout = songMenu): boolean {
	return JSON.stringify(layout) === JSON.stringify(defaultLayout());
}

export function resetLayout(): void {
	Object.assign(songMenu, defaultLayout());
	save();
}

export function setShown(action: MenuAction, shown: boolean): void {
	songMenu.hidden = shown ? songMenu.hidden.filter((a) => a !== action) : [...songMenu.hidden, action];
	save();
}

/** Moves the item at [index] one place up (-1) or down (1). */
export function moveItem(index: number, by: -1 | 1): void {
	const to = index + by;
	if (to < 0 || to >= songMenu.items.length) return;
	const items = [...songMenu.items];
	[items[index], items[to]] = [items[to], items[index]];
	songMenu.items = items;
	save();
}

export function addDivider(): void {
	songMenu.items = [...songMenu.items, 'divider'];
	save();
}

export function removeDivider(index: number): void {
	if (songMenu.items[index] !== 'divider') return;
	songMenu.items = songMenu.items.filter((_, i) => i !== index);
	save();
}

/** Puts [action] (or nothing) on big button [index]; it leaves any other button it was on. */
export function setButton(index: number, action: MenuAction | null): void {
	songMenu.buttons = songMenu.buttons.map((b, i) => (i === index ? action : b === action ? null : b));
	save();
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

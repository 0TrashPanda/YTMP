// Settings that follow your YTMP account (docs/features/ui.md#settings): the same on all your
// devices, unless a device keeps its own (the phone icon next to a setting: "Only on this
// device"). The account's value is one of your preferences (preferences.svelte.ts), which also
// keeps it in this browser without an account; a device's own values are kept in this browser.
// What only makes sense on one device (headphones, the equalizer, the song cache) stays in
// storage.ts.
//
// These settings used to be kept in this browser only. Such a value moves once: onto the
// account when it has none yet, else it stays this device's own if it's different (see [load]).
import { listening } from './listening.svelte';
import { preferences } from './preferences.svelte';
import type { DislikeButton, ImageQuality, SwipeOpens, TabOrder, VideoArt } from './storage';
import { PAGE_SIZES, read, write } from './storage';

export type PlaylistPick = 'first' | 'random';
/** How many songs of one playlist may be added at most (Settings → Adding songs). */
export const ADD_LIMITS = { min: 10, max: 5000 };
/** At most this many choices when it asks how many. */
export const MAX_ASK_COUNTS = 6;
/** The wait before "Adding …" shows, in ms. */
export const BUSY_DELAYS = { max: 5000 };

const DEVICE_KEY = 'ytmp.deviceSettings';
const PARKED_KEY = 'ytmp.deviceSettingsParked';
const MOVE_KEY = 'ytmp.settingsToMove';

function readObject(key: string): Record<string, unknown> {
	try {
		const value = JSON.parse(read(key) ?? '{}') as unknown;
		return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
	} catch {
		return {};
	}
}

function remove(key: string): void {
	try {
		localStorage.removeItem(key);
	} catch {
		// Nothing saved anyway.
	}
}

/** This device's own values. */
let device = $state<Record<string, unknown>>(readObject(DEVICE_KEY));
/** This device's own values while it follows your account again, for when it keeps its own once more. */
let parked = $state<Record<string, unknown>>(readObject(PARKED_KEY));
/** Values from before, not moved yet (they count as this device's until then). */
let toMove = $state<Record<string, unknown>>(readObject(MOVE_KEY));

function setDevice(key: string, value: unknown): void {
	const next = { ...device };
	if (value === undefined) delete next[key];
	else next[key] = value;
	device = next;
	write(DEVICE_KEY, JSON.stringify(next));
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
const changed = () => typeof window !== 'undefined' && window.dispatchEvent(new Event('ytmp:settings'));

/** How a value from before was kept in this browser: its key, and how to read it. */
type Old<T> = { key: string; parse: (raw: string) => T | undefined };

export class Setting<T> {
	constructor(
		readonly key: string,
		readonly fallback: T,
		/** The value, if [raw] is one; undefined: not set or not valid. */
		readonly parse: (raw: unknown) => T | undefined,
		old?: Old<T>
	) {
		const raw = old ? read(old.key) : null;
		if (old && raw !== null) {
			const value = old.parse(raw);
			if (value !== undefined && !(key in toMove)) toMove[key] = value;
			write(MOVE_KEY, JSON.stringify(toMove));
			remove(old.key);
		}
	}

	/** Your account's value (without an account: this browser's). */
	get account(): T {
		return this.parse(preferences.get(this.key)) ?? this.fallback;
	}

	/** This device keeps its own value. */
	get onlyHere(): boolean {
		return this.key in device;
	}

	/** The value here. Reactive. */
	get value(): T {
		if (this.onlyHere) return this.parse(device[this.key]) ?? this.fallback;
		if (this.key in toMove) return this.parse(toMove[this.key]) ?? this.account;
		return this.account;
	}

	/** Changes it: on this device if it keeps its own, else on your account. */
	set(value: T): void {
		if (same(value, this.value)) return;
		const plain = $state.snapshot(value) as T;
		if (this.onlyHere) setDevice(this.key, plain);
		else if (this.key in toMove) {
			toMove = { ...toMove, [this.key]: plain };
			write(MOVE_KEY, JSON.stringify(toMove));
		} else preferences.set(this.key, same(plain, this.fallback) ? undefined : plain);
		changed();
	}

	/** Back to how it is at first (here, or on your account). */
	reset(): void {
		if (this.onlyHere) setDevice(this.key, $state.snapshot(this.fallback));
		else preferences.set(this.key, undefined);
		changed();
	}

	/**
	 * [on]: this device keeps its own value from now (the one it had before, else the current
	 * one); off: it follows your account again (its own value is kept for next time).
	 */
	keepHere(on: boolean): void {
		if (on === this.onlyHere) return;
		const before = this.parse(parked[this.key]);
		const { [this.key]: _, ...rest } = parked;
		parked = on ? rest : { ...rest, [this.key]: device[this.key] };
		write(PARKED_KEY, JSON.stringify(parked));
		setDevice(this.key, on ? (before ?? $state.snapshot(this.value)) : undefined);
		if (!on && this.key in toMove) {
			const { [this.key]: _, ...others } = toMove;
			toMove = others;
			write(MOVE_KEY, JSON.stringify(others));
		}
		changed();
	}
}

const oneOf =
	<T extends string>(...options: T[]) =>
	(raw: unknown): T | undefined =>
		options.includes(raw as T) ? (raw as T) : undefined;
const bool = (raw: unknown) => (typeof raw === 'boolean' ? raw : undefined);
const flag = (key: string): Old<boolean> => ({ key, parse: (raw) => raw === '1' });
const word = <T>(key: string, parse: (raw: unknown) => T | undefined): Old<T> => ({ key, parse });
const json = <T>(key: string, parse: (raw: unknown) => T | undefined): Old<T> => ({
	key,
	parse: (raw) => {
		try {
			return parse(JSON.parse(raw));
		} catch {
			return undefined;
		}
	}
});
const object = (raw: unknown) => (raw && typeof raw === 'object' && !Array.isArray(raw) ? (raw as Record<string, unknown>) : undefined);
const pageSize = (raw: unknown) => {
	const size = typeof raw === 'number' ? Math.round(raw) : NaN;
	return size >= PAGE_SIZES.min && size <= PAGE_SIZES.max ? size : undefined;
};

const count = (min: number, max: number) => (raw: unknown) => {
	const n = typeof raw === 'number' ? Math.round(raw) : NaN;
	return n >= min && n <= max ? n : undefined;
};

/** A few song counts, each within ADD_LIMITS, smallest first. */
const counts = (raw: unknown) => {
	if (!Array.isArray(raw)) return undefined;
	const valid = raw.filter((n) => count(1, ADD_LIMITS.max)(n) === n) as number[];
	return valid.length === raw.length && valid.length <= MAX_ASK_COUNTS ? [...new Set(valid)].sort((a, b) => a - b) : undefined;
};

const imageQuality = oneOf<ImageQuality>('high', 'low');
const videoArt = oneOf<VideoArt>('crop', 'fit');
const dislikeButton = oneOf<DislikeButton>('normal', 'dim', 'hidden');
const swipeOpens = oneOf<SwipeOpens>('tab', 'upnext');
const tabOrder = oneOf<TabOrder>('upnext', 'related');

export const settings = {
	// Playback
	/** Next while paused plays the next song (like YouTube Music), or keeps the room paused. */
	nextPlays: new Setting('nextPlays', true, bool, flag('ytmp.nextPlays')),
	/** Start radio also clears the songs that played before (above the current one). */
	radioClearsHistory: new Setting('radioClearsHistory', false, bool, flag('ytmp.radioClearsHistory')),
	/** Opening the app goes back into the solo room you were in, instead of the room list. */
	reopenSolo: new Setting('reopenSolo', true, bool),
	/** The same for party rooms. */
	reopenParty: new Setting('reopenParty', false, bool),
	/** Your other devices may switch "Play here" on and off on this one. */
	remotePlay: new Setting('remotePlay', true, bool, flag('ytmp.remotePlay')),
	/** The app skips silent parts of podcast episodes, when you listen alone. */
	skipSilence: new Setting('skipSilence', false, bool, flag('ytmp.skipSilence')),
	/** In a party room, pausing, skipping, seeking or playing something else asks first while others listen along. */
	askInParty: new Setting('askInParty', true, bool),
	/** In a party room where others listen along, Pause stops playing on this device instead (not playing here: it pauses, asked first). */
	pauseStopsHere: new Setting('pauseStopsHere', true, bool),

	// Adding songs
	/** At most this many songs of one playlist (or album) go into the queue without asking. */
	playlistMax: new Setting('playlistMax', 200, count(ADD_LIMITS.min, ADD_LIMITS.max)),
	/** More songs than this: asks first how many (0: never asks). */
	playlistAskOver: new Setting('playlistAskOver', 100, count(0, ADD_LIMITS.max)),
	/** The choices when it asks: add this many (the ones below the playlist's length show). */
	playlistAskCounts: new Setting<number[]>('playlistAskCounts', [100, 200], counts),
	/** When it asks, also: add all of it, however long. */
	playlistAskAll: new Setting('playlistAskAll', true, bool),
	/** When it asks, also: a number to type. */
	playlistAskCustom: new Setting('playlistAskCustom', true, bool),
	/** When it asks, also: the first ones or at random, for this time ([playlistPick] is how it starts). */
	playlistAskPick: new Setting('playlistAskPick', true, bool),
	/** Which songs of a longer playlist: the first ones, or picked at random from all of it. */
	playlistPick: new Setting('playlistPick', 'first' as PlaylistPick, oneOf<PlaylistPick>('first', 'random')),
	/** Something that takes a moment says what's going on after this many milliseconds (busy.svelte.ts). */
	busyDelay: new Setting('busyDelay', 150, count(0, BUSY_DELAYS.max)),

	// Look
	imageQuality: new Setting('imageQuality', 'high' as ImageQuality, imageQuality, word('ytmp.imageQuality', imageQuality)),
	videoArt: new Setting('videoArt', 'crop' as VideoArt, videoArt, word('ytmp.videoArt', videoArt)),
	/** How many songs a long list shows at first, and adds each time for more. */
	pageSize: new Setting('pageSize', 300, pageSize, { key: 'ytmp.pageSize', parse: (raw) => pageSize(Number(raw)) }),
	dislikeButton: new Setting('dislikeButton', 'normal' as DislikeButton, dislikeButton, word('ytmp.dislikeButton', dislikeButton)),
	swipeOpens: new Setting('swipeOpens', 'tab' as SwipeOpens, swipeOpens, word('ytmp.swipeOpens', swipeOpens)),
	tabOrder: new Setting('tabOrder', 'upnext' as TabOrder, tabOrder, word('ytmp.tabOrder', tabOrder)),

	// The rest keep their own shape (and check it where they're used).
	/** Taps and swipes on songs in lists, and in Up next (gestures.svelte.ts). */
	gesturesList: new Setting<Record<string, unknown>>('gesturesList', {}, object, json('ytmp.gestures.list', object)),
	gesturesQueue: new Setting<Record<string, unknown>>('gesturesQueue', {}, object, json('ytmp.gestures.queue', object)),
	/** Home and your library load new ones after this many minutes (0: only when you pull down). */
	homeRefresh: new Setting('homeRefresh', 6 * 60, count(0, 7 * 24 * 60)),
	/** How Home looks (home.svelte.ts). */
	home: new Setting<Record<string, unknown>>('home', {}, object, json('ytmp.home', object)),
	/** The song menu's layout (songMenu.svelte.ts). */
	songMenu: new Setting<unknown>('songMenu', undefined, (raw) => raw),
	/** How radios are made: Settings → Radio (radio.svelte.ts). */
	radio: new Setting<unknown>('radio', undefined, (raw) => raw),
	/** The Radio from chips above Up next that are hidden (mix, ytm, graph, device). */
	radioChipsHidden: new Setting<string[]>('radioChipsHidden', [], (raw) =>
		Array.isArray(raw) ? raw.filter((r): r is string => ['mix', 'ytm', 'graph', 'device'].includes(r as string)) : undefined
	),
	/** The YouTube Music playlist you saved to last (holding Save to playlist saves straight to it). */
	lastPlaylist: new Setting<{ id: string; title: string } | undefined>('lastPlaylist', undefined, (raw) => {
		const p = object(raw);
		return p && typeof p.id === 'string' && typeof p.title === 'string' ? { id: p.id, title: p.title } : undefined;
	}),
	/** What solo rooms leave out (soloLook.svelte.ts). */
	soloHidden: new Setting<unknown>('soloHidden', undefined, (raw) => raw),
	/** The Local tab shows the music on the network. */
	localNetwork: new Setting('localNetwork', true, bool, flag('ytmp.localNetwork'))
};

let moving: Promise<void> | null = null;

/**
 * Takes your settings from your account (once per page; coming back to it asks again), and
 * moves the values from before: onto the account when it has none, else they stay this
 * device's own when they're different.
 */
export function load(): Promise<void> {
	return (moving ??= (async () => {
		await preferences.load();
		// Not reachable now: next time (until then they count as this device's).
		if (!preferences.onAccount && listening.available) {
			moving = null;
			return;
		}
		for (const [key, value] of Object.entries(toMove)) {
			const account = preferences.get(key);
			if (account === undefined) preferences.set(key, value);
			else if (!same(account, value)) setDevice(key, value);
		}
		toMove = {};
		remove(MOVE_KEY);
	})());
}

/** Settings can be different on this device: you're logged in, so they follow your account. */
export const onAccount = () => preferences.onAccount;

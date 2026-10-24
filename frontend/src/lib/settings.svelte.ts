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

const DEVICE_KEY = 'ytmp.deviceSettings';
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

	/** [on]: this device keeps its own value from now (starting with the current one); off: it follows your account again. */
	keepHere(on: boolean): void {
		if (on === this.onlyHere) return;
		setDevice(this.key, on ? $state.snapshot(this.value) : undefined);
		if (!on && this.key in toMove) {
			const { [this.key]: _, ...rest } = toMove;
			toMove = rest;
			write(MOVE_KEY, JSON.stringify(rest));
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
	/** Your other devices may switch "Play here" on and off on this one. */
	remotePlay: new Setting('remotePlay', true, bool, flag('ytmp.remotePlay')),
	/** The app skips silent parts of podcast episodes, when you listen alone. */
	skipSilence: new Setting('skipSilence', false, bool, flag('ytmp.skipSilence')),
	/** In a party room, pausing, skipping, seeking or playing something else asks first while others listen along. */
	askInParty: new Setting('askInParty', true, bool),
	/** In a party room where others listen along, Pause stops playing on this device instead (not playing here: it pauses, asked first). */
	pauseStopsHere: new Setting('pauseStopsHere', false, bool),

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
	/** How Home looks (home.svelte.ts). */
	home: new Setting<Record<string, unknown>>('home', {}, object, json('ytmp.home', object)),
	/** The song menu's layout (songMenu.svelte.ts). */
	songMenu: new Setting<unknown>('songMenu', undefined, (raw) => raw),
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

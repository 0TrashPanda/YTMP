// How radios are made (docs/features/history-and-recommendations.md#radio--mix): the defaults of
// Settings → Radio, which the hold sheet of Start radio, Autoplay this and Find similar changes
// for one radio. One of your settings (settings.svelte.ts), so the same on all your devices.
import { settings } from './settings.svelte';
import type { RadioFrom, RadioOptions } from './protocol.gen';

export const RADIO_FROM: { value: RadioFrom; label: string; hint: string }[] = [
	{ value: 'mix', label: 'Mix', hint: "YouTube Music's radio with songs from your listening mixed in" },
	{ value: 'ytm', label: 'YouTube Music', hint: "Only YouTube Music's radio" },
	{ value: 'graph', label: 'Song graph', hint: 'Only what YTMP learned: your listening, everyone’s, and the radios it kept' },
	{ value: 'device', label: 'On this device', hint: 'Only songs that play without internet: your music files and saved songs' }
];

export const radioFromLabel = (from: RadioFrom) => RADIO_FROM.find((f) => f.value === from)?.label ?? 'Mix';

export const DEFAULT_RADIO: RadioOptions = {
	from: 'mix',
	discover: 0,
	withAccount: null,
	myTaste: true,
	perArtist: 0,
	recentHours: 0,
	leaveOutSkipped: false,
	mixEvery: 2,
	offline: 'graph',
	blockedSongs: [],
	blockedArtists: []
};

const FROM = new Set<RadioFrom>(['mix', 'ytm', 'graph', 'device']);
const num = (v: unknown, min: number, max: number, fallback: number) => (typeof v === 'number' && Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : fallback);
const strings = (v: unknown) => (Array.isArray(v) ? v.filter((s): s is string => typeof s === 'string').slice(0, 1000) : []);

/** Reads saved choices, keeping only what's valid. */
export function parseRadio(saved: unknown): RadioOptions {
	const s = saved && typeof saved === 'object' ? (saved as Partial<Record<keyof RadioOptions, unknown>>) : {};
	return {
		from: FROM.has(s.from as RadioFrom) ? (s.from as RadioFrom) : DEFAULT_RADIO.from,
		discover: num(s.discover, -1, 1, 0),
		withAccount: typeof s.withAccount === 'boolean' ? s.withAccount : null,
		myTaste: typeof s.myTaste === 'boolean' ? s.myTaste : true,
		perArtist: Math.round(num(s.perArtist, 0, 50, 0)),
		recentHours: Math.round(num(s.recentHours, 0, 24 * 30, 0)),
		leaveOutSkipped: s.leaveOutSkipped === true,
		mixEvery: Math.round(num(s.mixEvery, 1, 10, 2)),
		offline: s.offline === 'device' ? 'device' : 'graph',
		blockedSongs: strings(s.blockedSongs),
		blockedArtists: strings(s.blockedArtists)
	};
}

let parsedFrom: unknown = null;
let parsed = DEFAULT_RADIO;

/** Your defaults. Reactive. */
export function radioDefaults(): RadioOptions {
	const saved = settings.radio.value;
	if (saved !== parsedFrom) {
		parsedFrom = saved;
		parsed = parseRadio(saved);
	}
	return parsed;
}

export function setRadioDefaults(change: Partial<RadioOptions>): void {
	settings.radio.set({ ...radioDefaults(), ...change });
}

/** The choices for one radio: your defaults with what you picked for it. */
export const radioOptions = (picked: Partial<RadioOptions> = {}): RadioOptions => ({ ...radioDefaults(), ...picked });

/** The room's radio with another *Radio from* (the Up next chips), keeping your filters. */
export const withFrom = (current: RadioOptions | undefined, from: RadioFrom): RadioOptions => ({ ...radioDefaults(), ...(current ?? {}), from });

const lower = (s: string) => s.toLowerCase();

export const isBlockedArtist = (name: string) => radioDefaults().blockedArtists.some((a) => lower(a) === lower(name));
export const isBlockedSong = (id: string) => radioDefaults().blockedSongs.includes(id);

/** Never put [name] in a radio again (Dislike's hold), or again allow it. */
export function blockArtist(name: string, blocked = true): void {
	const rest = radioDefaults().blockedArtists.filter((a) => lower(a) !== lower(name));
	setRadioDefaults({ blockedArtists: blocked ? [...rest, name] : rest });
}

export function blockSong(id: string, blocked = true): void {
	const rest = radioDefaults().blockedSongs.filter((s) => s !== id);
	setRadioDefaults({ blockedSongs: blocked ? [...rest, id] : rest });
}

/** "Discover" as words, for the slider. */
export function discoverLabel(value: number): string {
	if (value <= -1) return 'Only songs you know';
	if (value < -0.15) return 'More songs you know';
	if (value >= 1) return 'Only songs new to you';
	if (value > 0.15) return 'More songs new to you';
	return 'No preference';
}

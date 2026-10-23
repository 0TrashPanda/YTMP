// The equalizer (docs/features/playback-and-outputs.md#equalizer): ten bands, presets, on or
// off. It acts on this device's own playback. In a browser it's one setting for the browser,
// done here with Web Audio (see WebPlayer). In the app the app's player does it, and keeps one
// setting for each thing the phone plays on: its speaker, wired headphones, each Bluetooth
// device; this page shows and changes the one that plays now.
import { nativeBridge } from './native';
import { read, write } from './storage';

/** The bands' centres in Hz; the app's player uses the same (android/.../Equalizer.kt). */
export const EQ_BANDS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000] as const;
/** How far a band goes up or down, in dB. */
export const EQ_MAX_DB = 12;
/** How wide each band is (peaking filters an octave apart). */
export const EQ_Q = 1.41;

export interface EqSettings {
	on: boolean;
	/** dB per band of [EQ_BANDS]. */
	gains: number[];
	/** The preset these gains come from; null when you moved the sliders yourself. */
	preset: string | null;
}

/** What the app's player plays on now, which the shown setting is for. */
export interface EqOutput {
	id: string;
	name: string;
}

const flat = () => EQ_BANDS.map(() => 0);

export const EQ_PRESETS: { id: string; name: string; gains: number[] }[] = [
	{ id: 'flat', name: 'Flat', gains: flat() },
	{ id: 'bass', name: 'Bass boost', gains: [6, 5, 4, 2, 0, 0, 0, 0, 0, 0] },
	{ id: 'lessBass', name: 'Less bass', gains: [-6, -5, -4, -2, 0, 0, 0, 0, 0, 0] },
	{ id: 'treble', name: 'Treble boost', gains: [0, 0, 0, 0, 0, 0, 2, 4, 5, 6] },
	{ id: 'lessTreble', name: 'Less treble', gains: [0, 0, 0, 0, 0, 0, -2, -4, -5, -6] },
	{ id: 'voice', name: 'Spoken word', gains: [-6, -5, -3, -1, 1, 3, 4, 3, 1, 0] },
	{ id: 'loudness', name: 'Loudness', gains: [6, 4, 2, 0, -1, 0, 0, 1, 3, 5] },
	{ id: 'pop', name: 'Pop', gains: [-1, 1, 3, 4, 3, 1, -1, -1, -1, -1] },
	{ id: 'rock', name: 'Rock', gains: [5, 4, 3, 1, -1, -1, 1, 3, 4, 5] },
	{ id: 'hiphop', name: 'Hip-hop', gains: [5, 4, 2, 3, -1, -1, 1, -1, 2, 3] },
	{ id: 'electronic', name: 'Electronic', gains: [5, 4, 1, 0, -2, 2, 1, 1, 4, 5] },
	{ id: 'acoustic', name: 'Acoustic', gains: [4, 4, 3, 1, 2, 1, 3, 3, 3, 2] },
	{ id: 'classical', name: 'Classical', gains: [4, 3, 3, 2, 0, 0, 0, 2, 3, 4] }
];

export const EQ_OFF: EqSettings = { on: false, gains: flat(), preset: 'flat' };

/** Turns down the whole sound by the biggest boost (dB, 0 or less), so boosted bands don't clip. */
export function eqHeadroom(gains: readonly number[]): number {
	return 0 - Math.max(0, ...gains) || 0;
}

/** [value] as settings, from storage or the app: anything odd becomes off and flat. */
export function eqSettings(value: unknown): EqSettings {
	const v = value as Partial<EqSettings> | null;
	const gains = Array.isArray(v?.gains) && v.gains.length === EQ_BANDS.length ? v.gains.map(Number) : null;
	if (!v || !gains || gains.some((g) => !Number.isFinite(g))) return { ...EQ_OFF, gains: flat() };
	return {
		on: v.on === true,
		gains: gains.map((g) => Math.max(-EQ_MAX_DB, Math.min(EQ_MAX_DB, g))),
		preset: typeof v.preset === 'string' && EQ_PRESETS.some((p) => p.id === v.preset) ? v.preset : null
	};
}

const KEY = 'ytmp.equalizer';

class Equalizer {
	settings = $state<EqSettings>({ ...EQ_OFF, gains: flat() });
	/** In the app: what the phone plays on now. Null in a browser. */
	output = $state<EqOutput | null>(null);
	/** This device can have an equalizer: a browser with Web Audio, or an app whose player has one (bridge version 14). */
	readonly available: boolean;

	private listeners = new Set<(settings: EqSettings) => void>();

	constructor() {
		if (typeof window === 'undefined') {
			this.available = false;
			return;
		}
		const bridge = nativeBridge;
		if (bridge) {
			this.available = !!bridge.equalizer;
			if (!bridge.equalizer) return;
			this.show(bridge.equalizer());
			// The phone switched to other headphones (and their setting), or another page changed it.
			window.__ytmpNative = { ...window.__ytmpNative, onEqualizer: (json) => this.show(json) };
		} else {
			this.available = 'AudioContext' in window;
			try {
				this.settings = eqSettings(JSON.parse(read(KEY) ?? 'null'));
			} catch {
				// Nothing saved: off.
			}
		}
	}

	set(settings: EqSettings): void {
		this.settings = settings;
		if (nativeBridge?.setEqualizer) nativeBridge.setEqualizer(JSON.stringify(settings));
		else write(KEY, JSON.stringify(settings));
		for (const listener of this.listeners) listener(settings);
	}

	setOn(on: boolean): void {
		this.set({ ...this.settings, on });
	}

	usePreset(id: string): void {
		const preset = EQ_PRESETS.find((p) => p.id === id);
		if (preset) this.set({ on: true, gains: [...preset.gains], preset: id });
	}

	setBand(index: number, db: number): void {
		const gains = [...this.settings.gains];
		gains[index] = db;
		// Back on a preset's gains: that preset again.
		const preset = EQ_PRESETS.find((p) => p.gains.every((g, i) => g === gains[i]))?.id ?? null;
		this.set({ on: true, gains, preset });
	}

	/** The browser's player: called now and on every change. */
	listen(listener: (settings: EqSettings) => void): () => void {
		this.listeners.add(listener);
		listener(this.settings);
		return () => this.listeners.delete(listener);
	}

	private show(json: string | null): void {
		try {
			const state = JSON.parse(json ?? 'null') as { output?: EqOutput; settings?: unknown } | null;
			this.output = state?.output ?? null;
			this.settings = eqSettings(state?.settings);
		} catch {
			// Keep what's shown.
		}
	}
}

export const equalizer = new Equalizer();

// The songs a Pixel's Now Playing recognized lately, for the search page's icon: tapping a song
// searches for it (docs/features/search.md). Only in the app, on a Pixel: the app reads Now
// Playing's notification once you allow notification access for it in Android's settings.
import { nativeBridge, type DetectedSong } from './native';

class Recognized {
	/** This phone has Now Playing (a Pixel in the app); the icon shows only then. */
	available = $state(false);
	/** YTMP may read Now Playing's notifications. */
	access = $state(false);
	/** Newest first. */
	songs = $state<DetectedSong[]>([]);

	constructor() {
		const bridge = nativeBridge;
		if (typeof window === 'undefined' || !bridge?.nowPlayingAvailable) return;
		this.available = bridge.nowPlayingAvailable();
		if (!this.available) return;
		this.refresh();
		window.__ytmpNative = { ...window.__ytmpNative, onNowPlaying: (songs) => (this.songs = songs) };
		// Coming back from Android's settings: the access may have changed.
		document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && this.refresh());
	}

	/** Asks the app what it knows now. */
	refresh(): void {
		const bridge = nativeBridge;
		if (!this.available || !bridge) return;
		this.access = bridge.nowPlayingAccess?.() ?? false;
		try {
			this.songs = JSON.parse(bridge.nowPlayingRecent?.() ?? '[]') as DetectedSong[];
		} catch {
			this.songs = [];
		}
	}

	/** Opens Android's notification access settings, where YTMP is allowed to read Now Playing. */
	askAccess(): void {
		nativeBridge?.openNowPlayingAccess?.();
	}
}

export const recognized = new Recognized();

import { proxiedAudioUrl } from './api';
import { artUrl, artistNames } from './format';
import { nativeBridge, type NativeBridge } from './native';
import type { RoomConnection } from './room.svelte';
import { saved } from './storage';
import { correction, targetPosition } from './sync';

/** Plays the room's audio on this device. */
export interface RoomPlayer {
	readonly enabled: boolean;
	readonly volume: number;
	readonly buffering: boolean;
	readonly error: string | null;
	/** Sync adjustment for this device in ms; positive plays earlier (for speaker/Bluetooth delay). */
	readonly syncOffsetMs: number;
	/** True when playback can start without a click (the Android app). */
	readonly canStartWithoutGesture: boolean;
	enable(): Promise<void>;
	disable(): void;
	setVolume(value: number): void;
	setSyncOffset(ms: number): void;
	/** Call whenever the room state changes. */
	sync(): void;
	destroy(): void;
}

export function createPlayer(room: RoomConnection): RoomPlayer {
	return nativeBridge ? new NativePlayer(room, nativeBridge) : new WebPlayer(room);
}

/**
 * Plays the room's audio in the browser, in sync with the host.
 *
 * Uses the direct stream URL first and switches to the host proxy for the rest of the
 * room when the direct URL doesn't work (see docs/implementation/playback-sync.md).
 */
class WebPlayer implements RoomPlayer {
	readonly canStartWithoutGesture = false;
	enabled = $state(false);
	volume = $state(saved.volume);
	syncOffsetMs = $state(saved.syncOffsetMs);
	buffering = $state(false);
	error = $state<string | null>(null);

	private audio = new Audio();
	private useProxy = false;
	private loadedItemId: string | null = null;
	private timer: ReturnType<typeof setInterval> | undefined;

	constructor(private readonly room: RoomConnection) {
		this.audio.preload = 'auto';
		this.audio.volume = this.volume;
		this.audio.addEventListener('waiting', () => (this.buffering = true));
		this.audio.addEventListener('playing', () => (this.buffering = false));
		this.audio.addEventListener('canplay', () => this.sync());
		this.audio.addEventListener('error', () => this.onError());
	}

	/** Must be called from a click: browsers only allow audio after a user gesture. */
	async enable(): Promise<void> {
		this.enabled = true;
		saved.listening = true;
		this.error = null;
		// Unlock audio right away, inside the gesture.
		this.audio.play().catch(() => {});
		this.setMediaKeys(true);
		this.sync();
		this.timer = setInterval(() => this.sync(), 500);
		await this.room.run({ kind: 'SetListening', on: true });
	}

	disable(): void {
		this.enabled = false;
		saved.listening = false;
		clearInterval(this.timer);
		this.audio.pause();
		this.setMediaKeys(false);
		this.room.run({ kind: 'SetListening', on: false });
	}

	setVolume(value: number): void {
		this.volume = value;
		this.audio.volume = value;
		saved.volume = value;
	}

	setSyncOffset(ms: number): void {
		this.syncOffsetMs = ms;
		saved.syncOffsetMs = ms;
		this.sync();
	}

	destroy(): void {
		clearInterval(this.timer);
		this.setMediaKeys(false);
		this.audio.pause();
		this.audio.removeAttribute('src');
		this.audio.load();
	}

	/** Brings the audio element in line with the room. Called on every state change and every second. */
	sync(): void {
		if (!this.enabled) return;
		const state = this.room.state;
		const now = state?.nowPlaying;
		if (!state || !now || !now.streamUrl) {
			if (!this.audio.paused) this.audio.pause();
			if (!now) this.unload();
			return;
		}

		if (navigator.mediaSession) navigator.mediaSession.playbackState = state.playback.playing ? 'playing' : 'paused';
		if (this.loadedItemId !== now.item.itemId) {
			this.loadedItemId = now.item.itemId;
			this.showMetadata();
			this.audio.src = this.useProxy ? proxiedAudioUrl(now.item.song.id) : now.streamUrl;
			this.audio.playbackRate = 1;
			return; // 'canplay' calls sync again.
		}
		if (this.audio.readyState < HTMLMediaElement.HAVE_METADATA) return;

		const playback = state.playback;
		const expectedMs = targetPosition(playback, this.room.hostNow(), this.syncOffsetMs);

		if (!playback.playing) {
			if (!this.audio.paused) this.audio.pause();
			if (Math.abs(this.audio.currentTime * 1000 - expectedMs) > 250) this.audio.currentTime = expectedMs / 1000;
			return;
		}

		const fix = correction(this.audio.currentTime * 1000 - expectedMs, this.audio.playbackRate, this.room.listeningAlone);
		if (fix.seek) this.audio.currentTime = expectedMs / 1000;
		if (this.audio.playbackRate !== fix.rate) this.audio.playbackRate = fix.rate;
		if (this.audio.paused) {
			this.audio.play().catch(() => {
				this.error = 'Tap "Play here" again to allow audio.';
			});
		}
	}

	/**
	 * Media keys (keyboard, headphones, the OS media overlay) would otherwise pause only the
	 * local audio, which the sync loop then undoes. Make them room commands instead.
	 */
	private setMediaKeys(on: boolean): void {
		const session = navigator.mediaSession;
		if (!session) return;
		const handlers: [MediaSessionAction, MediaSessionActionHandler][] = [
			['play', () => this.room.run({ kind: 'Play' })],
			['pause', () => this.room.run({ kind: 'Pause' })],
			['nexttrack', () => this.room.run({ kind: 'Skip' })],
			['previoustrack', () => this.room.run({ kind: 'Previous' })],
			['seekto', (details) => details.seekTime != null && this.room.run({ kind: 'Seek', positionMs: Math.round(details.seekTime * 1000) })]
		];
		for (const [action, handler] of handlers) {
			try {
				session.setActionHandler(action, on ? handler : null);
			} catch {
				// Not every browser supports every action.
			}
		}
		if (!on) {
			session.metadata = null;
			session.playbackState = 'none';
		}
	}

	private showMetadata(): void {
		const song = this.room.state?.nowPlaying?.item.song;
		if (!navigator.mediaSession || !song) return;
		const art = artUrl(song, 544);
		navigator.mediaSession.metadata = new MediaMetadata({
			title: song.title,
			artist: artistNames(song),
			album: song.album?.name ?? '',
			artwork: art ? [{ src: art, sizes: '544x544' }] : []
		});
	}

	private onError(): void {
		const now = this.room.state?.nowPlaying;
		if (!this.enabled || !now) return;
		if (!this.useProxy) {
			// The direct URL is probably tied to the host's IP; stream through the host instead.
			this.useProxy = true;
			this.loadedItemId = null;
			this.sync();
		} else {
			this.error = `Can't play "${now.item.song.title}" on this device.`;
		}
	}

	private unload(): void {
		if (this.loadedItemId === null) return;
		this.loadedItemId = null;
		this.audio.removeAttribute('src');
		this.audio.load();
	}
}

/**
 * In the Android app, audio is played natively (Media3), which also gives lock screen and
 * Bluetooth controls. This class only tells the app what should be playing; the app does
 * the syncing itself and sends media button presses back as room commands.
 */
class NativePlayer implements RoomPlayer {
	readonly canStartWithoutGesture = true;
	enabled = $state(false);
	volume = $state(saved.volume);
	syncOffsetMs = $state(saved.syncOffsetMs);
	buffering = $state(false);
	error = $state<string | null>(null);

	constructor(
		private readonly room: RoomConnection,
		private readonly bridge: NativeBridge
	) {
		window.__ytmpNative = {
			onCommand: (command) => void this.room.run(command),
			onStatus: (status) => {
				this.buffering = status === 'buffering';
				if (status === 'stopped') this.disable();
				if (status === 'error') this.error = "Can't play this song on this device.";
			}
		};
	}

	async enable(): Promise<void> {
		this.enabled = true;
		this.error = null;
		saved.listening = true;
		this.sync();
		await this.room.run({ kind: 'SetListening', on: true });
	}

	disable(): void {
		this.enabled = false;
		saved.listening = false;
		this.sync();
		this.room.run({ kind: 'SetListening', on: false });
	}

	setVolume(value: number): void {
		this.volume = value;
		saved.volume = value;
		this.sync();
	}

	setSyncOffset(ms: number): void {
		this.syncOffsetMs = ms;
		saved.syncOffsetMs = ms;
		this.sync();
	}

	sync(): void {
		const state = this.room.state;
		const now = state?.nowPlaying ?? null;
		const song = now?.item.song;
		this.bridge.playback(
			JSON.stringify({
				enabled: this.enabled,
				item:
					now && song
						? {
								itemId: now.item.itemId,
								songId: song.id,
								title: song.title,
								artist: artistNames(song),
								artUrl: artUrl(song, 544),
								durationMs: song.durationMs
							}
						: null,
				streamUrl: now?.streamUrl ?? null,
				proxyUrl: song ? new URL(proxiedAudioUrl(song.id), location.href).href : null,
				playing: state?.playback.playing ?? false,
				positionMs: state?.playback.positionMs ?? 0,
				hostTimeMs: state?.playback.hostTimeMs ?? 0,
				clockOffset: this.room.clockOffset,
				syncOffsetMs: this.syncOffsetMs,
				alone: this.room.listeningAlone,
				volume: this.volume
			})
		);
	}

	destroy(): void {
		this.enabled = false;
		this.sync();
		window.__ytmpNative = undefined;
	}
}

import { proxiedAudioUrl } from './api';
import type { RoomConnection } from './room.svelte';
import { saved } from './storage';

/** Above this drift we jump; below it we nudge the playback speed. */
const SEEK_THRESHOLD_MS = 1500;
const NUDGE_THRESHOLD_MS = 120;

/**
 * Plays the room's audio on this device, in sync with the host.
 *
 * Uses the direct stream URL first and switches to the host proxy for the rest of the
 * room when the direct URL doesn't work (see docs/implementation/playback-sync.md).
 */
export class Player {
	enabled = $state(false);
	volume = $state(saved.volume);
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
		this.sync();
		this.timer = setInterval(() => this.sync(), 1000);
		await this.room.run({ kind: 'SetListening', on: true });
	}

	disable(): void {
		this.enabled = false;
		saved.listening = false;
		clearInterval(this.timer);
		this.audio.pause();
		this.room.run({ kind: 'SetListening', on: false });
	}

	setVolume(value: number): void {
		this.volume = value;
		this.audio.volume = value;
		saved.volume = value;
	}

	destroy(): void {
		clearInterval(this.timer);
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

		if (this.loadedItemId !== now.item.itemId) {
			this.loadedItemId = now.item.itemId;
			this.audio.src = this.useProxy ? proxiedAudioUrl(now.item.song.id) : now.streamUrl;
			this.audio.playbackRate = 1;
			return; // 'canplay' calls sync again.
		}
		if (this.audio.readyState < HTMLMediaElement.HAVE_METADATA) return;

		const playback = state.playback;
		const expectedMs = playback.playing
			? playback.positionMs + (this.room.hostNow() - playback.hostTimeMs)
			: playback.positionMs;

		if (!playback.playing) {
			if (!this.audio.paused) this.audio.pause();
			if (Math.abs(this.audio.currentTime * 1000 - expectedMs) > 250) this.audio.currentTime = expectedMs / 1000;
			return;
		}

		const drift = this.audio.currentTime * 1000 - expectedMs;
		if (Math.abs(drift) > SEEK_THRESHOLD_MS) {
			this.audio.currentTime = Math.max(0, expectedMs / 1000);
			this.audio.playbackRate = 1;
		} else if (Math.abs(drift) > NUDGE_THRESHOLD_MS) {
			this.audio.playbackRate = drift > 0 ? 0.97 : 1.03;
		} else {
			this.audio.playbackRate = 1;
		}
		if (this.audio.paused) {
			this.audio.play().catch(() => {
				this.error = 'Tap "Play here" again to allow audio.';
			});
		}
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

// The sleep timer (docs/features/playback-and-outputs.md#sleep-timer): after some minutes, or
// at the end of the song, the sound fades out; then a solo room pauses, and in a shared room
// this device stops playing (the room plays on for the others). One timer per device, like
// YouTube Music's. In the app it runs in the app's player (it keeps going with the screen off,
// when this page is frozen); in a browser it runs here, while the room's page is open.
import { read, write } from './storage';
import { nativeBridge } from './native';
import type { RoomPlayer } from './player.svelte';
import type { NowPlaying, PlaybackStatus } from './protocol.gen';
import type { RoomConnection } from './room.svelte';
import { positionNow } from './sync';

/** The sound fades out over this long (ms) before it stops. */
export const FADE_MS = 10_000;
const LAST_KEY = 'ytmp.sleepLast';

/** When it stops: at [endsAt] (ms since 1970), or at the end of the song [itemId] (the queue item). */
export interface SleepSetting {
	endsAt: number | null;
	itemId: string | null;
}

/**
 * How long (ms) until it stops, on the wall clock; 0 when it's time. The end of a song that's
 * no longer playing is now; a song without a known length never ends by itself (Infinity).
 */
export function sleepLeft(setting: SleepSetting, now: NowPlaying | null | undefined, playback: PlaybackStatus | undefined, hostNow: number, wallNow: number): number {
	if (setting.endsAt !== null) return Math.max(0, setting.endsAt - wallNow);
	if (setting.itemId === null) return Infinity;
	if (!now || now.item.itemId !== setting.itemId || !playback) return 0;
	if (now.item.song.durationMs <= 0) return Infinity;
	const left = now.item.song.durationMs - positionNow(playback, hostNow);
	// An episode at another speed ends sooner (or later).
	return Math.max(0, left / (playback.speed ?? 1));
}

/** How loud it still plays (0–1) with [left] ms to go. */
export function sleepFade(left: number): number {
	return Math.max(0, Math.min(1, left / FADE_MS));
}

class SleepTimer {
	endsAt = $state<number | null>(null);
	itemId = $state<string | null>(null);
	readonly active = $derived(this.endsAt !== null || this.itemId !== null);

	private room: RoomConnection | null = null;
	private player: RoomPlayer | null = null;
	private timer: ReturnType<typeof setInterval> | undefined;

	constructor() {
		const bridge = nativeBridge;
		if (typeof window === 'undefined' || !bridge?.sleepTimer) return;
		// The app's player has the timer: show what it says.
		this.show(bridge.sleepTimer());
		window.__ytmpNative = { ...window.__ytmpNative, onSleepTimer: (json) => this.show(json) };
	}

	/** The room page this timer acts on (in a browser), or none. */
	attach(room: RoomConnection | null, player: RoomPlayer | null): void {
		this.room = room;
		this.player = player;
		this.run();
	}

	/** Stops in [minutes], or at the end of the song that's playing now ('song'). */
	set(when: number | 'song'): void {
		const itemId = when === 'song' ? (this.room?.state?.nowPlaying?.item.itemId ?? null) : null;
		if (when === 'song' && !itemId) return;
		write(LAST_KEY, String(when));
		this.change({ endsAt: when === 'song' ? null : Date.now() + when * 60_000, itemId });
	}

	/** The time set last (holding Sleep timer starts it again), or null. */
	get last(): number | 'song' | null {
		const raw = read(LAST_KEY);
		if (raw === 'song') return 'song';
		const minutes = Number(raw);
		return raw && minutes > 0 ? minutes : null;
	}

	cancel(): void {
		this.change({ endsAt: null, itemId: null });
	}

	/** Minutes (rounded up) until it stops, for the screen; null at the end of the song. */
	minutesLeft(wallNow: number): number | null {
		return this.endsAt === null ? null : Math.max(0, Math.ceil((this.endsAt - wallNow) / 60_000));
	}

	private change(setting: SleepSetting): void {
		this.endsAt = setting.endsAt;
		this.itemId = setting.itemId;
		const bridge = nativeBridge;
		if (bridge?.setSleepTimer) bridge.setSleepTimer(this.active ? JSON.stringify(setting) : 'null');
		else this.run();
	}

	private show(json: string | null): void {
		const setting = json ? (JSON.parse(json) as SleepSetting | null) : null;
		this.endsAt = setting?.endsAt ?? null;
		this.itemId = setting?.itemId ?? null;
	}

	/** In a browser: watches the clock while it's set (also from media events, which background tabs still get). */
	private run(): void {
		clearInterval(this.timer);
		if (nativeBridge?.setSleepTimer) return;
		if (!this.active) {
			this.player?.setFade?.(1);
			return;
		}
		this.timer = setInterval(() => this.tick(), 250);
	}

	private tick(): void {
		const room = this.room;
		if (!this.active || !room) return;
		const left = sleepLeft(this, room.state?.nowPlaying, room.state?.playback, room.hostNow(), Date.now());
		this.player?.setFade?.(sleepFade(left));
		if (left <= 0) void this.fire(room, this.player);
	}

	/** Time to stop: a solo room pauses, elsewhere this device stops playing. */
	private async fire(room: RoomConnection, player: RoomPlayer | null): Promise<void> {
		this.endsAt = this.itemId = null;
		clearInterval(this.timer);
		if (room.state?.room.visibility === 'private' && room.can('play_pause')) {
			// Silent until the room has paused (the next song may be starting).
			player?.setFade?.(0);
			await room.run({ kind: 'Pause' }, false);
			await new Promise((resolve) => setTimeout(resolve, 500));
		} else if (player?.enabled) {
			player.disable();
		}
		player?.setFade?.(1);
	}
}

export const sleepTimer = new SleepTimer();

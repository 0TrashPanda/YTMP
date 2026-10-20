import { describe, expect, it } from 'vitest';
import type { NowPlaying, PlaybackStatus } from './protocol.gen';
import { FADE_MS, sleepFade, sleepLeft } from './sleepTimer.svelte';

const now = (itemId: string, durationMs: number) => ({ item: { itemId, song: { durationMs } }, streamUrl: 'x' }) as unknown as NowPlaying;
const playback = (positionMs: number, speed = 1) => ({ playing: true, positionMs, hostTimeMs: 1000, speed, starting: false }) as unknown as PlaybackStatus;

describe('sleep timer', () => {
	it('counts down to a time', () => {
		expect(sleepLeft({ endsAt: 60_000, itemId: null }, null, undefined, 0, 15_000)).toBe(45_000);
		expect(sleepLeft({ endsAt: 60_000, itemId: null }, null, undefined, 0, 70_000)).toBe(0);
	});

	it('or to the end of the song, at the room speed', () => {
		const setting = { endsAt: null, itemId: 'a' };
		// 200 s song at 150 s, 10 s later on the host's clock: 40 s to go.
		expect(sleepLeft(setting, now('a', 200_000), playback(150_000), 11_000, 0)).toBe(40_000);
		// An episode at 2x: half that.
		expect(sleepLeft(setting, now('a', 200_000), playback(150_000, 2), 1_000, 0)).toBe(25_000);
		// The song moved on: time to stop.
		expect(sleepLeft(setting, now('b', 200_000), playback(0), 1_000, 0)).toBe(0);
		// Without a length it can't end by itself.
		expect(sleepLeft(setting, now('a', 0), playback(5_000), 1_000, 0)).toBe(Infinity);
	});

	it('fades out over the last seconds', () => {
		expect(sleepFade(Infinity)).toBe(1);
		expect(sleepFade(FADE_MS * 2)).toBe(1);
		expect(sleepFade(FADE_MS / 2)).toBe(0.5);
		expect(sleepFade(0)).toBe(0);
	});
});

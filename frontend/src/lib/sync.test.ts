import { describe, expect, it } from 'vitest';
import { correction, looseCorrection, ownRate, targetPosition } from './sync';

describe('correction', () => {
	it('jumps when far off, speeds up or slows down when a little off', () => {
		expect(correction(500, 1)).toEqual({ seek: true, rate: 1 });
		expect(correction(60, 1)).toEqual({ seek: false, rate: 0.97 });
		expect(correction(-60, 1)).toEqual({ seek: false, rate: 1.03 });
		expect(correction(20, 1)).toEqual({ seek: false, rate: 1 });
	});

	it('keeps correcting until close, and stops when it overshot', () => {
		expect(correction(20, 0.97)).toEqual({ seek: false, rate: 0.97 });
		expect(correction(5, 0.97)).toEqual({ seek: false, rate: 1 });
		expect(correction(-20, 0.97)).toEqual({ seek: false, rate: 1 });
	});

	it('alone, only fixes big drift, by jumping', () => {
		expect(correction(1000, 1, true)).toEqual({ seek: false, rate: 1 });
		expect(correction(2000, 1, true)).toEqual({ seek: true, rate: 1 });
	});

	it('a Chromecast jumps only for clear drift, and not while settling', () => {
		expect(looseCorrection(1500, false).seek).toBe(true);
		expect(looseCorrection(1500, true).seek).toBe(false);
		expect(looseCorrection(500, false).seek).toBe(false);
	});
});

describe('targetPosition', () => {
	it('moves on while playing, with the sync adjustment', () => {
		const playing = { playing: true, positionMs: 10_000, hostTimeMs: 1_000, starting: false, speed: 1, loading: false };
		expect(targetPosition(playing, 3_000, 0)).toBe(12_000);
		expect(targetPosition(playing, 3_000, 200)).toBe(12_200);
		expect(targetPosition({ ...playing, playing: false }, 3_000, 0)).toBe(10_000);
		expect(targetPosition({ ...playing, positionMs: 0 }, 1_000, -500)).toBe(0);
	});

	it('an episode at 1.5 moves on one and a half times as fast', () => {
		const fast = { playing: true, positionMs: 10_000, hostTimeMs: 1_000, starting: false, speed: 1.5, loading: false };
		expect(targetPosition(fast, 3_000, 0)).toBe(13_000);
		expect(targetPosition(fast, 3_000, 200)).toBe(13_300);
	});
});

describe('ownRate', () => {
	it('is the player speed apart from the room speed', () => {
		expect(ownRate(1.455, 1.5)).toBe(0.97);
		expect(ownRate(1.5, 1.5)).toBe(1);
		expect(ownRate(1.03, 1)).toBe(1.03);
		// Corrections at 1.5 work like at 1.
		expect(correction(60, ownRate(1.5, 1.5)).rate * 1.5).toBeCloseTo(1.455);
	});
});

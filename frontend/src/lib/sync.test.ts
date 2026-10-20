import { describe, expect, it } from 'vitest';
import { correction, looseCorrection, targetPosition } from './sync';

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
		const playing = { playing: true, positionMs: 10_000, hostTimeMs: 1_000, starting: false };
		expect(targetPosition(playing, 3_000, 0)).toBe(12_000);
		expect(targetPosition(playing, 3_000, 200)).toBe(12_200);
		expect(targetPosition({ ...playing, playing: false }, 3_000, 0)).toBe(10_000);
		expect(targetPosition({ ...playing, positionMs: 0 }, 1_000, -500)).toBe(0);
	});
});

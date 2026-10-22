import { describe, expect, it } from 'vitest';
import { EQ_BANDS, EQ_OFF, EQ_PRESETS, eqHeadroom, eqSettings } from './equalizer.svelte';

describe('equalizer', () => {
	it('turns the sound down by the biggest boost', () => {
		expect(eqHeadroom([0, 6, 3, -12])).toBe(-6);
		expect(eqHeadroom([-3, -1])).toBe(0);
	});

	it('has a gain per band in every preset, within range', () => {
		for (const preset of EQ_PRESETS) {
			expect(preset.gains).toHaveLength(EQ_BANDS.length);
			expect(preset.gains.every((g) => Math.abs(g) <= 12)).toBe(true);
		}
	});

	it('reads saved settings, and anything odd as off', () => {
		const bass = EQ_PRESETS.find((p) => p.id === 'bass')!;
		expect(eqSettings({ on: true, gains: bass.gains, preset: 'bass' })).toEqual({ on: true, gains: bass.gains, preset: 'bass' });
		expect(eqSettings({ on: true, gains: [1, 2] })).toEqual(EQ_OFF);
		expect(eqSettings(null)).toEqual(EQ_OFF);
		const loud = eqSettings({ on: true, gains: EQ_BANDS.map(() => 40), preset: 'nope' });
		expect(loud.gains.every((g) => g === 12)).toBe(true);
		expect(loud.preset).toBeNull();
	});
});

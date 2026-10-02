// Keeping local playback in sync with the room. The same rules are in
// android/.../SyncCorrection.kt; keep the two in step.

import type { PlaybackStatus } from './protocol.gen';

/** Further off than this, jump instead of speeding up or slowing down. */
export const SEEK_THRESHOLD_MS = 400;
/** Start correcting when further off than this… */
export const START_CORRECTING_MS = 40;
/** …and stop once closer than this. The gap avoids flipping speed back and forth. */
export const STOP_CORRECTING_MS = 10;
/** Speed change while correcting: 3% faster or slower. Pitch is kept. */
export const CORRECTION_RATE = 0.03;
/**
 * Listening alone (nobody else plays along): only fix drift bigger than this, by jumping.
 * Smaller corrections would only be audible for no reason.
 */
export const ALONE_TOLERANCE_MS = 1500;

export interface Correction {
	seek: boolean;
	rate: number;
}

/**
 * How to fix [driftMs] (positive = we are ahead of where we should be), given the speed
 * we are playing at now. The speed only changes when starting or stopping a correction,
 * because every speed change makes the player's position briefly unreliable.
 */
export function correction(driftMs: number, currentRate: number, alone = false): Correction {
	const size = Math.abs(driftMs);
	if (alone) return { seek: size > ALONE_TOLERANCE_MS, rate: 1 };
	if (size > SEEK_THRESHOLD_MS) return { seek: true, rate: 1 };
	const correctingRate = driftMs > 0 ? 1 - CORRECTION_RATE : 1 + CORRECTION_RATE;
	const correcting = currentRate !== 1;
	if (correcting) {
		// Keep going until close enough, or until we overshot to the other side.
		return { seek: false, rate: size < STOP_CORRECTING_MS || currentRate !== correctingRate ? 1 : currentRate };
	}
	return { seek: false, rate: size > START_CORRECTING_MS ? correctingRate : 1 };
}

/**
 * Where this device's player should be. [syncOffsetMs] is the device's sync adjustment:
 * positive plays earlier, to make up for speaker or Bluetooth delay.
 */
export function targetPosition(playback: PlaybackStatus, hostNow: number, syncOffsetMs: number): number {
	const position = playback.playing ? playback.positionMs + (hostNow - playback.hostTimeMs) : playback.positionMs;
	return Math.max(0, position + syncOffsetMs);
}

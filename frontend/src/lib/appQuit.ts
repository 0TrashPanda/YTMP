// The desktop app closing (its window closed, or restarting for an update): this device stops
// playing the room before it goes, so the room doesn't play on for nobody. A closed app sends
// no goodbye; the host would only notice the lost connection after a while, and then keeps a
// device that played for another minute (Room.holdPlayer: meant for a reload or a network drop).
// "Playing here" itself stays on (saved.listening): the app opens playing here again.

import type { RoomPlayer } from './player.svelte';
import type { RoomConnection } from './room.svelte';

/** The room the app played before it restarted for an update, so it plays on after. */
const RESUME_KEY = 'ytmp.resumeAfterAppUpdate';
/** An update that takes longer than this isn't "the same moment" any more. */
const RESUME_MAX_AGE_MS = 10 * 60_000;

/** What the app keeps over an update's restart. */
export interface Resume {
	room: string;
	/** The song that played, when the room played: it plays on if it's still there and paused. */
	itemId: string | null;
	at: number;
}

/**
 * The app is closing: if this device plays [room], it says it stops (the room pauses when
 * nothing else plays it). With [forUpdate], it remembers to play on after the restart.
 */
export async function leaveForQuit(room: RoomConnection, player: RoomPlayer, forUpdate: boolean, storage: Storage = localStorage): Promise<void> {
	if (!player.enabled || room.status !== 'connected') return;
	if (forUpdate) {
		const state = room.state;
		const resume: Resume = {
			room: room.roomCode,
			itemId: state?.playback.playing ? (state.nowPlaying?.item.itemId ?? null) : null,
			at: Date.now()
		};
		try {
			storage.setItem(RESUME_KEY, JSON.stringify(resume));
		} catch {
			// Not kept: it opens with "Playing here" as saved, without playing on.
		}
	}
	await room.run({ kind: 'SetListening', on: false, startsTogether: false, ownLookups: 'none' }, false);
}

/** The app restarted for an update while it played [roomCode]: it goes back into that room (not asked away). */
export function resumePending(roomCode: string, storage: Storage = localStorage, now = Date.now()): boolean {
	try {
		const resume = JSON.parse(storage.getItem(RESUME_KEY) ?? 'null') as Resume | null;
		return resume?.room === roomCode && now - resume.at < RESUME_MAX_AGE_MS;
	} catch {
		return false;
	}
}

/** The app just restarted for an update while it played [roomCode]: what it played (asked once). */
export function takeResume(roomCode: string, storage: Storage = localStorage, now = Date.now()): Resume | null {
	try {
		const text = storage.getItem(RESUME_KEY);
		if (!text) return null;
		const resume = JSON.parse(text) as Resume;
		if (resume.room !== roomCode) return null;
		storage.removeItem(RESUME_KEY);
		return now - resume.at < RESUME_MAX_AGE_MS ? resume : null;
	} catch {
		return null;
	}
}

/**
 * Back after the update and playing here again: the room paused when this device left (nothing
 * else played it), so it plays on, if it's still on the same song and paused.
 */
export function playsOn(resume: Resume, room: RoomConnection): boolean {
	const state = room.state;
	if (!resume.itemId || !state || state.playback.playing) return false;
	return state.nowPlaying?.item.itemId === resume.itemId;
}

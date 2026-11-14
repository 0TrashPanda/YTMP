import { describe, expect, it } from 'vitest';
import { leaveForQuit, playsOn, resumePending, takeResume } from './appQuit';
import type { RoomPlayer } from './player.svelte';
import type { RoomConnection } from './room.svelte';

function memory(): Storage {
	const items = new Map<string, string>();
	return {
		get length() {
			return items.size;
		},
		clear: () => items.clear(),
		getItem: (key) => items.get(key) ?? null,
		key: (i) => [...items.keys()][i] ?? null,
		removeItem: (key) => void items.delete(key),
		setItem: (key, value) => void items.set(key, value)
	};
}

function room(playing: boolean, itemId = 'a') {
	const sent: unknown[] = [];
	const connection = {
		roomCode: 'ROOM',
		status: 'connected',
		state: { playback: { playing }, nowPlaying: { item: { itemId } } },
		run: async (command: unknown) => {
			sent.push(command);
			return null;
		}
	} as unknown as RoomConnection;
	return { connection, sent };
}

const playingHere = { enabled: true } as RoomPlayer;

describe('appQuit', () => {
	it('stops playing here when the app closes, without remembering to play on', async () => {
		const storage = memory();
		const { connection, sent } = room(true);
		await leaveForQuit(connection, playingHere, false, storage);
		expect(sent).toEqual([{ kind: 'SetListening', on: false, startsTogether: false }]);
		expect(takeResume('ROOM', storage)).toBeNull();
	});

	it('does nothing when it does not play here', async () => {
		const { connection, sent } = room(true);
		await leaveForQuit(connection, { enabled: false } as RoomPlayer, true, memory());
		expect(sent).toEqual([]);
	});

	it('plays on after an update, once, in the same room, on the same song', async () => {
		const storage = memory();
		await leaveForQuit(room(true).connection, playingHere, true, storage);
		expect(takeResume('OTHER', storage)).toBeNull();
		expect(resumePending('ROOM', storage)).toBe(true);
		expect(resumePending('OTHER', storage)).toBe(false);
		const resume = takeResume('ROOM', storage)!;
		expect(resume.itemId).toBe('a');
		expect(takeResume('ROOM', storage)).toBeNull();
		expect(resumePending('ROOM', storage)).toBe(false);
		expect(playsOn(resume, room(false).connection)).toBe(true);
		expect(playsOn(resume, room(true).connection)).toBe(false);
		expect(playsOn(resume, room(false, 'b').connection)).toBe(false);
	});

	it('does not start a room that was paused before the update', async () => {
		const storage = memory();
		await leaveForQuit(room(false).connection, playingHere, true, storage);
		const resume = takeResume('ROOM', storage)!;
		expect(resume).not.toBeNull();
		expect(playsOn(resume, room(false).connection)).toBe(false);
	});

	it('forgets an update that took too long', async () => {
		const storage = memory();
		await leaveForQuit(room(true).connection, playingHere, true, storage);
		expect(takeResume('ROOM', storage, Date.now() + 11 * 60_000)).toBeNull();
	});
});

import { describe, expect, it } from 'vitest';
import { coming, keepLabel, missing, offlineLine, offlineNote } from './offlinePlaylists.svelte';
import type { OfflinePlaylist, OfflinePlaylists } from './protocol.gen';

const playlist = (p: Partial<OfflinePlaylist>): OfflinePlaylist => ({
	id: 'PL1',
	title: 'Road trip',
	thumbnails: [],
	kept: true,
	mostPlayed: false,
	songs: 10,
	here: 10,
	notKept: 0,
	tooLong: false,
	read: true,
	...p
});
const list = (playlists: OfflinePlaylist[], l: Partial<OfflinePlaylists> = {}): OfflinePlaylists => ({ playlists, waiting: null, busy: false, error: null, full: null, ...l });

describe('playlists kept offline', () => {
	it('the button says how far it is', () => {
		expect(keepLabel(null)).toBe('Keep offline');
		expect(keepLabel(playlist({ kept: false }))).toBe('Keep offline');
		expect(keepLabel(playlist({ here: 4 }))).toBe('Offline · 4/10');
		expect(keepLabel(playlist({}))).toBe('Offline');
		expect(keepLabel(playlist({ read: false, songs: 0, here: 0 }))).toBe('Offline · getting ready');
	});

	it('the settings say how many songs are here, and why not all', () => {
		expect(offlineLine(playlist({}))).toBe('All 10 songs on this phone');
		expect(offlineLine(playlist({ here: 9, notKept: 1 }))).toBe(
			"9 of 10 songs on this phone · 1 can't be kept in the cache (podcast episodes, songs over an hour): download it from the song menu"
		);
		expect(offlineLine(playlist({ tooLong: true }))).toContain('Too many songs');
	});

	it('songs the cache can never keep are not waited for', () => {
		expect(missing(playlist({ here: 9, notKept: 1 }))).toBe(0);
		expect(coming(list([playlist({ here: 9, notKept: 1 })]))).toBe(false);
		expect(coming(list([playlist({ here: 3 })]))).toBe(true);
		// Waiting for Wi-Fi (or a full cache): nothing comes in by looking again.
		expect(coming(list([playlist({ here: 3 })], { waiting: 'Wi-Fi' }))).toBe(false);
		// Complete playlists that aren't kept don't count.
		expect(coming(list([playlist({ kept: false, here: 3 })]))).toBe(false);
	});

	it('says why nothing comes in', () => {
		expect(offlineNote(list([], { waiting: 'full', full: 'Road trip' }))).toBe('The cache is full before all of "Road trip" fits: a bigger size keeps more.');
		expect(offlineNote(list([], { waiting: 'Wi-Fi' }))).toBe('Waiting for Wi-Fi.');
		expect(offlineNote(list([]))).toBeNull();
	});
});

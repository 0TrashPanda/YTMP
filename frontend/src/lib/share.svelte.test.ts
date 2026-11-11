import { describe, expect, it } from 'vitest';
import { roomLinkWith, sharedFromUrl, songLink } from './share.svelte';
import { parseLink } from './links';

describe('song links', () => {
	it('shares the YouTube Music link', () => {
		expect(songLink({ id: 'ytm:dQw4w9WgXcQ' })).toBe('https://music.youtube.com/watch?v=dQw4w9WgXcQ');
		expect(songLink({ id: 'yt:dQw4w9WgXcQ' })).toBe('https://music.youtube.com/watch?v=dQw4w9WgXcQ');
	});

	it('has none for music only on this device', () => {
		expect(songLink({ id: 'local:abc' })).toBeNull();
		expect(songLink({ id: 'device:42' })).toBeNull();
	});

	it('is a link YTMP opens again as that song', () => {
		expect(parseLink(songLink({ id: 'ytm:dQw4w9WgXcQ' })!)).toEqual({ kind: 'song', id: 'ytm:dQw4w9WgXcQ', playlist: null });
	});
});

describe('playing a shared song in a room', () => {
	it('goes there in the room link and back out', () => {
		const link = roomLinkWith('/room/ABCD', 'ytm:dQw4w9WgXcQ', 'next');
		expect(link).toBe('/room/ABCD?shared=ytm%3AdQw4w9WgXcQ&do=next');
		expect(sharedFromUrl(new URL(link, 'http://127.0.0.1:8765'))).toEqual({ songId: 'ytm:dQw4w9WgXcQ', action: 'next' });
	});

	it('ignores anything else', () => {
		const base = 'http://127.0.0.1:8765/room/ABCD';
		expect(sharedFromUrl(new URL(base))).toBeNull();
		expect(sharedFromUrl(new URL(`${base}?shared=ytm:dQw4w9WgXcQ&do=delete`))).toBeNull();
		expect(sharedFromUrl(new URL(`${base}?shared=local:abc&do=play`))).toBeNull();
		expect(sharedFromUrl(new URL(`${base}?shared=ytm:dQw4w9WgXcQ`))).toBeNull();
	});
});

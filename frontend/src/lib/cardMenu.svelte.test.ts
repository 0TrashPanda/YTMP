import { describe, expect, it } from 'vitest';
import { cardLink } from './cardMenu.svelte';

describe('cardLink', () => {
	const target = (kind: 'album' | 'playlist' | 'artist' | 'podcast', id: string) => ({ kind, id, title: 't', subtitle: '' });

	it("links to the card's page on YouTube Music", () => {
		expect(cardLink(target('album', 'MPREb_abc'))).toBe('https://music.youtube.com/browse/MPREb_abc');
		expect(cardLink(target('artist', 'UC123'))).toBe('https://music.youtube.com/channel/UC123');
		expect(cardLink(target('playlist', 'VLPL42'))).toBe('https://music.youtube.com/playlist?list=PL42');
		expect(cardLink(target('podcast', 'MPSPPL9'))).toBe('https://music.youtube.com/browse/MPSPPL9');
		expect(cardLink(target('podcast', 'PL9'))).toBe('https://music.youtube.com/playlist?list=PL9');
	});
});

import { describe, expect, it } from 'vitest';
import { PageStack } from './pageStack.svelte';

function stack() {
	const scrolled: number[] = [];
	let at = 0;
	const pages = new PageStack({
		scrollTop: () => at,
		scrollTo: (y) => {
			scrolled.push(y);
			at = y;
		},
		toTop: () => scrolled.push(0),
		leaving: () => {}
	});
	return { pages, scrolled, scroll: (y: number) => (at = y) };
}

describe('PageStack', () => {
	it('goes back through the pages, scrolled as you left them', () => {
		const { pages, scrolled, scroll } = stack();
		pages.show({ kind: 'artist', id: 'a', name: 'Artist' });
		scroll(300);
		pages.show({ kind: 'album', id: 'b', title: 'Album' });
		expect(pages.top.kind).toBe('album');
		expect(pages.hasBackLink).toBe(true);
		expect(pages.back()).toBe(true);
		expect(pages.top.kind).toBe('artist');
		expect(scrolled.at(-1)).toBe(300);
		expect(pages.back()).toBe(true);
		expect(pages.top.kind).toBe('home');
		expect(pages.back()).toBe(false);
	});

	it('a new search replaces the one on top', () => {
		const { pages } = stack();
		pages.show({ kind: 'search', query: 'daft' });
		pages.show({ kind: 'search', query: 'daft punk' });
		expect(pages.pages.length).toBe(2);
		expect(pages.query).toBe('daft punk');
	});

	it('the bottom bar starts over, with Home under it', () => {
		const { pages } = stack();
		pages.show({ kind: 'artist', id: 'a', name: 'Artist' });
		pages.pick('library');
		expect(pages.pages.map((p) => p.kind)).toEqual(['home', 'library']);
		expect(pages.section).toBe('library');
		pages.show({ kind: 'history' });
		expect(pages.view?.kind).toBe('history');
		expect(pages.hasBackLink).toBe(false);
		pages.pick('home');
		expect(pages.pages.map((p) => p.kind)).toEqual(['home']);
	});
});

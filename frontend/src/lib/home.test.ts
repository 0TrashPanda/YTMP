import { describe, expect, it } from 'vitest';
import { arrange, isPodcasts, learn, loadPrefs } from './home.svelte';
import type { HomeSection, SearchItem } from './protocol.gen';

const song = (id: string, podcast = false): SearchItem =>
	({
		kind: 'song',
		song: {
			id,
			title: id,
			artists: [],
			album: null,
			durationMs: 0,
			thumbnails: [],
			explicit: false,
			podcast: podcast ? { id: 'p', name: 'P' } : null
		},
		video: false
	}) as SearchItem;
const section = (title: string, items: SearchItem[] = [song('a')]): HomeSection => ({ title, items });

describe('home sections', () => {
	it('puts new names after the one YTM shows before them', () => {
		expect(learn(['B', 'A'], ['A', 'New', 'B'])).toEqual(['B', 'A', 'New']);
		expect(learn(['B'], ['First', 'B'])).toEqual(['First', 'B']);
	});

	it('shows sections in your order, without hidden ones, each with its layout', () => {
		const prefs = {
			...loadPrefs(null),
			order: ['Listen again', 'Quick picks'],
			hidden: ['Mixed for you'],
			lists: ['Quick picks']
		};
		const shown = arrange([section('Quick picks'), section('Mixed for you'), section('Listen again'), section('New')], prefs);
		expect(shown.map((s) => [s.title, s.layout])).toEqual([
			['Listen again', 'carousel'],
			// YTM shows it after Listen again.
			['New', 'carousel'],
			['Quick picks', 'list']
		]);
	});

	it('can hide podcast sections', () => {
		const podcasts = section('Podcasts for you', [song('e1', true), song('e2', true), song('x')]);
		expect(isPodcasts(podcasts)).toBe(true);
		expect(isPodcasts(section('Quick picks'))).toBe(false);
		const shown = arrange([section('Quick picks'), podcasts], {
			...loadPrefs(null),
			hidePodcasts: true
		});
		expect(shown.map((s) => s.title)).toEqual(['Quick picks']);
	});

	it('reads what was saved, and survives junk', () => {
		expect(loadPrefs('nonsense').order).toEqual([]);
		expect(loadPrefs('{"order":["A","A",3],"hidePodcasts":true}')).toMatchObject({ order: ['A'], hidePodcasts: true });
	});
});

describe('pins', () => {
	it('keeps the pins that make sense', () => {
		const prefs = loadPrefs(
			JSON.stringify({ pins: [{ kind: 'playlist', id: 'LM', title: 'Liked music', personal: true }, { kind: 'song', id: 'x', title: 'X' }, { kind: 'album', id: 'a' }] })
		);
		expect(prefs.pins).toEqual([{ kind: 'playlist', id: 'LM', title: 'Liked music', art: undefined, personal: true }]);
	});
});

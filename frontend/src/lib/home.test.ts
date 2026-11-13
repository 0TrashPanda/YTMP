import { describe, expect, it } from 'vitest';
import { arrange, FRIENDS, isPodcasts, learn, loadPrefs, PINNED, withPins, type Pin } from './home.svelte';
import type { HomeSection, ReceivedShare, SearchItem, Song } from './protocol.gen';

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
const section = (title: string, items: SearchItem[] = [song('a')]): HomeSection => ({ title, items, strapline: null });

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
			layouts: { 'Quick picks': 'list' as const }
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
		expect(loadPrefs(null).fullDialPages).toBe(true);
		expect(loadPrefs('{"fullDialPages":false}').fullDialPages).toBe(false);
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

describe('pinned sections', () => {
	const pin = (id: string, contents = false): Pin => ({ kind: 'playlist', id, title: id, contents: contents || undefined });

	it('shows tiles in Pinned, and a pin with its songs as its own section, both above YTM', () => {
		const prefs = { ...loadPrefs(null), pins: [pin('LM', true), pin('a'), pin('b')] };
		const shown = arrange([section('Quick picks')], prefs);
		expect(shown.map((b) => b.key)).toEqual([PINNED, 'ytmp:pin:playlist:LM', 'Quick picks']);
		expect(shown[0].kind === 'pinned' && shown[0].pins.map((p) => p.id)).toEqual(['a', 'b']);
	});

	it('shows pins while Home loads, and they move and hide like any section', () => {
		const prefs = { ...loadPrefs(null), pins: [pin('LM', true), pin('a')], order: ['Quick picks', 'ytmp:pin:playlist:LM'], hidden: [PINNED] };
		expect(arrange(null, prefs).map((b) => b.key)).toEqual(['ytmp:pin:playlist:LM']);
		expect(arrange([section('Quick picks')], prefs).map((b) => b.key)).toEqual(['Quick picks', 'ytmp:pin:playlist:LM']);
	});

	it('forgets the place of a pin that is gone', () => {
		expect(withPins(['ytmp:pin:playlist:old', 'Quick picks'], { ...loadPrefs(null), pins: [pin('a')] })).toEqual([PINNED, 'Quick picks']);
	});
});

describe('from your friends', () => {
	const share = (id: string): ReceivedShare => ({
		id,
		song: (song(id) as Extract<SearchItem, { kind: 'song' }>).song as Song,
		from: { username: 'bob', displayName: 'Bob' },
		group: 'Hardcore',
		note: '',
		at: 0
	});

	it('shows above YTM’s sections when something is shared, and not when nothing is', () => {
		const prefs = loadPrefs(null);
		expect(arrange([section('Quick picks')], prefs, [share('s1')]).map((b) => b.key)).toEqual([FRIENDS, 'Quick picks']);
		expect(arrange([section('Quick picks')], prefs, []).map((b) => b.key)).toEqual(['Quick picks']);
	});

	it('keeps its place while nothing is shared', () => {
		const prefs = { ...loadPrefs(null), order: ['Quick picks', FRIENDS] };
		expect(withPins(prefs.order, prefs)).toEqual(['Quick picks', FRIENDS]);
		expect(arrange([section('Quick picks')], prefs, [share('s1')]).map((b) => b.key)).toEqual(['Quick picks', FRIENDS]);
	});
});

describe('speed dial', () => {
	it('is how Pinned and YTM’s own Speed dial show, unless set otherwise', () => {
		const pins: Pin[] = [{ kind: 'album', id: 'a', title: 'A' }];
		const shown = arrange([section('Speed dial'), section('Quick picks')], { ...loadPrefs(null), pins, layouts: { 'Quick picks': 'dial' } });
		expect(shown.map((b) => [b.key, b.layout])).toEqual([
			[PINNED, 'dial'],
			['Speed dial', 'dial'],
			['Quick picks', 'dial']
		]);
		expect(loadPrefs('{"layouts":{"A":"list","B":"wobbly"}}').layouts).toEqual({ A: 'list' });
	});
});

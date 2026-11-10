import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlaylistPage, SearchItem, Song } from './protocol.gen';

const song = (id: string, title = id, artist = 'Someone'): Song => ({ id, title, artists: [{ id: null, name: artist }], album: null, durationMs: 0, thumbnails: [], explicit: false, podcast: null });
const part = (songs: Song[], more: string | null): PlaylistPage => ({ id: 'LM', title: 'Liked music', author: null, description: null, thumbnails: [], songs, owned: false, itemIds: [], more, songCount: null }) as PlaylistPage;
const playlist = (id: string, title: string): SearchItem => ({ kind: 'playlist', id, title, author: null, itemCount: 3, thumbnails: [] }) as SearchItem;

const api = vi.hoisted(() => ({
	getPlaylist: vi.fn(),
	getPlaylistPart: vi.fn(),
	getLibrary: vi.fn(),
	lastSeen: vi.fn(() => null),
	youtubeSource: () => null
}));
const store = vi.hoisted(() => ({ kept: null as unknown }));

vi.mock('./api', () => api);
vi.mock('./keptLiked', () => ({ readLiked: async () => store.kept, writeLiked: async (_: string, kept: unknown) => void (store.kept = kept), forgetLiked: async () => {} }));
vi.mock('./settings.svelte', () => ({ settings: { homeRefresh: { value: 360 } } }));
vi.mock('./youtube.svelte', () => ({ youtube: { account: { name: 'Me', handle: null } } }));

const { byArtist, due, librarySearch, MAX_LIKED, withNewest } = await import('./librarySearch.svelte');
const { youtube } = await import('./youtube.svelte');

const ALL = { songs: 10, items: 10, loose: false };
const settle = async () => {
	for (let i = 0; i < 50; i++) await Promise.resolve();
};

describe('what to read of Liked music', () => {
	const HOUR = 3_600_000;
	it('reads everything the first time, nothing while recent, the newest part once old', () => {
		expect(due(null, false, 0, 6 * HOUR)).toBe('all');
		expect(due({ at: 0, fullAt: 0, songs: [] }, false, HOUR, 6 * HOUR)).toBe(null);
		expect(due({ at: 0, fullAt: 0, songs: [] }, false, 7 * HOUR, 6 * HOUR)).toBe('newest');
	});

	it('reads everything again after a week, and with only by hand only when asked', () => {
		expect(due({ at: 7 * 24 * HOUR, fullAt: 0, songs: [] }, false, 8 * 24 * HOUR, 6 * HOUR)).toBe('all');
		expect(due({ at: 0, fullAt: 0, songs: [] }, false, 100 * 24 * HOUR, 0)).toBe(null);
		expect(due({ at: 0, fullAt: 0, songs: [] }, true, 100 * 24 * HOUR, 0)).toBe('newest');
	});

	it('puts the newest on top, each song once', () => {
		expect(withNewest([song('new'), song('a')], [song('a'), song('b')]).map((s) => s.id)).toEqual(['new', 'a', 'b']);
	});
});

describe('searching your library', () => {
	beforeEach(() => {
		vi.useFakeTimers();
		vi.clearAllMocks();
		store.kept = null;
		// Each test's library is someone else's: a new account starts over.
		youtube.account = { name: `Me${Math.random()}`, handle: null, photoUrl: null };
		api.getLibrary.mockResolvedValue({ sections: [{ title: 'Playlists', items: [playlist('LM', 'Liked music'), playlist('PL1', 'Road trip')] }] });
	});

	it('finds liked songs by title or artist, whatever the capitals and accents', async () => {
		api.getPlaylist.mockResolvedValue(part([song('1', 'Déjà Vu', 'Beyoncé'), song('2', 'Yellow', 'Coldplay')], null));
		librarySearch.ensure();
		await settle();
		expect(librarySearch.find('deja', ALL).songs.map((s) => s.id)).toEqual(['1']);
		expect(librarySearch.find('BEYONCE', ALL).songs.map((s) => s.id)).toEqual(['1']);
		expect(librarySearch.find('yellow coldplay', ALL).songs.map((s) => s.id)).toEqual(['2']);
		expect(librarySearch.find('nothing like this', ALL).songs).toEqual([]);
	});

	it('finds your playlists, and nothing is found signed out', async () => {
		api.getPlaylist.mockResolvedValue(part([], null));
		librarySearch.ensure();
		await settle();
		expect(librarySearch.find('road', ALL).sections.map((s) => s.title)).toEqual(['Playlists']);
		youtube.account = null;
		expect(librarySearch.find('road', ALL)).toEqual({ songs: [], sections: [] });
	});

	it('reads Liked music one part at a time, with a pause, and keeps it', async () => {
		api.getPlaylist.mockResolvedValue(part([song('a')], 'NEXT'));
		api.getPlaylistPart.mockResolvedValue(part([song('b')], null));
		librarySearch.ensure();
		await settle();
		expect(api.getPlaylistPart).not.toHaveBeenCalled();
		expect(librarySearch.songs.map((s) => s.id)).toEqual(['a']);
		await vi.advanceTimersByTimeAsync(1000);
		await settle();
		expect(api.getPlaylistPart).toHaveBeenCalledOnce();
		expect(librarySearch.songs.map((s) => s.id)).toEqual(['a', 'b']);
		expect((store.kept as { songs: Song[] }).songs.map((s) => s.id)).toEqual(['a', 'b']);
	});

	it('makes no requests for what was read recently', async () => {
		store.kept = { at: Date.now(), fullAt: Date.now(), songs: [song('kept', 'Kept song')] };
		librarySearch.ensure();
		await settle();
		expect(api.getPlaylist).not.toHaveBeenCalled();
		expect(librarySearch.find('kept', ALL).songs).toHaveLength(1);
	});

	it('looks only at the newest part once the list is old', async () => {
		store.kept = { at: 0, fullAt: Date.now(), songs: [song('old')] };
		api.getPlaylist.mockResolvedValue(part([song('new'), song('old')], 'NEXT'));
		librarySearch.ensure();
		await settle();
		expect(api.getPlaylistPart).not.toHaveBeenCalled();
		expect(librarySearch.songs.map((s) => s.id)).toEqual(['new', 'old']);
	});

	it('stops at the limit', async () => {
		api.getPlaylist.mockResolvedValue(part(Array.from({ length: MAX_LIKED + 5 }, (_, i) => song(`s${i}`)), 'NEXT'));
		librarySearch.ensure();
		await settle();
		expect(api.getPlaylistPart).not.toHaveBeenCalled();
		expect(librarySearch.songs).toHaveLength(MAX_LIKED);
	});

	it('keeps what it has when a part fails, and an unliked song leaves at once', async () => {
		api.getPlaylist.mockResolvedValue(part([song('a'), song('b')], 'NEXT'));
		api.getPlaylistPart.mockRejectedValue(new Error('blocked'));
		librarySearch.ensure();
		await settle();
		await vi.advanceTimersByTimeAsync(1000);
		await settle();
		expect(librarySearch.songs.map((s) => s.id)).toEqual(['a', 'b']);
		expect(librarySearch.ready).toBe(true);
		librarySearch.likeChanged('a', false);
		expect(librarySearch.songs.map((s) => s.id)).toEqual(['b']);
	});

	it('suggests names, each once', async () => {
		api.getPlaylist.mockResolvedValue(part([song('1', 'Road home'), song('2', 'road home')], null));
		librarySearch.ensure();
		await settle();
		expect(librarySearch.suggest('road', 5)).toEqual(['Road trip', 'Road home']);
	});
});

describe("an artist's songs in your library", () => {
	const by = (id: string, title: string, artists: { id: string | null; name: string }[]): Song => ({ ...song(id, title), artists });
	const DAFT = { id: 'UCdaft', name: 'Daft Punk' };
	const podcast = (id: string, author: string | null): SearchItem => ({ kind: 'podcast', id, title: id, author, thumbnails: [] });
	const owned = (id: string, author: string | null): SearchItem => ({ kind: 'playlist', id, title: id, author, itemCount: null, thumbnails: [] });

	it('finds songs by the artist ID, also as one of several artists, in the order of Liked music', () => {
		const songs = [
			by('1', 'One More Time', [{ id: 'UCdaft', name: 'Daft Punk' }]),
			by('2', 'Yellow', [{ id: 'UCcold', name: 'Coldplay' }]),
			by('3', 'Get Lucky', [{ id: 'UCdaft', name: 'Daft Punk' }, { id: 'UCpw', name: 'Pharrell Williams' }]),
			by('4', 'Starboy', [{ id: 'UCweeknd', name: 'The Weeknd' }, { id: 'MPLAUCdaft', name: 'Daft Punk' }])
		];
		expect(byArtist(songs, [], DAFT).songs.map((s) => s.id)).toEqual(['1', '3', '4']);
	});

	it('does not take another artist with the same or a similar name when songs have IDs', () => {
		const songs = [by('1', 'A', [{ id: 'UCother', name: 'Daft Punk' }]), by('2', 'B', [{ id: 'UCtribute', name: 'Daft Punk Tribute' }])];
		expect(byArtist(songs, [], DAFT).songs).toEqual([]);
	});

	it('falls back to the whole name, ignoring case, accents and punctuation, when a song has no artist ID', () => {
		const songs = [
			by('1', 'Halo', [{ id: null, name: 'BEYONCE' }]),
			by('2', 'Crazy in Love', [{ id: null, name: 'Beyoncé feat. Jay-Z' }]),
			by('3', 'Tribute', [{ id: null, name: 'Beyoncé Tribute Band' }]),
			by('4', 'Shy', [{ id: null, name: 'Beyonc' }])
		];
		expect(byArtist(songs, [], { id: 'UCb', name: 'Beyoncé' }).songs.map((s) => s.id)).toEqual(['1', '2']);
		expect(byArtist([by('5', 'X', [{ id: null, name: 'Simon and Garfunkel' }])], [], { id: 'UCsg', name: 'Simon & Garfunkel' }).songs).toHaveLength(1);
	});

	it("finds the artist's podcasts and playlists in your library by name, not Liked music", () => {
		const sections = [
			{ items: [owned('LM', 'Daft Punk'), owned('PL1', 'Daft Punk'), owned('PL2', 'Someone else')] },
			{ items: [podcast('P1', 'daft punk'), podcast('P2', 'Daft Punkish'), podcast('P3', null)] }
		];
		expect(byArtist([], sections, DAFT).items.map((i) => ('id' in i ? i.id : ''))).toEqual(['PL1', 'P1']);
	});

	it('shows nothing without a YouTube Music sign-in', () => {
		const account = youtube.account;
		youtube.account = null;
		expect(librarySearch.ofArtist(DAFT)).toEqual({ songs: [], items: [] });
		youtube.account = account;
	});
});

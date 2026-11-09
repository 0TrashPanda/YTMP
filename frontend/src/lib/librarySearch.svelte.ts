// Finding things in your own library: the "From your library" section of search, and the search
// bar on the Library tab (searchOrder.svelte.ts, SearchResults.svelte, SearchBox.svelte).
//
// It only looks at what the app already has: your library (playlists and podcasts) and Liked
// music, read once and kept in this browser (keptLiked.ts). Nothing happens until you search,
// and matching is local, so a search never waits for this and each search costs no extra
// request. Liked music is read in parts with a pause between them, and afterwards only its newest
// part is looked at when the list is older than Settings → Home's "New suggestions after":
// many YouTube requests in a short time can get the home network blocked.
import { untrack } from 'svelte';
import { getLibrary, getPlaylist, getPlaylistPart, lastSeen, youtubeSource } from './api';
import { fuzzyIndex } from './format';
import { readLiked, writeLiked, type KeptLiked } from './keptLiked';
import type { HomePage, SearchItem, Song } from './protocol.gen';
import { settings } from './settings.svelte';
import { youtube } from './youtube.svelte';

/** The pause between two parts of Liked music, in ms. */
export const PART_PAUSE = 1000;
/** At most this many liked songs are read. */
export const MAX_LIKED = 5000;
/** The whole list is read again this often, for songs you unliked somewhere else. */
const READ_ALL_EVERY = 7 * 24 * 60 * 60_000;

/** [newest] on top of [kept], each song once. */
export function withNewest(newest: Song[], kept: Song[]): Song[] {
	const seen = new Set(newest.map((s) => s.id));
	return [...newest, ...kept.filter((s) => !seen.has(s.id))];
}

/** What's worth reading now: nothing (null), only the newest songs, or all of them. */
export function due(kept: KeptLiked | null, forced: boolean, now: number, wait: number): 'newest' | 'all' | null {
	if (!kept) return 'all';
	const old = wait !== 0 && now - kept.at >= wait;
	if (old && now - kept.fullAt >= READ_ALL_EVERY) return 'all';
	return forced || old ? 'newest' : null;
}

export interface LibraryMatches {
	songs: Song[];
	/** Your library's own sections, with what matched (playlists, podcasts). */
	sections: { title: string; items: SearchItem[] }[];
}

/** How much of each to find: [loose] also finds letters in order ("rt" finds "Road trip"), for when you search only your library. */
export interface FindOptions {
	songs: number;
	items: number;
	loose: boolean;
}

const NOTHING: LibraryMatches = { songs: [], sections: [] };
const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const songText = (song: Song) => `${song.title} ${song.artists.map((a) => a.name).join(' ')}`;

function itemText(item: SearchItem): string {
	switch (item.kind) {
		case 'playlist':
		case 'podcast':
			return `${item.title} ${item.author ?? ''}`;
		case 'album':
			return `${item.album.title} ${item.artists.map((a) => a.name).join(' ')}`;
		case 'artist':
			return item.name;
		case 'song':
			return songText(item.song);
	}
}

function itemName(item: SearchItem): string {
	switch (item.kind) {
		case 'album':
			return item.album.title;
		case 'artist':
			return item.name;
		case 'song':
			return item.song.title;
		default:
			return item.title;
	}
}

class LibrarySearch {
	songs = $state.raw<Song[]>([]);
	library = $state.raw<HomePage | null>(null);
	/** Something can be looked in (the first read is done, or nothing could be read). */
	ready = $state(false);
	/** Still reading Liked music, part by part. */
	reading = $state(false);

	/** Whose library this is (see [ensure]). */
	#owner: string | null = null;
	#kept: KeptLiked | null = null;
	#busy: Promise<void> | null = null;
	/** Look at the newest songs next time, however recently they were read. */
	#stale = false;

	#indexes = new Map<boolean, { from: Song[]; find: (query: string) => Song[] }>();

	#clear(owner: string | null): void {
		this.#owner = owner;
		this.#kept = null;
		this.#busy = null;
		this.#stale = false;
		this.#indexes.clear();
		this.songs = [];
		this.library = null;
		this.ready = false;
		this.reading = false;
	}

	/**
	 * Makes sure your library is there or on its way. Call it when searching: from an effect
	 * it runs again once you turn out to be signed in.
	 */
	ensure(): void {
		const account = youtube.account;
		const owner = account ? `${youtubeSource() ?? ''}|${account.name}|${account.handle ?? ''}` : null;
		untrack(() => {
			if (owner !== this.#owner) this.#clear(owner);
			if (!owner) return;
			this.library ??= lastSeen('me:library');
			getLibrary()
				.then((page) => {
					if (owner === this.#owner) this.library = page;
				})
				.catch(() => {}); // what was kept stays
			this.#busy ??= this.#readLiked(owner).finally(() => {
				if (owner !== this.#owner) return;
				this.#busy = null;
				this.ready = true;
				this.reading = false;
			});
		});
	}

	async #readLiked(owner: string): Promise<void> {
		if (!this.#kept) {
			this.#kept = await readLiked(owner);
			if (owner !== this.#owner) return;
			if (this.#kept) {
				this.songs = this.#kept.songs;
				this.ready = true;
			}
		}
		const kept = this.#kept;
		const reading = due(kept, this.#stale, Date.now(), settings.homeRefresh.value * 60_000);
		this.#stale = false;
		if (!reading) return;
		this.reading = !kept;
		let songs: Song[] = [];
		let more: string | null = null;
		const known = reading === 'newest' ? new Set(kept!.songs.map((s) => s.id)) : null;
		const reachedKnown = () => !!known && songs.some((s) => known.has(s.id));
		try {
			const first = await getPlaylist('LM', true);
			if (owner !== this.#owner) return;
			songs = first.songs;
			more = first.more ?? null;
			this.songs = withNewest(songs, kept?.songs ?? []);
			this.ready = true;
			while (more && songs.length < MAX_LIKED && !reachedKnown()) {
				await sleep(PART_PAUSE);
				const part = await getPlaylistPart('LM', more, true);
				if (owner !== this.#owner) return;
				songs = [...songs, ...part.songs];
				more = part.more ?? null;
				this.songs = withNewest(songs, kept?.songs ?? []);
			}
		} catch {
			// Not now: what was read so far stays, and it's tried again when it's due.
			more ??= '';
		}
		if (owner !== this.#owner) return;
		const now = Date.now();
		const toEnd = more === null || songs.length >= MAX_LIKED;
		const newest = toEnd ? songs : withNewest(songs, kept?.songs ?? []);
		this.#kept = { at: now, fullAt: toEnd ? now : (kept?.fullAt ?? 0), songs: newest.slice(0, MAX_LIKED) };
		this.songs = this.#kept.songs;
		await writeLiked(owner, this.#kept);
	}

	/** The newest liked songs are looked at again next time (you pulled the Library down). */
	refresh(): void {
		this.#stale = true;
	}

	/** You changed a like in the app: unliked songs go right away, new ones come with the next search. */
	likeChanged(songId: string, liked: boolean): void {
		this.#stale = true;
		if (liked || !this.#kept) return;
		this.#kept = { ...this.#kept, songs: this.#kept.songs.filter((s) => s.id !== songId) };
		this.songs = this.#kept.songs;
		if (this.#owner) void writeLiked(this.#owner, this.#kept);
	}

	#findSongs(query: string, loose: boolean): Song[] {
		let index = this.#indexes.get(loose);
		if (index?.from !== this.songs) {
			index = { from: this.songs, find: fuzzyIndex(this.songs, songText, loose) };
			this.#indexes.set(loose, index);
		}
		return index.find(query);
	}

	/** What matches [query]; nothing without a YouTube Music sign-in. Reactive. */
	find(query: string, { songs, items, loose }: FindOptions): LibraryMatches {
		if (!youtube.account || !query.trim()) return NOTHING;
		return {
			songs: this.#findSongs(query, loose).slice(0, songs),
			sections: (this.library?.sections ?? [])
				.map((section) => ({ title: section.title, items: fuzzyIndex(section.items, itemText, loose)(query).slice(0, items) }))
				.filter((section) => section.items.length)
		};
	}

	/** Names to suggest while typing, best first, each once. */
	suggest(query: string, count: number): string[] {
		const { songs, sections } = this.find(query, { songs: count, items: count, loose: false });
		const names = [...sections.flatMap((s) => s.items.map(itemName)), ...songs.map((s) => s.title)];
		const seen = new Set<string>();
		return names.filter((name) => !seen.has(name.toLowerCase()) && !!seen.add(name.toLowerCase())).slice(0, count);
	}
}

export const librarySearch = new LibrarySearch();

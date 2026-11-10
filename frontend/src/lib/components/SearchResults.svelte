<script lang="ts">
	// Search results with YTM's chips (all, songs, videos, albums, …), or "Find similar" for a
	// song. Songs: tap = what Tap does in song lists, ⋮ or right-click = song menu. Albums, artists and
	// playlists open their page. All also has a section with what matches in your own library
	// (librarySearch.svelte.ts), the sections in the order of Settings → Search
	// (sectionOrder.svelte.ts); the Library chip shows only your library, without asking YouTube Music.
	import { smallUrl, thumbUrl } from '../images.svelte';
	import Picture from './Picture.svelte';
	import { getSong, search, similar } from '../api';
	import { parseLink } from '../links';
	import { menuAt, tapSong } from '../songActions';
	import { itemCard } from '../cards';
	import { artistNames } from '../format';
	import { librarySearch } from '../librarySearch.svelte';
	import { LIBRARY_SECTION, searchOrder, TOP_SECTION } from '../sectionOrder.svelte';
	import { settings } from '../settings.svelte';
	import { youtube } from '../youtube.svelte';
	import type { RadioFrom, AlbumRef, ArtistRef, PodcastRef, SearchItem, SearchPage, SearchType, Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import CardGrid from './CardGrid.svelte';
	import SongList from './SongList.svelte';
	import Icon from './Icon.svelte';
	import Skeleton from './Skeleton.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let {
		room,
		query,
		type = 'all',
		library = false,
		onType = () => {},
		onLibrary = () => {},
		onReorder,
		similarTo = null,
		similarFrom = 'mix',
		onToast,
		onMenu,
		onArtist = () => {},
		onAlbum = () => {},
		onPlaylist = () => {},
		onPodcast = () => {}
	}: {
		room: RoomConnection;
		query: string;
		type?: SearchType;
		/** A chip (or a section's "More") was picked. */
		onType?: (type: SearchType) => void;
		/** Only your library (the Library chip). */
		library?: boolean;
		/** The Library chip (or the library section's "More") was picked. */
		onLibrary?: () => void;
		/** Opens the setting for the order of the sections. */
		onReorder?: () => void;
		/** Show songs similar to this one instead of searching. */
		similarTo?: Song | null;
		/** Find similar from both YouTube Music and the song graph, or only one of them. */
		similarFrom?: RadioFrom;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
		onArtist?: (artist: ArtistRef) => void;
		onAlbum?: (album: AlbumRef) => void;
		onPlaylist?: (playlist: { id: string; title: string }, personal?: boolean) => void;
		onPodcast?: (podcast: PodcastRef) => void;
	} = $props();

	const CHIPS: [SearchType | typeof LIBRARY_SECTION, string][] = [
		['all', 'All'],
		[LIBRARY_SECTION, 'Library'],
		['songs', 'Songs'],
		['videos', 'Videos'],
		['albums', 'Albums'],
		['artists', 'Artists'],
		['community_playlists', 'Community playlists'],
		['featured_playlists', 'Featured playlists'],
		['podcasts', 'Podcasts'],
		['episodes', 'Episodes']
	];

	let page = $state<SearchPage>({ sections: [] });
	/** A pasted song link: that song is the result. */
	const link = $derived(similarTo ? null : parseLink(query));
	let loading = $state(false);
	let error = $state<string | null>(null);

	/** Only your library: nothing is asked of YouTube Music. */
	const libraryOnly = $derived(library && !!youtube.account && !similarTo && !link);
	/** Also what matches in your library: in All. */
	const withLibrary = $derived(!similarTo && !link && (libraryOnly || type === 'all'));
	const mine = $derived(
		withLibrary ? librarySearch.find(query, libraryOnly ? { songs: 100, items: 30, loose: true } : { songs: 4, items: 6, loose: false }) : { songs: [], sections: [] }
	);

	$effect(() => {
		if (withLibrary && query.trim()) librarySearch.ensure();
	});

	// Cancel searches that are no longer needed.
	$effect(() => {
		const q = query.trim();
		const seed = similarTo;
		const t = type;
		const songLink = link?.kind === 'song' ? link : null;
		if ((!q && !seed) || libraryOnly) {
			page = { sections: [] };
			loading = false;
			error = null;
			return;
		}
		const controller = new AbortController();
		const timer = setTimeout(async () => {
				loading = true;
				error = null;
				try {
					page = songLink
						? { sections: [{ title: 'From the link', type: null, items: [{ kind: 'song', song: await getSong(songLink.id, controller.signal), video: false }] }] }
						: seed
						? {
								sections: [
									{ title: `Similar to ${seed.title}`, type: null, items: (await similar(seed.id, controller.signal, similarFrom)).map((song) => ({ kind: 'song', song, video: false })) }
								]
							}
						: await search(q, t, controller.signal);
				} catch (e) {
					if (!controller.signal.aborted) error = e instanceof Error ? e.message : 'Search failed';
				} finally {
					if (!controller.signal.aborted) loading = false;
				}
		}, 0);
		return () => {
			clearTimeout(timer);
			controller.abort();
		};
	});

	const pageEmpty = $derived(page.sections.every((s) => s.items.length === 0));

	/** A section as shown: YouTube Music's, or one with what matches in your library. */
	type Shown = { key: string; title: string; type: SearchType | null; items: SearchItem[]; big: boolean; mine: boolean };

	const asItems = (found: Song[]): SearchItem[] => found.map((song) => ({ kind: 'song', song, video: false }));

	const shown = $derived.by((): Shown[] => {
		if (libraryOnly) {
			return [
				{ key: 'liked', title: 'Liked music', type: null, items: asItems(mine.songs), big: false, mine: true },
				...mine.sections.map((s) => ({ key: s.title, title: s.title, type: null, items: s.items, big: false, mine: true }))
			];
		}
		const theirs = page.sections.map((s) => ({ key: s.type ?? TOP_SECTION, title: s.title, type: s.type, items: s.items, big: s.type === null && !similarTo, mine: false }));
		if (!withLibrary) return theirs;
		const items = [...asItems(mine.songs), ...mine.sections.flatMap((s) => s.items)];
		// Waiting for the first results it stays out, so they don't push it around when they come.
		const section = items.length && !(loading && pageEmpty) ? [{ key: LIBRARY_SECTION, title: 'From your library', type: null, items, big: false, mine: true }] : [];
		return searchOrder.arrange([...theirs, ...section], (s) => s.key);
	});

	const empty = $derived(shown.every((s) => s.items.length === 0));
	/** Your library is still being looked in, the first time. */
	const libraryBusy = $derived(libraryOnly && !librarySearch.ready);

	function songs(items: SearchItem[]): Song[] {
		return items.flatMap((i) => (i.kind === 'song' ? [i.song] : []));
	}

	const card = (item: SearchItem, yours = false) => itemCard(item, { onArtist, onAlbum, onPlaylist: (p) => onPlaylist(p, yours), onPodcast });

	const cards = (items: SearchItem[], yours: boolean) => items.flatMap((i) => card(i, yours) ?? []);

	/** The top result, as one big card: a song does what Tap does in song lists, anything else opens. */
	function top(item: SearchItem): { title: string; subtitle: string; art: string | undefined; small?: string; round: boolean; open: () => void; prefetch?: () => void } {
		if (item.kind === 'song') {
			const song = item.song;
			return {
				title: song.title,
				subtitle: `${song.podcast ? 'Episode' : item.video ? 'Video' : 'Song'} • ${artistNames(song)}`,
				art: thumbUrl(song.thumbnails, 400),
				small: smallUrl(song.thumbnails, 400),
				round: false,
				open: () => tapSong(room, song, onToast)
			};
		}
		const c = card(item)!;
		const kind = item.kind === 'album' ? item.album.kind : item.kind === 'artist' ? 'Artist' : item.kind === 'podcast' ? 'Podcast' : 'Playlist';
		return { ...c, round: !!c.round, subtitle: item.kind === 'artist' ? kind : `${kind} • ${c.subtitle}` };
	}
</script>

<section class="flex flex-col gap-4">
	{#if !similarTo && !link}
		<div class="-mx-1 flex gap-2 overflow-x-auto px-3 pb-1 [scrollbar-width:none]" role="toolbar" aria-label="What to search for">
			{#each CHIPS.filter(([value]) => value !== LIBRARY_SECTION || youtube.account) as [value, label] (value)}
				{@const picked = libraryOnly ? value === LIBRARY_SECTION : type === value}
				<button
					class="shrink-0 rounded-lg px-3 py-1.5 text-sm whitespace-nowrap {picked ? 'bg-white text-black' : 'bg-raised hover:bg-line'}"
					aria-pressed={picked}
					onclick={() => (value === LIBRARY_SECTION ? onLibrary() : onType(value as SearchType))}>{label}</button
				>
			{/each}
		</div>
	{/if}

	{#if error}
		<p class="px-2 text-muted">{error}</p>
	{:else if (loading && pageEmpty) || libraryBusy}
		<Skeleton />
	{:else if empty && libraryOnly}
		<p class="px-2 text-muted">{librarySearch.reading ? 'Still reading your liked songs…' : `Nothing in your library matches “${query.trim()}”.`}</p>
	{:else if empty && (query.trim() || similarTo)}
		<p class="px-2 text-muted">No results.</p>
	{/if}

	{#each shown as section, i (section.key + i)}
		{#if section.items.length}
			<!-- Dimmed while the next results (another chip, more letters) load. -->
			<div class="flex flex-col gap-1 transition-opacity {loading ? 'opacity-50' : ''}">
				<div class="flex items-center justify-between gap-2 px-2 pb-1">
					<h2 class="truncate text-xl font-bold">{section.title}</h2>
					{#if type === 'all' && !libraryOnly && (section.type || section.key === LIBRARY_SECTION) && !similarTo}
						<button class="shrink-0 rounded-full px-3 py-1 text-sm text-muted hover:bg-raised hover:text-white" onclick={() => (section.type ? onType(section.type) : onLibrary())}>More</button>
					{/if}
				</div>
				{#if section.big}
					{@const t = top(section.items[0])}
					{@const first = section.items[0]}
					<!-- A song on top has the song menu too: ⋮, a right-click or holding it. -->
					<div class="relative mx-2">
						<button
							class="group flex w-full items-center gap-4 rounded-lg bg-raised p-4 text-left hover:bg-line {first.kind === 'song' ? 'pr-12' : ''}"
							onclick={t.open}
							onpointerenter={t.prefetch}
							oncontextmenu={(e) => {
								if (first.kind !== 'song') return;
								e.preventDefault();
								onMenu({ song: first.song, place: 'search', x: e.clientX, y: e.clientY });
							}}
						>
							<div class="h-20 w-20 shrink-0 overflow-hidden bg-line sm:h-24 sm:w-24 {t.round ? 'rounded-full' : 'rounded-md'}">
								{#if t.art}<Picture src={t.art} small={t.small} lazy={false} class="h-full w-full object-cover" />{/if}
							</div>
							<div class="min-w-0">
								<p class="truncate text-xl font-bold sm:text-2xl">{t.title}</p>
								<p class="truncate text-sm text-muted">{t.subtitle}</p>
							</div>
						</button>
						{#if first.kind === 'song'}
							<button
								class="absolute top-1/2 right-2 -translate-y-1/2 rounded-full p-2 text-muted hover:bg-surface hover:text-white"
								aria-label="More for {first.song.title}"
								onclick={(e) => onMenu({ song: first.song, place: 'search', ...menuAt(e.currentTarget) })}
							>
								<Icon name="more" />
							</button>
						{/if}
					</div>
				{:else}
					<SongList {room} songs={songs(section.items)} {onToast} {onMenu} />
					{#if cards(section.items, section.mine).length}
						<CardGrid cards={cards(section.items, section.mine)} />
					{/if}
				{/if}
			</div>
		{/if}
	{/each}

	{#if link?.kind === 'song' && link.playlist && !empty}
		{@const list = link.playlist}
		<button class="mx-2 flex items-center gap-2 self-start rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={() => onPlaylist({ id: list, title: '' })}>
			<Icon name="library" size={18} /> Open the playlist it's in
		</button>
	{/if}

	{#if libraryOnly && query.trim()}
		{#if librarySearch.reading && !empty}
			<p class="px-2 text-sm text-muted">Still reading your liked songs ({librarySearch.songs.length} so far)…</p>
		{/if}
		<button class="mt-2 flex items-center gap-2 self-start rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={() => onType('all')}>
			<Icon name="search" size={18} /> Search everywhere for "{query.trim()}"
		</button>
	{:else if !similarTo && query.trim() && !link}
		{#if onReorder && type === 'all' && !empty}
			<button class="mt-2 flex items-center gap-2 self-start rounded-full px-3 py-2 text-sm text-muted hover:bg-raised hover:text-white" onclick={onReorder}>
				<Icon name="tune" size={18} /> Change the order of these sections
			</button>
		{/if}
		<a
			class="mt-2 flex items-center gap-2 self-start rounded-full px-3 py-2 text-sm text-muted hover:bg-raised hover:text-white"
			href="https://music.youtube.com/search?q={encodeURIComponent(query.trim())}"
			target="_blank"
			rel="noopener"
		>
			<Icon name="open" size={18} /> Search "{query.trim()}" on YouTube Music
		</a>
	{/if}
</section>

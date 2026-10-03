<script lang="ts">
	// Search results with YTM's chips (all, songs, videos, albums, …), or "Find similar" for a
	// song. Songs: tap = play next, ⋮ or right-click = song menu. Albums, artists and
	// playlists open their page.
	import { prefetch, search, similar } from '../api';
	import { artistNames } from '../format';
	import type { AlbumRef, ArtistRef, SearchItem, SearchPage, SearchType, Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import CardGrid, { type Card } from './CardGrid.svelte';
	import SongList from './SongList.svelte';
	import Icon from './Icon.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let {
		room,
		query,
		type = 'all',
		onType = () => {},
		similarTo = null,
		onToast,
		onMenu,
		onArtist = () => {},
		onAlbum = () => {},
		onPlaylist = () => {}
	}: {
		room: RoomConnection;
		query: string;
		type?: SearchType;
		/** A chip (or a section's "More") was picked. */
		onType?: (type: SearchType) => void;
		/** Show songs similar to this one instead of searching. */
		similarTo?: Song | null;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
		onArtist?: (artist: ArtistRef) => void;
		onAlbum?: (album: AlbumRef) => void;
		onPlaylist?: (playlist: { id: string; title: string }) => void;
	} = $props();

	const CHIPS: [SearchType, string][] = [
		['all', 'All'],
		['songs', 'Songs'],
		['videos', 'Videos'],
		['albums', 'Albums'],
		['artists', 'Artists'],
		['community_playlists', 'Community playlists'],
		['featured_playlists', 'Featured playlists']
	];

	let page = $state<SearchPage>({ sections: [] });
	let loading = $state(false);
	let error = $state<string | null>(null);

	// Search shortly after typing stops; cancel searches that are no longer needed.
	$effect(() => {
		const q = query.trim();
		const seed = similarTo;
		const t = type;
		if (!q && !seed) {
			page = { sections: [] };
			return;
		}
		const controller = new AbortController();
		const timer = setTimeout(
			async () => {
				loading = true;
				error = null;
				try {
					page = seed
						? {
								sections: [
									{ title: `Similar to ${seed.title}`, type: null, items: (await similar(seed.id, controller.signal)).map((song) => ({ kind: 'song', song, video: false })) }
								]
							}
						: await search(q, t, controller.signal);
				} catch (e) {
					if (!controller.signal.aborted) error = e instanceof Error ? e.message : 'Search failed';
				} finally {
					if (!controller.signal.aborted) loading = false;
				}
			},
			seed ? 0 : 300
		);
		return () => {
			clearTimeout(timer);
			controller.abort();
		};
	});

	const empty = $derived(page.sections.every((s) => s.items.length === 0));

	function songs(items: SearchItem[]): Song[] {
		return items.flatMap((i) => (i.kind === 'song' ? [i.song] : []));
	}

	function card(item: SearchItem): Card | null {
		switch (item.kind) {
			case 'album':
				return {
					key: `album:${item.album.id}`,
					title: item.album.title,
					subtitle: [item.album.kind, item.artists.map((a) => a.name).join(', '), item.album.year].filter(Boolean).join(' • '),
					art: item.album.thumbnails.at(-1)?.url,
					open: () => onAlbum({ id: item.album.id, name: item.album.title }),
					prefetch: () => prefetch('album', item.album.id)
				};
			case 'artist':
				return {
					key: `artist:${item.id}`,
					title: item.name,
					subtitle: 'Artist',
					art: item.thumbnails.at(-1)?.url,
					round: true,
					open: () => onArtist({ id: item.id, name: item.name }),
					prefetch: () => prefetch('artist', item.id)
				};
			case 'playlist':
				return {
					key: `playlist:${item.id}`,
					title: item.title,
					subtitle: [item.author, item.itemCount != null ? `${item.itemCount} songs` : null].filter(Boolean).join(' • '),
					art: item.thumbnails.at(-1)?.url,
					open: () => onPlaylist({ id: item.id, title: item.title }),
					prefetch: () => prefetch('playlist', item.id)
				};
			case 'song':
				return null;
		}
	}

	const cards = (items: SearchItem[]) => items.flatMap((i) => card(i) ?? []);

	async function playNext(song: Song) {
		const e = await room.run({ kind: 'AddSongs', songs: [song], position: 'next' });
		onToast(e ? e.message : `Playing "${song.title}" next`);
	}

	/** The top result, as one big card: a song plays next, anything else opens. */
	function top(item: SearchItem): { title: string; subtitle: string; art: string | undefined; round: boolean; open: () => void; prefetch?: () => void } {
		if (item.kind === 'song') {
			const song = item.song;
			return {
				title: song.title,
				subtitle: `${item.video ? 'Video' : 'Song'} • ${artistNames(song)}`,
				art: song.thumbnails.at(-1)?.url,
				round: false,
				open: () => playNext(song)
			};
		}
		const c = card(item)!;
		const kind = item.kind === 'album' ? item.album.kind : item.kind === 'artist' ? 'Artist' : 'Playlist';
		return { ...c, round: !!c.round, subtitle: item.kind === 'artist' ? kind : `${kind} • ${c.subtitle}` };
	}
</script>

<section class="flex flex-col gap-4">
	{#if !similarTo}
		<div class="-mx-1 flex gap-2 overflow-x-auto px-3 pb-1" role="toolbar" aria-label="What to search for">
			{#each CHIPS as [value, label] (value)}
				<button
					class="shrink-0 rounded-lg px-3 py-1.5 text-sm whitespace-nowrap {type === value ? 'bg-white text-black' : 'bg-raised hover:bg-line'}"
					aria-pressed={type === value}
					onclick={() => onType(value)}>{label}</button
				>
			{/each}
		</div>
	{/if}

	{#if error}
		<p class="px-2 text-muted">{error}</p>
	{:else if loading && empty}
		<p class="px-2 text-muted">{similarTo ? 'Finding similar songs…' : 'Searching…'}</p>
	{:else if empty && (query.trim() || similarTo)}
		<p class="px-2 text-muted">No results.</p>
	{/if}

	{#each page.sections as section, i (section.title + i)}
		{#if section.items.length}
			<div class="flex flex-col gap-1">
				<div class="flex items-center justify-between gap-2 px-2 pb-1">
					<h2 class="truncate text-xl font-bold">{section.title}</h2>
					{#if type === 'all' && section.type && !similarTo}
						<button class="shrink-0 rounded-full px-3 py-1 text-sm text-muted hover:bg-raised hover:text-white" onclick={() => onType(section.type!)}>More</button>
					{/if}
				</div>
				{#if section.type === null && !similarTo}
					{@const t = top(section.items[0])}
					<button
						class="group mx-2 flex items-center gap-4 rounded-lg bg-raised p-4 text-left hover:bg-line"
						onclick={t.open}
						onpointerenter={t.prefetch}
						oncontextmenu={(e) => {
							const item = section.items[0];
							if (item.kind !== 'song') return;
							e.preventDefault();
							onMenu({ song: item.song, place: 'search', x: e.clientX, y: e.clientY });
						}}
					>
						<div class="h-20 w-20 shrink-0 overflow-hidden bg-line sm:h-24 sm:w-24 {t.round ? 'rounded-full' : 'rounded-md'}">
							{#if t.art}<img src={t.art} alt="" class="h-full w-full object-cover" />{/if}
						</div>
						<div class="min-w-0">
							<p class="truncate text-xl font-bold sm:text-2xl">{t.title}</p>
							<p class="truncate text-sm text-muted">{t.subtitle}</p>
						</div>
					</button>
				{:else}
					<SongList {room} songs={songs(section.items)} {onToast} {onMenu} />
					{#if cards(section.items).length}
						<CardGrid cards={cards(section.items)} />
					{/if}
				{/if}
			</div>
		{/if}
	{/each}

	{#if !similarTo && query.trim()}
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

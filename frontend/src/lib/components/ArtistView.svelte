<script lang="ts">
	// An artist page: top songs, albums and singles, and what of theirs is in your library
	// (librarySearch.svelte.ts), in the order of Settings → Artist pages (sectionOrder.svelte.ts).
	// The library section never holds up the page: it joins once your library is there.
	import { smallUrl, thumbUrl } from '../images.svelte';
	import Picture from './Picture.svelte';
	import { getArtist, topSongs } from '../api';
	import { whileBusy } from '../busy.svelte';
	import { addToQueue, playNext, startRadio } from '../songActions';
	import { itemCard } from '../cards';
	import { librarySearch } from '../librarySearch.svelte';
	import { artistOrder, LIBRARY_SECTION } from '../sectionOrder.svelte';
	import type { AlbumRef, AlbumSummary, ArtistPage, PodcastRef } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import AlbumGrid from './AlbumGrid.svelte';
	import CardGrid from './CardGrid.svelte';
	import Icon from './Icon.svelte';
	import Skeleton from './Skeleton.svelte';
	import PinButton from './PinButton.svelte';
	import type { MenuTarget } from './SongMenu.svelte';
	import SongList from './SongList.svelte';

	let {
		room,
		id,
		name,
		onToast,
		onMenu,
		onAlbum,
		onPlaylist,
		onPodcast,
		onReorder
	}: {
		room: RoomConnection;
		id: string;
		/** Shown while loading. */
		name: string;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
		onAlbum: (album: AlbumRef) => void;
		/** Opens all the artist's songs, as a playlist page (or [personal]: one of your own playlists). */
		onPlaylist: (playlist: { id: string; title: string }, personal?: boolean) => void;
		onPodcast: (podcast: PodcastRef) => void;
		/** Opens the setting for the order of the sections. */
		onReorder?: () => void;
	} = $props();

	let artist = $state<ArtistPage | null>(null);
	let error = $state<string | null>(null);
	let showAllSongs = $state(false);
	let showAllMine = $state(false);
	let showBio = $state(false);

	$effect(() => {
		const artistId = id;
		artist = null;
		error = null;
		showAllSongs = false;
		showAllMine = false;
		getArtist(artistId)
			.then((a) => artistId === id && (artist = a))
			.catch((e) => (error = e instanceof Error ? e.message : "Couldn't load the artist"));
	});

	// Your library, read once and kept (most often no request at all): from an effect, it is
	// looked at again once you turn out to be signed in.
	$effect(() => librarySearch.ensure());

	/** What of theirs is in your library. */
	const mine = $derived(artist ? librarySearch.ofArtist({ id, name: artist.name }) : { songs: [], items: [] });
	const mineCards = $derived(
		mine.items.flatMap(
			(item) => itemCard(item, { onArtist: () => {}, onAlbum, onPlaylist: (p) => onPlaylist(p, true), onPodcast }) ?? []
		)
	);

	const openAlbum = (album: AlbumSummary) => onAlbum({ id: album.id, name: album.title });

	/** The sections with something to show, in your order. */
	const sections = $derived.by(() => {
		if (!artist) return [];
		const shown = [
			...(mine.songs.length || mineCards.length ? [LIBRARY_SECTION] : []),
			...(artist.songs.length || artist.songsPlaylistId ? ['songs'] : []),
			...(artist.albums.length ? ['albums'] : []),
			...(artist.singles.length ? ['singles'] : [])
		];
		return artistOrder.arrange(shown, (key) => key);
	});

	async function radio() {
		const first = artist?.songs[0];
		if (!first) return;
		await startRadio(room, first, onToast, artist!.name);
	}

	async function addAll(position: 'next' | 'end') {
		const a = artist;
		if (!a?.songs.length) return;
		const songs = await whileBusy(`Adding songs by ${a.name}…`, topSongs(a));
		const name = `${songs.length} songs by ${a.name}`;
		await (position === 'next' ? playNext : addToQueue)(room, songs, name, onToast);
	}
</script>

<div class="flex flex-col gap-6">
	<header class="flex items-center gap-5 px-2">
		<div class="h-28 w-28 shrink-0 overflow-hidden rounded-full bg-raised sm:h-40 sm:w-40">
			{#if artist?.thumbnails.at(-1)}
				<Picture src={thumbUrl(artist.thumbnails, 400)!} small={smallUrl(artist.thumbnails, 400)} lazy={false} class="h-full w-full object-cover" />
			{/if}
		</div>
		<div class="min-w-0">
			<p class="text-xs font-medium tracking-wide text-muted uppercase">Artist</p>
			<h1 class="truncate text-3xl font-black sm:text-5xl">{artist?.name ?? name}</h1>
			<div class="mt-3 flex flex-wrap gap-2">
				{#if room.can('start_radio')}
					<button class="flex items-center gap-2 rounded-full bg-white px-4 py-2 text-sm font-medium text-black disabled:opacity-40" disabled={!artist} onclick={radio}>
						<Icon name="radio" size={18} /> Radio
					</button>
				{/if}
				{#if room.can('add_songs')}
					<button class="flex items-center gap-2 rounded-full bg-raised px-4 py-2 text-sm hover:bg-line disabled:opacity-40" disabled={!artist} onclick={() => addAll('end')}>
						<Icon name="playlistAdd" size={18} /> Add top songs
					</button>
				{/if}
				<PinButton pin={artist ? { kind: 'artist', id, title: artist.name, art: thumbUrl(artist.thumbnails, 120) } : null} {onToast} />
			</div>
		</div>
	</header>

	{#if error}
		<p class="px-2 text-accent">{error}</p>
	{:else if !artist}
		<Skeleton rows={5} />
	{:else}
		{#if artist.description}
			<button class="px-2 text-left text-sm text-muted {showBio ? '' : 'line-clamp-2'}" onclick={() => (showBio = !showBio)}>{artist.description}</button>
		{/if}
		{#each sections as key (key)}
			{#if key === LIBRARY_SECTION}
				<section class="flex flex-col gap-1" aria-label="From your library">
					<h2 class="px-2 pb-1 text-xl font-bold">From your library</h2>
					{#if mine.songs.length}
						<SongList {room} songs={showAllMine ? mine.songs : mine.songs.slice(0, 5)} page={{ artist: id }} {onToast} {onMenu} />
						{#if mine.songs.length > 5}
							<button class="self-start rounded-full px-3 py-1.5 text-sm text-muted hover:bg-raised hover:text-white" onclick={() => (showAllMine = !showAllMine)}>
								{showAllMine ? 'Show less' : `Show all ${mine.songs.length}`}
							</button>
						{/if}
					{/if}
					{#if mineCards.length}
						<div class={mine.songs.length ? 'pt-2' : ''}><CardGrid cards={mineCards} /></div>
					{/if}
				</section>
			{:else if key === 'songs'}
				<section class="flex flex-col gap-1">
					<h2 class="px-2 pb-1 text-xl font-bold">Songs</h2>
					<SongList {room} songs={showAllSongs ? artist.songs : artist.songs.slice(0, 5)} page={{ artist: id }} {onToast} {onMenu} />
					{#if artist.songsPlaylistId}
						{@const all = { id: artist.songsPlaylistId, title: `Songs by ${artist.name}` }}
						<!-- Like YTM: all their songs on a page of their own. -->
						<button class="self-start rounded-full border border-line px-4 py-1.5 text-sm font-medium hover:bg-raised" onclick={() => onPlaylist(all)}>Show all</button>
					{:else if artist.songs.length > 5}
						<button class="self-start rounded-full px-3 py-1.5 text-sm text-muted hover:bg-raised hover:text-white" onclick={() => (showAllSongs = !showAllSongs)}>
							{showAllSongs ? 'Show less' : `Show all ${artist.songs.length}`}
						</button>
					{/if}
				</section>
			{:else if key === 'albums'}
				<section class="flex flex-col gap-3">
					<h2 class="px-2 text-xl font-bold">Albums</h2>
					<AlbumGrid albums={artist.albums} onOpen={openAlbum} />
				</section>
			{:else if key === 'singles'}
				<section class="flex flex-col gap-3">
					<h2 class="px-2 text-xl font-bold">Singles & EPs</h2>
					<AlbumGrid albums={artist.singles} onOpen={openAlbum} />
				</section>
			{/if}
		{/each}
		{#if onReorder && sections.length > 1}
			<button class="flex items-center gap-2 self-start rounded-full px-3 py-2 text-sm text-muted hover:bg-raised hover:text-white" onclick={onReorder}>
				<Icon name="tune" size={18} /> Change the order of these sections
			</button>
		{/if}
	{/if}
</div>

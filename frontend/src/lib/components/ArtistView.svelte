<script lang="ts">
	// An artist page: top songs, albums and singles.
	import { getArtist } from '../api';
	import type { AlbumSummary, ArtistPage } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import AlbumGrid from './AlbumGrid.svelte';
	import Icon from './Icon.svelte';
	import type { MenuTarget } from './SongMenu.svelte';
	import SongList from './SongList.svelte';

	let {
		room,
		id,
		name,
		onToast,
		onMenu,
		onAlbum,
		onPlaylist
	}: {
		room: RoomConnection;
		id: string;
		/** Shown while loading. */
		name: string;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
		onAlbum: (album: AlbumSummary) => void;
		/** Opens all the artist's songs, as a playlist page. */
		onPlaylist: (playlist: { id: string; title: string }) => void;
	} = $props();

	let artist = $state<ArtistPage | null>(null);
	let error = $state<string | null>(null);
	let showAllSongs = $state(false);
	let showBio = $state(false);

	$effect(() => {
		const artistId = id;
		artist = null;
		error = null;
		getArtist(artistId)
			.then((a) => artistId === id && (artist = a))
			.catch((e) => (error = e instanceof Error ? e.message : "Couldn't load the artist"));
	});

	async function radio() {
		const first = artist?.songs[0];
		if (!first) return;
		const e = await room.run({ kind: 'StartRadio', song: first });
		onToast(e ? e.message : `Starting a radio from ${artist!.name}`);
	}

	async function addAll(position: 'next' | 'end') {
		if (!artist?.songs.length) return;
		const e = await room.run({ kind: 'AddSongs', songs: artist.songs, position });
		onToast(e ? e.message : `Added ${artist.songs.length} songs by ${artist.name}`);
	}
</script>

<div class="flex flex-col gap-6">
	<header class="flex items-center gap-5 px-2">
		<div class="h-28 w-28 shrink-0 overflow-hidden rounded-full bg-raised sm:h-40 sm:w-40">
			{#if artist?.thumbnails.at(-1)}
				<img src={artist.thumbnails.at(-1)!.url} alt="" referrerpolicy="no-referrer" class="h-full w-full object-cover" />
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
			</div>
		</div>
	</header>

	{#if error}
		<p class="px-2 text-accent">{error}</p>
	{:else if !artist}
		<p class="px-2 text-muted">Loading…</p>
	{:else}
		{#if artist.description}
			<button class="px-2 text-left text-sm text-muted {showBio ? '' : 'line-clamp-2'}" onclick={() => (showBio = !showBio)}>{artist.description}</button>
		{/if}
		<section class="flex flex-col gap-1">
			<h2 class="px-2 pb-1 text-xl font-bold">Songs</h2>
			<SongList {room} songs={showAllSongs ? artist.songs : artist.songs.slice(0, 5)} {onToast} {onMenu} />
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
		{#if artist.albums.length}
			<section class="flex flex-col gap-3">
				<h2 class="px-2 text-xl font-bold">Albums</h2>
				<AlbumGrid albums={artist.albums} onOpen={onAlbum} />
			</section>
		{/if}
		{#if artist.singles.length}
			<section class="flex flex-col gap-3">
				<h2 class="px-2 text-xl font-bold">Singles & EPs</h2>
				<AlbumGrid albums={artist.singles} onOpen={onAlbum} />
			</section>
		{/if}
	{/if}
</div>

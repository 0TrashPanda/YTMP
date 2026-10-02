<script lang="ts">
	// An album page: its songs, in order.
	import { getAlbum } from '../api';
	import { formatTime } from '../format';
	import type { AlbumPage, ArtistRef } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Icon from './Icon.svelte';
	import type { MenuTarget } from './SongMenu.svelte';
	import SongList from './SongList.svelte';

	let {
		room,
		id,
		title,
		onToast,
		onMenu,
		onArtist
	}: {
		room: RoomConnection;
		id: string;
		/** Shown while loading. */
		title: string;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
		onArtist: (artist: ArtistRef) => void;
	} = $props();

	let album = $state<AlbumPage | null>(null);
	let error = $state<string | null>(null);

	$effect(() => {
		const albumId = id;
		album = null;
		error = null;
		getAlbum(albumId)
			.then((a) => albumId === id && (album = a))
			.catch((e) => (error = e instanceof Error ? e.message : "Couldn't load the album"));
	});

	const total = $derived(album?.songs.reduce((sum, s) => sum + s.durationMs, 0) ?? 0);

	async function addAll(position: 'next' | 'end') {
		if (!album?.songs.length) return;
		const e = await room.run({ kind: 'AddSongs', songs: album.songs, position });
		onToast(e ? e.message : position === 'next' ? `Playing ${album.title} next` : `Added ${album.title} to the queue`);
	}

	async function playNow() {
		if (!album?.songs.length) return;
		const [first, ...rest] = album.songs;
		let e = await room.run({ kind: 'PlayNow', song: first });
		if (!e && rest.length) e = await room.run({ kind: 'AddSongs', songs: rest, position: 'next' });
		if (e) onToast(e.message);
	}
</script>

<div class="flex flex-col gap-6">
	<header class="flex flex-col gap-5 px-2 sm:flex-row sm:items-end">
		<div class="aspect-square w-48 shrink-0 overflow-hidden rounded-lg bg-raised shadow-2xl sm:w-56">
			{#if album?.thumbnails.at(-1)}
				<img src={album.thumbnails.at(-1)!.url} alt="" class="h-full w-full object-cover" />
			{/if}
		</div>
		<div class="min-w-0">
			<p class="text-xs font-medium tracking-wide text-muted uppercase">{album?.kind ?? 'Album'}</p>
			<h1 class="text-3xl font-black break-words sm:text-4xl">{album?.title ?? title}</h1>
			{#if album}
				<p class="mt-1 text-muted">
					{#each album.artists as artist, i (i)}
						{#if i > 0}{', '}{/if}
						{#if artist.id}
							<button class="font-medium text-white hover:underline" onclick={() => onArtist(artist)}>{artist.name}</button>
						{:else}{artist.name}{/if}
					{/each}
					{#if album.year} • {album.year}{/if} • {album.songs.length} songs • {formatTime(total)}
				</p>
			{/if}
			<div class="mt-3 flex flex-wrap gap-2">
				{#if room.can('play_now')}
					<button class="flex items-center gap-2 rounded-full bg-white px-4 py-2 text-sm font-medium text-black disabled:opacity-40" disabled={!album} onclick={playNow}>
						<Icon name="play" size={18} /> Play
					</button>
				{/if}
				{#if room.can('add_songs')}
					<button class="flex items-center gap-2 rounded-full bg-raised px-4 py-2 text-sm hover:bg-line disabled:opacity-40" disabled={!album} onclick={() => addAll('next')}>
						<Icon name="playNext" size={18} /> Play next
					</button>
					<button class="flex items-center gap-2 rounded-full bg-raised px-4 py-2 text-sm hover:bg-line disabled:opacity-40" disabled={!album} onclick={() => addAll('end')}>
						<Icon name="playlistAdd" size={18} /> Add to queue
					</button>
				{/if}
			</div>
		</div>
	</header>

	{#if error}
		<p class="px-2 text-accent">{error}</p>
	{:else if !album}
		<p class="px-2 text-muted">Loading…</p>
	{:else}
		<section class="flex flex-col gap-1">
			<SongList {room} songs={album.songs} numbered showAlbum={false} {onToast} {onMenu} />
		</section>
	{/if}
</div>

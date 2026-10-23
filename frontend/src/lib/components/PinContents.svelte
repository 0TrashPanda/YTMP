<script lang="ts">
	// A pin shown on Home with its songs (Settings → Home): the first songs of the playlist or
	// album, the artist's top songs, or the podcast's newest episodes. More opens its page.
	import { getAlbum, getArtist, getPlaylist, getPodcast } from '../api';
	import type { Layout, Pin } from '../home.svelte';
	import type { Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import SongCarousel from './SongCarousel.svelte';
	import SongList from './SongList.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let {
		room,
		pin,
		layout,
		onToast,
		onMenu
	}: { room: RoomConnection; pin: Pin; layout: Layout; onToast: (text: string) => void; onMenu: (target: MenuTarget) => void } = $props();

	/** A carousel holds five pages of four; a list stays short (More shows the rest). */
	const SHOWN: Record<Layout, number> = { carousel: 20, list: 10 };

	let songs = $state<Song[] | null>(null);
	let error = $state(false);

	async function load(): Promise<Song[]> {
		switch (pin.kind) {
			case 'playlist':
				return (await getPlaylist(pin.id, pin.personal)).songs;
			case 'album':
				return (await getAlbum(pin.id)).songs;
			case 'artist':
				return (await getArtist(pin.id)).songs;
			case 'podcast':
				return (await getPodcast(pin.id)).episodes.map((e) => e.song);
		}
	}

	$effect(() => {
		void pin.id;
		load()
			.then((s) => (songs = s))
			.catch(() => (error = true));
	});
</script>

{#if error}
	<p class="px-2 text-sm text-muted">Couldn't load it.</p>
{:else if !songs}
	<div class="flex animate-pulse flex-col gap-2 px-2">
		{#each [0, 1, 2, 3] as i (i)}
			<div class="h-12 rounded-md bg-raised"></div>
		{/each}
	</div>
{:else if !songs.length}
	<p class="px-2 text-sm text-muted">Nothing in it yet.</p>
{:else if layout === 'list'}
	<SongList {room} songs={songs.slice(0, SHOWN.list)} {onToast} {onMenu} />
{:else}
	<SongCarousel {room} songs={songs.slice(0, SHOWN.carousel)} {onToast} {onMenu} />
{/if}

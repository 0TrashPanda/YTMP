<script lang="ts">
	// A playlist page (community or YouTube Music's own): its songs, in order.
	import { getPlaylist } from '../api';
	import { formatTime } from '../format';
	import type { PlaylistPage } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import PlayAllButtons from './PlayAllButtons.svelte';
	import type { MenuTarget } from './SongMenu.svelte';
	import SongList from './SongList.svelte';

	let {
		room,
		id,
		title,
		onToast,
		onMenu
	}: {
		room: RoomConnection;
		id: string;
		/** Shown while loading. */
		title: string;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
	} = $props();

	let playlist = $state<PlaylistPage | null>(null);
	let error = $state<string | null>(null);
	let showDescription = $state(false);

	$effect(() => {
		const playlistId = id;
		playlist = null;
		error = null;
		getPlaylist(playlistId)
			.then((p) => playlistId === id && (playlist = p))
			.catch((e) => (error = e instanceof Error ? e.message : "Couldn't load the playlist"));
	});

	const total = $derived(playlist?.songs.reduce((sum, s) => sum + s.durationMs, 0) ?? 0);
</script>

<div class="flex flex-col gap-6">
	<header class="flex flex-col gap-5 px-2 sm:flex-row sm:items-end">
		<div class="aspect-square w-48 shrink-0 overflow-hidden rounded-lg bg-raised shadow-2xl sm:w-56">
			{#if playlist?.thumbnails.at(-1)}
				<img src={playlist.thumbnails.at(-1)!.url} alt="" class="h-full w-full object-cover" />
			{/if}
		</div>
		<div class="min-w-0">
			<p class="text-xs font-medium tracking-wide text-muted uppercase">Playlist</p>
			<h1 class="text-3xl font-black break-words sm:text-4xl">{playlist?.title ?? title}</h1>
			{#if playlist}
				<p class="mt-1 text-muted">
					{#if playlist.author}<span class="font-medium text-white">{playlist.author}</span> • {/if}{playlist.songs.length} songs • {formatTime(total)}
				</p>
				{#if playlist.description}
					<button class="mt-1 max-w-prose text-left text-sm text-muted {showDescription ? '' : 'line-clamp-2'}" onclick={() => (showDescription = !showDescription)}>
						{playlist.description}
					</button>
				{/if}
			{/if}
			<PlayAllButtons {room} songs={playlist?.songs ?? null} name={playlist?.title ?? title} {onToast} />
		</div>
	</header>

	{#if error}
		<p class="px-2 text-accent">{error}</p>
	{:else if !playlist}
		<p class="px-2 text-muted">Loading…</p>
	{:else if playlist.songs.length === 0}
		<p class="px-2 text-muted">This playlist has no songs that can be played.</p>
	{:else}
		<section class="flex flex-col gap-1">
			<SongList {room} songs={playlist.songs} {onToast} {onMenu} />
		</section>
	{/if}
</div>

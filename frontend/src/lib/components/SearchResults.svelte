<script lang="ts">
	// Search results, or "Find similar" for a song. Tap = play next; ⋮ or right-click = song menu.
	import { search, similar } from '../api';
	import { artistNames, formatTime } from '../format';
	import type { Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let {
		room,
		query,
		similarTo = null,
		onToast,
		onMenu
	}: {
		room: RoomConnection;
		query: string;
		/** Show songs similar to this one instead of searching. */
		similarTo?: Song | null;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
	} = $props();

	let results = $state<Song[]>([]);
	let loading = $state(false);
	let error = $state<string | null>(null);

	// Search shortly after typing stops; cancel searches that are no longer needed.
	$effect(() => {
		const q = query.trim();
		const seed = similarTo;
		if (!q && !seed) {
			results = [];
			return;
		}
		const controller = new AbortController();
		const timer = setTimeout(
			async () => {
				loading = true;
				error = null;
				try {
					results = seed ? await similar(seed.id, controller.signal) : await search(q, controller.signal);
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

	async function playNext(song: Song) {
		const error = await room.run({ kind: 'AddSongs', songs: [song], position: 'next' });
		onToast(error ? error.message : `Playing "${song.title}" next`);
	}

	function menu(event: MouseEvent, song: Song, atPointer: boolean) {
		event.preventDefault();
		event.stopPropagation();
		const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
		onMenu({ song, place: 'search', x: atPointer ? event.clientX : rect.right - 256, y: atPointer ? event.clientY : rect.bottom });
	}
</script>

<section class="flex flex-col gap-1">
	<h2 class="truncate px-2 pb-2 text-xl font-bold">{similarTo ? `Similar to ${similarTo.title}` : 'Songs'}</h2>
	{#if error}
		<p class="px-2 text-muted">{error}</p>
	{:else if loading && results.length === 0}
		<p class="px-2 text-muted">{similarTo ? 'Finding similar songs…' : 'Searching…'}</p>
	{:else if results.length === 0}
		<p class="px-2 text-muted">No results.</p>
	{/if}

	{#each results as song (song.id)}
		<div class="group relative flex items-center gap-3 rounded-md px-2 py-2 hover:bg-raised" role="listitem" oncontextmenu={(e) => menu(e, song, true)}>
			<!-- Tap = play next (docs/features/ui.md#gestures). -->
			<button class="flex min-w-0 flex-1 items-center gap-3 text-left" onclick={() => playNext(song)} title="Play next">
				<Art {song} size={48} class="h-12 w-12" />
				<div class="min-w-0 flex-1">
					<div class="truncate font-medium">{song.title}</div>
					<div class="truncate text-sm text-muted">
						{artistNames(song)}{song.album ? ` • ${song.album.name}` : ''}
					</div>
				</div>
				<span class="text-sm text-muted tabular-nums">{formatTime(song.durationMs)}</span>
			</button>
			<button class="rounded-full p-2 text-muted hover:bg-line hover:text-white" aria-label="More for {song.title}" onclick={(e) => menu(e, song, false)}>
				<Icon name="more" />
			</button>
		</div>
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

<script lang="ts">
	// Search results, or "Find similar" for a song. Tap = play next; ⋮ or right-click = song menu.
	import { search, similar } from '../api';
	import type { Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import SongList from './SongList.svelte';
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

	<SongList {room} songs={results} {onToast} {onMenu} />

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

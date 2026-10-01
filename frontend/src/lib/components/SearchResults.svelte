<script lang="ts">
	import { search } from '../api';
	import { artistNames, formatTime } from '../format';
	import type { QueuePosition, Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';

	let {
		room,
		query,
		onToast
	}: { room: RoomConnection; query: string; onToast: (text: string) => void } = $props();

	let results = $state<Song[]>([]);
	let loading = $state(false);
	let error = $state<string | null>(null);
	let menuFor = $state<string | null>(null);

	// Search shortly after typing stops; cancel searches that are no longer needed.
	$effect(() => {
		const q = query.trim();
		if (!q) {
			results = [];
			return;
		}
		const controller = new AbortController();
		const timer = setTimeout(async () => {
			loading = true;
			error = null;
			try {
				results = await search(q, controller.signal);
			} catch (e) {
				if (!controller.signal.aborted) error = e instanceof Error ? e.message : 'Search failed';
			} finally {
				if (!controller.signal.aborted) loading = false;
			}
		}, 300);
		return () => {
			clearTimeout(timer);
			controller.abort();
		};
	});

	async function add(song: Song, position: QueuePosition) {
		menuFor = null;
		const error = await room.run({ kind: 'AddSongs', songs: [song], position });
		onToast(error ? error.message : position === 'next' ? `Playing "${song.title}" next` : `Added "${song.title}" to the queue`);
	}

	async function playNow(song: Song) {
		menuFor = null;
		const error = await room.run({ kind: 'PlayNow', song });
		if (error) onToast(error.message);
	}
</script>

<svelte:window onclick={() => (menuFor = null)} />

<section class="flex flex-col gap-1">
	<h2 class="px-2 pb-2 text-xl font-bold">Songs</h2>
	{#if error}
		<p class="px-2 text-muted">{error}</p>
	{:else if loading && results.length === 0}
		<p class="px-2 text-muted">Searching…</p>
	{:else if results.length === 0}
		<p class="px-2 text-muted">No results.</p>
	{/if}

	{#each results as song (song.id)}
		<div class="group relative flex items-center gap-3 rounded-md px-2 py-2 hover:bg-raised">
			<!-- Tap = play next (docs/features/ui.md#gestures). -->
			<button class="flex min-w-0 flex-1 items-center gap-3 text-left" onclick={() => add(song, 'next')} title="Play next">
				<Art {song} size={48} class="h-12 w-12" />
				<div class="min-w-0 flex-1">
					<div class="truncate font-medium">{song.title}</div>
					<div class="truncate text-sm text-muted">
						{artistNames(song)}{song.album ? ` • ${song.album.name}` : ''}
					</div>
				</div>
				<span class="text-sm text-muted tabular-nums">{formatTime(song.durationMs)}</span>
			</button>
			<button
				class="rounded-full p-2 text-muted hover:bg-line hover:text-white"
				aria-label="More actions"
				onclick={(e) => {
					e.stopPropagation();
					menuFor = menuFor === song.id ? null : song.id;
				}}
			>
				<Icon name="more" />
			</button>

			{#if menuFor === song.id}
				<div
					class="absolute top-12 right-2 z-20 w-48 overflow-hidden rounded-lg bg-raised py-2 shadow-xl ring-1 ring-line"
					role="menu"
				>
					<button class="flex w-full items-center gap-3 px-4 py-2 hover:bg-line" onclick={() => add(song, 'next')}>
						<Icon name="playNext" size={20} /> Play next
					</button>
					<button class="flex w-full items-center gap-3 px-4 py-2 hover:bg-line" onclick={() => add(song, 'end')}>
						<Icon name="playlistAdd" size={20} /> Add to queue
					</button>
					<button class="flex w-full items-center gap-3 px-4 py-2 hover:bg-line" onclick={() => playNow(song)}>
						<Icon name="play" size={20} /> Play now
					</button>
				</div>
			{/if}
		</div>
	{/each}
</section>

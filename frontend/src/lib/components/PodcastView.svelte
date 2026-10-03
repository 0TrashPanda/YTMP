<script lang="ts">
	// A podcast page: what it's about, and its episodes (newest first). Tap an episode =
	// play next; ⋮ or right-click = the song menu.
	import { getPodcast } from '../api';
	import { formatTime } from '../format';
	import type { Episode, PodcastPage } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Icon from './Icon.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

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

	let podcast = $state<PodcastPage | null>(null);
	let error = $state<string | null>(null);
	let showDescription = $state(false);

	$effect(() => {
		const podcastId = id;
		podcast = null;
		error = null;
		getPodcast(podcastId)
			.then((p) => podcastId === id && (podcast = p))
			.catch((e) => (error = e instanceof Error ? e.message : "Couldn't load the podcast"));
	});

	async function play(episode: Episode, now: boolean) {
		const e = await room.run(now ? { kind: 'PlayNow', song: episode.song } : { kind: 'AddSongs', songs: [episode.song], position: 'next' });
		if (e) onToast(e.message);
		else if (!now) onToast(`Playing "${episode.song.title}" next`);
	}

	function menu(event: MouseEvent, episode: Episode, atPointer: boolean) {
		event.preventDefault();
		event.stopPropagation();
		const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
		onMenu({ song: episode.song, place: 'search', x: atPointer ? event.clientX : rect.right - 256, y: atPointer ? event.clientY : rect.bottom });
	}

	/** "1 hr 5 min" like YTM, instead of 65:00. */
	function length(ms: number): string {
		const minutes = Math.round(ms / 60_000);
		if (minutes < 60) return minutes ? `${minutes} min` : formatTime(ms);
		return `${Math.floor(minutes / 60)} hr${minutes % 60 ? ` ${minutes % 60} min` : ''}`;
	}

	const latest = $derived(podcast?.episodes[0] ?? null);
</script>

<div class="flex flex-col gap-6">
	<header class="flex flex-col gap-5 px-2 sm:flex-row sm:items-end">
		<div class="aspect-square w-48 shrink-0 overflow-hidden rounded-lg bg-raised shadow-2xl sm:w-56">
			{#if podcast?.thumbnails.at(-1)}
				<img src={podcast.thumbnails.at(-1)!.url} alt="" referrerpolicy="no-referrer" class="h-full w-full object-cover" />
			{/if}
		</div>
		<div class="min-w-0">
			<p class="text-xs font-medium tracking-wide text-muted uppercase">Podcast</p>
			<h1 class="text-3xl font-black break-words sm:text-4xl">{podcast?.title ?? title}</h1>
			{#if podcast?.author}
				<p class="mt-1 font-medium">{podcast.author}</p>
			{/if}
			{#if podcast?.description}
				<button class="mt-1 max-w-prose text-left text-sm whitespace-pre-line text-muted {showDescription ? '' : 'line-clamp-2'}" onclick={() => (showDescription = !showDescription)}>
					{podcast.description}
				</button>
			{/if}
			{#if latest && room.can('play_now')}
				<div class="mt-3 flex flex-wrap gap-2">
					<button class="flex items-center gap-2 rounded-full bg-white px-4 py-2 text-sm font-medium text-black" onclick={() => play(latest, true)}>
						<Icon name="play" size={18} /> Latest episode
					</button>
				</div>
			{/if}
		</div>
	</header>

	{#if error}
		<p class="px-2 text-accent">{error}</p>
	{:else if !podcast}
		<p class="px-2 text-muted">Loading…</p>
	{:else if podcast.episodes.length === 0}
		<p class="px-2 text-muted">No episodes.</p>
	{:else}
		<section class="flex flex-col gap-1">
			<h2 class="px-2 pb-1 text-xl font-bold">Episodes</h2>
			{#each podcast.episodes as episode (episode.song.id)}
				<div class="group flex items-start gap-3 rounded-md px-2 py-3 hover:bg-raised" role="listitem" oncontextmenu={(e) => menu(e, episode, true)}>
					<button class="flex min-w-0 flex-1 items-start gap-3 text-left" onclick={() => play(episode, false)} title="Play next">
						<div class="aspect-video w-28 shrink-0 overflow-hidden rounded bg-raised sm:w-36">
							{#if episode.song.thumbnails.at(-1)}
								<img src={episode.song.thumbnails.at(-1)!.url} alt="" loading="lazy" referrerpolicy="no-referrer" class="h-full w-full object-cover" />
							{/if}
						</div>
						<div class="min-w-0 flex-1">
							<div class="line-clamp-2 font-medium">{episode.song.title}</div>
							<div class="text-sm text-muted">
								{[episode.date, episode.song.durationMs ? length(episode.song.durationMs) : null].filter(Boolean).join(' • ')}
							</div>
							{#if episode.description}
								<div class="mt-1 line-clamp-2 text-sm text-muted">{episode.description}</div>
							{/if}
						</div>
					</button>
					<button class="rounded-full p-2 text-muted hover:bg-line hover:text-white" aria-label="More for {episode.song.title}" onclick={(e) => menu(e, episode, false)}>
						<Icon name="more" />
					</button>
				</div>
			{/each}
		</section>
	{/if}
</div>

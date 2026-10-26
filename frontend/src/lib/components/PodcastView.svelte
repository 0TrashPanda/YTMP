<script lang="ts">
	// A podcast page: what it's about, and its episodes (newest first). Tap an episode =
	// play next; ⋮ or right-click = the song menu. With YouTube Music: Subscribe (your library).
	// With a YTMP account: how far you got in each episode, and marking them played.
	import { whileBusy } from '../busy.svelte';
	import { thumbUrl } from '../images.svelte';
	import { getMyPodcast, getPodcast, setPodcastSaved } from '../api';
	import { youtube } from '../youtube.svelte';
	import { listening } from '../listening.svelte';
	import { menuAt, playNext, playNow, quoted } from '../songActions';
	import { formatTime } from '../format';
	import type { Episode, EpisodeProgress, PodcastPage } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Icon from './Icon.svelte';
	import Skeleton from './Skeleton.svelte';
	import PinButton from './PinButton.svelte';
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

	/** How far you got in the episodes (by song ID), on your account. */
	let progress = $state<Map<string, EpisodeProgress>>(new Map());
	$effect(() => {
		const page = podcast;
		progress = new Map();
		if (!page || !listening.available) return;
		listening
			.episodes(page.episodes.map((e) => e.song.id))
			.then((p) => podcast === page && (progress = p))
			.catch(() => {});
	});

	function marked(songId: string, played: boolean) {
		const next = new Map(progress);
		next.set(songId, { id: songId, positionMs: 0, durationMs: progress.get(songId)?.durationMs ?? 0, played });
		progress = next;
	}

	/** In your YouTube Music library; null: not known (signed out, or not loaded yet). */
	let saved = $state<boolean | null>(null);
	let saving = $state(false);
	$effect(() => {
		const podcastId = id;
		saved = null;
		if (!youtube.account) return;
		getMyPodcast(podcastId)
			.then((p) => podcastId === id && (saved = p.saved ?? null))
			.catch(() => {});
	});

	async function toggleSaved() {
		if (saved === null || saving) return;
		saving = true;
		const want = !saved;
		try {
			await whileBusy(want ? 'Subscribing…' : 'Unsubscribing…', setPodcastSaved(id, want));
			saved = want;
			onToast(want ? 'Subscribed: new episodes show on Home' : 'Unsubscribed');
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't change that");
		} finally {
			saving = false;
		}
	}

	async function play(episode: Episode, now: boolean) {
		if (now) await playNow(room, episode.song, onToast);
		else await playNext(room, [episode.song], quoted(episode.song), onToast);
	}

	function menu(event: MouseEvent, episode: Episode, atPointer: boolean) {
		event.preventDefault();
		event.stopPropagation();
		const song = episode.song;
		const mark = listening.available ? { played: !!progress.get(song.id)?.played, onMarked: (played: boolean) => marked(song.id, played) } : undefined;
		onMenu({ song, place: 'search', episode: mark, ...menuAt(event.currentTarget as HTMLElement, event, atPointer) });
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
				<img src={thumbUrl(podcast.thumbnails, 544)} alt="" referrerpolicy="no-referrer" class="h-full w-full object-cover" />
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
			<div class="mt-3 flex flex-wrap gap-2">
				{#if latest && room.can('play_now')}
					<button class="flex items-center gap-2 rounded-full bg-white px-4 py-2 text-sm font-medium text-black" onclick={() => play(latest, true)}>
						<Icon name="play" size={18} /> Latest episode
					</button>
				{/if}
				{#if saved !== null}
					<button class="flex items-center gap-2 rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-50" disabled={saving} onclick={toggleSaved}>
						<Icon name={saved ? 'check' : 'add'} size={18} />
						{saved ? 'Subscribed' : 'Subscribe'}
					</button>
				{/if}
				<PinButton pin={podcast ? { kind: 'podcast', id, title: podcast.title, art: thumbUrl(podcast.thumbnails, 120) } : null} {onToast} />
			</div>
		</div>
	</header>

	{#if error}
		<p class="px-2 text-accent">{error}</p>
	{:else if !podcast}
		<Skeleton rows={6} />
	{:else if podcast.episodes.length === 0}
		<p class="px-2 text-muted">No episodes.</p>
	{:else}
		<section class="flex flex-col gap-1">
			<h2 class="px-2 pb-1 text-xl font-bold">Episodes</h2>
			{#each podcast.episodes as episode, i (episode.song.id + i)}
				{@const heard = progress.get(episode.song.id)}
				<div class="group flex items-start gap-3 rounded-md px-2 py-3 hover:bg-raised" role="listitem" oncontextmenu={(e) => menu(e, episode, true)}>
					<button class="flex min-w-0 flex-1 items-start gap-3 text-left" onclick={() => play(episode, false)} title="Play next">
						<div class="relative aspect-video w-28 shrink-0 overflow-hidden rounded bg-raised sm:w-36">
							{#if episode.song.thumbnails.at(-1)}
								<img src={thumbUrl(episode.song.thumbnails, 160)} alt="" loading="lazy" referrerpolicy="no-referrer" class="h-full w-full object-cover {heard?.played ? 'opacity-50' : ''}" />
							{/if}
							<!-- How far you got, like YouTube Music. -->
							{#if heard && !heard.played && heard.positionMs > 0 && heard.durationMs > 0}
								<div class="absolute inset-x-0 bottom-0 h-1 bg-white/30">
									<div class="h-full bg-accent" style:width="{Math.min(100, (heard.positionMs / heard.durationMs) * 100)}%"></div>
								</div>
							{/if}
						</div>
						<div class="min-w-0 flex-1">
							<div class="line-clamp-2 font-medium">{episode.song.title}</div>
							<div class="flex items-center gap-1 text-sm text-muted">
								{#if heard?.played}
									<Icon name="check" size={16} /> Played •
								{:else if heard && heard.positionMs > 0 && heard.durationMs > heard.positionMs}
									{length(heard.durationMs - heard.positionMs)} left •
								{/if}
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

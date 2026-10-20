<script lang="ts">
	import { wheelAdjust } from '../wheel';
	import { MediaQuery } from 'svelte/reactivity';
	import { getAlbum, prefetch } from '../api';
	import { artistNames, formatTime } from '../format';
	import { images } from '../images.svelte';
	import { likes } from '../likes.svelte';
	import { isYoutubeSong } from '../ownModule';
	import { youtube } from '../youtube.svelte';
	import type { AlbumRef, ArtistRef, PodcastRef } from '../protocol.gen';
	import type { RoomPlayer } from '../player.svelte';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import NextButton from './NextButton.svelte';
	import RepeatButton from './RepeatButton.svelte';
	import ShuffleButton from './ShuffleButton.svelte';
	import OutputsSheet from './OutputsSheet.svelte';
	import Progress from './Progress.svelte';
	import SkipButtons from './SkipButtons.svelte';
	import SpeedButton from './SpeedButton.svelte';
	import { sleepTimer } from '../sleepTimer.svelte';

	let {
		room,
		player,
		positionMs,
		expanded,
		onExpand,
		onToast,
		onSongMenu,
		onArtist,
		onAlbum,
		onPodcast,
		onSleepTimer
	}: {
		room: RoomConnection;
		player: RoomPlayer;
		positionMs: number;
		/** The full player is open. */
		expanded: boolean;
		/** Open (true) or close the full player. */
		onExpand: (open: boolean) => void;
		onToast: (text: string) => void;
		/** Right-click or ⋮ on the current song. */
		onSongMenu: (event: MouseEvent) => void;
		onArtist: (artist: ArtistRef) => void;
		onAlbum: (album: AlbumRef) => void;
		onPodcast: (podcast: PodcastRef) => void;
		/** The sleep timer's sheet (a moon shows while it runs). */
		onSleepTimer: () => void;
	} = $props();

	let showOutputs = $state(false);
	const activeOutputs = $derived(room.state?.outputs.filter((o) => o.active).length ?? 0);

	const current = $derived(room.state?.nowPlaying ?? null);
	const playback = $derived(room.state?.playback);
	const duration = $derived(current?.item.song.durationMs ?? 0);
	// A new song that waits for everyone's players to load it counts as playing (and loading).
	const playing = $derived(!!playback?.playing || !!playback?.starting);
	// A paused room may not have the stream yet (a restored room gets it on play): only loading while playing.
	const loading = $derived(current !== null && (current.streamUrl === null || !!playback?.starting) && playing);
	const status = $derived(loading ? ' • loading…' : player.buffering ? ' • buffering…' : '');

	// Phones: swipe the mini player up to open the full player (a tap opens it too).
	let swipeStart: number | null = null;

	// Desktop, like YTM: "Artist • Album • Year". Songs don't carry their year, so it comes
	// from the album's page (remembered, so once per album).
	const wide = new MediaQuery('min-width: 640px');
	const albumId = $derived(current?.item.song.album?.id ?? null);
	let year = $state<string | null>(null);
	$effect(() => {
		const id = albumId;
		year = null;
		if (!id || !wide.current) return;
		let stale = false;
		getAlbum(id)
			.then((album) => {
				if (!stale) year = album.year;
			})
			.catch(() => {}); // just no year
		return () => (stale = true);
	});

	// Desktop: thumbs up next to the song, like YTM.
	const songId = $derived(current?.item.song.id);
	const liked = $derived(likes.get(songId));
	const rating = $derived(likes.rating(songId));
	/** Thumbs down; in a solo room the song is skipped too, like YouTube Music (not in a party: it's everyone's music). */
	async function toggleDislike(id: string) {
		if (rating === null) return;
		const disliking = rating !== 'dislike';
		const solo = room.state?.room.visibility === 'private';
		if ((await likes.rate(id, disliking ? 'dislike' : 'none', onToast)) && disliking && solo && room.can('skip')) await room.run({ kind: 'Skip', play: false });
	}
	$effect(() => {
		if (songId && wide.current) likes.load(songId);
	});

	/** Desktop: a click anywhere on the bar that isn't on a button or link opens (or closes) the full player, like YTM. */
	function barClick(event: MouseEvent) {
		if ((event.target as Element).closest('button, a, input')) return;
		if (current || expanded) onExpand(!expanded);
	}
</script>

<footer class="relative z-40 border-line bg-surface sm:border-t">
	<!-- Phones: YTM's mini player. The song (tap or swipe up for the full player), play/pause, next. -->
	<div
		class="sm:hidden"
		role="presentation"
		ontouchstart={(e) => (swipeStart = e.touches[0].clientY)}
		ontouchmove={(e) => {
			if (swipeStart !== null && swipeStart - e.touches[0].clientY > 30) {
				swipeStart = null;
				if (current) onExpand(true);
			}
		}}
		ontouchend={() => (swipeStart = null)}
	>
		<div class="flex h-16 items-center gap-1 pr-1 pl-2">
			<button class="flex min-w-0 flex-1 items-center gap-2.5 text-left" disabled={!current} onclick={() => onExpand(true)} oncontextmenu={onSongMenu}>
				<Art song={current?.item.song ?? null} size={96} class="h-10 w-10 shrink-0" />
				<div class="min-w-0 flex-1">
					<div class="truncate text-sm font-medium">{current?.item.song.title ?? 'Nothing playing'}</div>
					<div class="truncate text-xs text-muted">
						{#if current}{artistNames(current.item.song)}{status}{/if}
					</div>
				</div>
			</button>
			<button
				class="shrink-0 rounded-full p-1.5 disabled:opacity-40"
				disabled={!room.can('play_pause')}
				aria-label={playing ? 'Pause' : 'Play'}
				onclick={() => room.run({ kind: playing ? 'Pause' : 'Play' })}
			>
				<Icon name={playing ? 'pause' : 'play'} size={32} />
			</button>
			<NextButton {room} size={26} class="shrink-0 p-1.5" />
		</div>
		<!-- At the bottom, between the mini player and the bottom bar, like YTM. -->
		<div class="h-0.5 bg-white/15">
			<div class="h-full bg-accent" style:width="{duration ? Math.min(100, (positionMs / duration) * 100) : 0}%"></div>
		</div>
	</div>

	<!-- Larger screens: controls left, the song in the middle (centered), outputs and "Play here" right. -->
	<div class="hidden sm:block">
		<Progress {room} {positionMs} />
		<div class="grid h-18 cursor-pointer grid-cols-[1fr_minmax(0,auto)_1fr] items-center gap-4 px-4" role="presentation" onclick={barClick}>
			<div class="flex items-center justify-self-start">
				<button class="rounded-full p-2 hover:bg-raised disabled:opacity-40" aria-label="Previous" disabled={!room.canPrevious} onclick={() => room.run({ kind: 'Previous' })}>
					<Icon name="previous" />
				</button>
				{#snippet playPause()}
					<button
						class="rounded-full p-2 hover:bg-raised disabled:opacity-40"
						disabled={!room.can('play_pause')}
						aria-label={playing ? 'Pause' : 'Play'}
						onclick={() => room.run({ kind: playing ? 'Pause' : 'Play' })}
					>
						<Icon name={playing ? 'pause' : 'play'} size={36} />
					</button>
				{/snippet}
				{#if current?.item.song.podcast}
					<!-- Episodes: back 10 s and ahead 30 s around play, like YTM. -->
					<SkipButtons {room} {positionMs}>{@render playPause()}</SkipButtons>
				{:else}
					{@render playPause()}
				{/if}
				<NextButton {room} class="p-2 hover:bg-raised" />
				{#if current?.item.song.podcast}
					<SpeedButton {room} {onToast} up />
				{/if}
				<span class="pl-2 text-xs text-muted tabular-nums">
					{formatTime(Math.min(positionMs, duration))} / {formatTime(duration)}
				</span>
			</div>

			<div class="flex max-w-xl min-w-0 items-center gap-3 justify-self-center" role="presentation" oncontextmenu={onSongMenu}>
				<div class="flex min-w-0 items-center gap-3" title={current ? `Added by ${current.item.addedByName}` : undefined}>
					<Art song={current?.item.song ?? null} size={96} class="h-12 w-12 shrink-0" />
					<div class="min-w-0">
						<div class="truncate font-medium">{current?.item.song.title ?? 'Nothing playing'}</div>
						{#if current}
							{@const song = current.item.song}
							<div class="truncate text-sm text-muted">
								{#if song.podcast}
									{@const podcast = song.podcast}
									<button class="hover:text-white hover:underline" onclick={() => onPodcast(podcast)} onpointerenter={() => prefetch('podcast', podcast.id)}>{podcast.name}</button>
								{/if}
								{#each song.artists as artist, i (i)}
									{#if i > 0}{', '}{/if}<button class="hover:text-white hover:underline" onclick={() => onArtist(artist)} onpointerenter={() => artist.id && prefetch('artist', artist.id)}
										>{artist.name}</button
									>
								{/each}
								{#if song.album}
									{@const album = song.album}
									• <button class="hover:text-white hover:underline" onclick={() => onAlbum(album)} onpointerenter={() => album.id && prefetch('album', album.id)}>{album.name}</button>
								{/if}
								{#if year}• {year}{/if}{status}
							</div>
						{/if}
					</div>
				</div>
				{#if current && youtube.account && isYoutubeSong(current.item.song.id) && images.dislikeButton !== 'hidden'}
					{@const id = current.item.song.id}
					<button
						class="shrink-0 rounded-full p-1.5 text-muted hover:bg-raised hover:text-white disabled:opacity-40 {rating === 'dislike' ? 'text-white' : ''} {images.dislikeButton === 'dim' ? 'opacity-35 hover:opacity-100' : ''}"
						disabled={rating === null}
						aria-label={rating === 'dislike' ? 'Remove dislike' : 'Dislike'}
						aria-pressed={rating === 'dislike'}
						onclick={() => toggleDislike(id)}
					>
						<Icon name={rating === 'dislike' ? 'disliked' : 'dislike'} size={20} />
					</button>
				{/if}
				{#if current && youtube.account && isYoutubeSong(current.item.song.id)}
					{@const id = current.item.song.id}
					<button
						class="shrink-0 rounded-full p-1.5 text-muted hover:bg-raised hover:text-white disabled:opacity-40 {liked ? 'text-white' : ''}"
						disabled={liked === null}
						aria-label={liked ? 'Remove like' : 'Like'}
						aria-pressed={!!liked}
						onclick={() => liked !== null && likes.set(id, !liked, onToast)}
					>
						<Icon name={liked ? 'liked' : 'like'} size={20} />
					</button>
				{/if}
				{#if current}
					<button
						class="shrink-0 rounded-full p-1.5 text-muted hover:bg-raised hover:text-white"
						aria-label="More for {current.item.song.title}"
						onclick={(e) => {
							const r = e.currentTarget.getBoundingClientRect();
							onSongMenu(new MouseEvent('contextmenu', { clientX: r.left, clientY: r.top - 8 }));
						}}
					>
						<Icon name="more" size={20} />
					</button>
				{/if}
			</div>

			<div class="flex items-center gap-2 justify-self-end">
				{#if player.enabled}
					<input
						type="range"
						class="hidden w-24 accent-white md:block"
						min="0"
						max="1"
						step="0.01"
						value={player.volume}
						aria-label="Volume"
						use:wheelAdjust
						oninput={(e) => player.setVolume(Number(e.currentTarget.value))}
					/>
				{/if}
				<!-- Like YTM: repeat and shuffle next to the volume. -->
				{#if sleepTimer.active}
					<button class="hidden rounded-full p-2 text-accent hover:bg-raised md:block" aria-label="Sleep timer" title="Sleep timer" onclick={onSleepTimer}>
						<Icon name="sleep" size={20} />
					</button>
				{/if}
				<RepeatButton {room} size={20} class="hidden p-2 hover:bg-raised md:block" />
				<ShuffleButton {room} {onToast} size={20} class="hidden p-2 hover:bg-raised md:block" />
				<button
					class="rounded-full p-2 hover:bg-raised {activeOutputs ? 'text-accent' : ''}"
					aria-label="Play on"
					title={activeOutputs ? `Playing on ${activeOutputs} speaker(s)` : 'Play on a speaker or TV'}
					onclick={() => (showOutputs = true)}
				>
					<Icon name="cast" size={20} />
				</button>
				<button
					data-listen-toggle
					class="flex items-center gap-2 rounded-full px-3 py-2 text-sm font-medium
						{player.enabled ? 'bg-white text-black' : 'bg-raised hover:bg-line'}"
					onclick={() => (player.enabled ? player.disable() : player.enable())}
					title="Play the music on this device"
				>
					<Icon name="headphones" size={20} />
					<span class="hidden lg:inline">{player.enabled ? 'Playing here' : 'Play here'}</span>
				</button>
				<button
					class="rounded-full p-2 hover:bg-raised disabled:opacity-40"
					disabled={!current && !expanded}
					aria-label={expanded ? 'Close the player' : 'Open the player'}
					onclick={() => onExpand(!expanded)}
				>
					<Icon name={expanded ? 'collapse' : 'expand'} />
				</button>
			</div>
		</div>
	</div>
	{#if showOutputs}
		<OutputsSheet {room} {player} onClose={() => (showOutputs = false)} {onToast} />
	{/if}
	{#if player.error}
		<p class="px-4 pb-2 text-sm text-accent">{player.error}</p>
	{/if}
</footer>

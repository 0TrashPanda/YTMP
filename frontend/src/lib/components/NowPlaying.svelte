<script lang="ts">
	// The full player, like YTM's. Phones (and tablets): the whole screen, with the album art,
	// the song, the position, the controls and where it plays; "Up next" and "Related" slide up
	// over it. Swipe down (or the chevron, or Back) closes it. Desktop: above the player bar,
	// which keeps the controls and the song; the art on the left, Up next and Related on the right.
	import { fly } from 'svelte/transition';
	import { MediaQuery } from 'svelte/reactivity';
	import { prefetch } from '../api';
	import { likes } from '../likes.svelte';
	import { youtube } from '../youtube.svelte';
	import type { PageHandlers } from '../cards';
	import type { RoomPlayer } from '../player.svelte';
	import type { Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import OutputsSheet from './OutputsSheet.svelte';
	import Progress from './Progress.svelte';
	import QueuePanel from './QueuePanel.svelte';
	import SearchResults from './SearchResults.svelte';
	import SkipButtons from './SkipButtons.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let {
		room,
		player,
		positionMs,
		onClose,
		onToast,
		onMenu,
		onSongMenu,
		onSave,
		...open
	}: {
		room: RoomConnection;
		player: RoomPlayer;
		positionMs: number;
		onClose: () => void;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
		/** Right-click or ⋮ on the current song. */
		onSongMenu: (event: MouseEvent) => void;
		/** Save to one of your YouTube Music playlists. */
		onSave: (song: Song) => void;
	} & Omit<PageHandlers, 'onPlaylist'> = $props();

	const current = $derived(room.state?.nowPlaying ?? null);
	const song = $derived(current?.item.song ?? null);
	const playback = $derived(room.state?.playback);
	// A paused room may not have the stream yet (a restored room gets it on play): only loading while playing.
	const loading = $derived(current !== null && current.streamUrl === null && !!playback?.playing);

	/** Desktop shows the tabs next to the art, always open. */
	const wide = new MediaQuery('min-width: 1024px');
	let tab = $state<'queue' | 'related'>('queue');

	// Thumbs up, with your YouTube Music sign-in.
	const liked = $derived(likes.get(song?.id));
	$effect(() => {
		if (song?.id) likes.load(song.id);
	});

	function toggleLike() {
		if (song && liked !== null) likes.set(song.id, !liked, onToast);
	}
	/** Phones: Up next / Related slid up over the player. */
	let sheet = $state(false);
	let tabsSwipe: { x: number; y: number } | null = null;
	let showOutputs = $state(false);

	const activeOutputs = $derived(room.state?.outputs.filter((o) => o.active) ?? []);
	const playingOn = $derived.by(() => {
		const names = activeOutputs.map((o) => o.name);
		if (player.enabled) names.unshift('this device');
		if (names.length === 0) return null;
		return names.length > 2 ? `${names[0]} and ${names.length - 1} more` : names.join(' and ');
	});

	// Swipe down to close (phones).
	let swipeStart: number | null = null;
	let dragY = $state(0);
	function onTouchEnd() {
		if (dragY > 120) onClose();
		dragY = 0;
		swipeStart = null;
	}

	// Swipe Up next / Related down to close it: from the handle and tabs, or from the list
	// while it is scrolled to the top (like YTM). Not while dragging a song in the queue.
	let sheetSwipe: { x: number; y: number; ok: boolean } | null = null;
	let sheetY = $state(0);

	function sheetTouchStart(e: TouchEvent) {
		const target = e.target as HTMLElement;
		let scrolled = false;
		for (let el: HTMLElement | null = target; el && el !== e.currentTarget; el = el.parentElement) {
			if (el.scrollTop > 0) scrolled = true;
		}
		sheetSwipe = { x: e.touches[0].clientX, y: e.touches[0].clientY, ok: !scrolled && !target.closest('.touch-none') };
	}

	function sheetTouchMove(e: TouchEvent) {
		if (!sheetSwipe?.ok) return;
		const dy = e.touches[0].clientY - sheetSwipe.y;
		const dx = e.touches[0].clientX - sheetSwipe.x;
		// Scrolling the list up first, or swiping a song away: not closing.
		if (sheetY === 0 && (dy < 0 || Math.abs(dx) > Math.abs(dy))) sheetSwipe.ok = false;
		else sheetY = Math.max(0, dy);
	}

	function sheetTouchEnd() {
		if (sheetY > 100) sheet = false;
		sheetY = 0;
		sheetSwipe = null;
	}

	function pickTab(next: 'queue' | 'related') {
		if (sheet && tab === next) sheet = false;
		else {
			tab = next;
			sheet = true;
		}
	}

	function moreButton(e: MouseEvent) {
		const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
		onSongMenu(new MouseEvent('contextmenu', { clientX: r.right - 256, clientY: r.bottom }));
	}
</script>

<!-- [bottom]: the phone's tabs under the player, with more room to tap. -->
{#snippet tabs(bottom = false)}
	<div class="flex shrink-0 {bottom ? 'pb-2' : 'border-b border-white/10'}" role="tablist">
		{#each [['queue', 'Up next'], ['related', 'Related']] as const as [value, label] (value)}
			<button
				class="flex-1 {bottom ? 'py-5' : 'py-4'} text-sm font-medium tracking-wide uppercase {tab === value && (sheet || wide.current) ? 'border-b-2 border-white text-white' : 'text-muted'}"
				role="tab"
				aria-selected={tab === value}
				onclick={() => pickTab(value)}>{label}</button
			>
		{/each}
	</div>
{/snippet}

{#snippet tabContent()}
	{#if tab === 'queue'}
		<QueuePanel {room} {onMenu} title={false} />
	{:else if song}
		<div class="h-full overflow-y-auto p-2">
			<SearchResults {room} query="" similarTo={song} {onToast} {onMenu} />
		</div>
	{/if}
{/snippet}

<div
	class="fixed inset-0 z-50 isolate flex flex-col overflow-hidden bg-bg lg:absolute lg:z-30"
	style:transform={dragY ? `translateY(${dragY}px)` : undefined}
	transition:fly={{ y: 600, duration: 250, opacity: 1 }}
	role="dialog"
	aria-label="Now playing"
>
	<!-- The album art, blurred, as the background (YTM uses its colours). -->
	{#if song}
		<div class="pointer-events-none absolute inset-0 -z-10 opacity-40">
			<Art {song} size={96} class="h-full w-full scale-125 blur-3xl" />
		</div>
		<div class="pointer-events-none absolute inset-0 -z-10 bg-gradient-to-b from-transparent to-bg"></div>
	{/if}

	<div
		class="flex h-14 shrink-0 touch-none items-center gap-2 px-2"
		role="presentation"
		ontouchstart={(e) => (swipeStart = e.touches[0].clientY)}
		ontouchmove={(e) => swipeStart !== null && (dragY = Math.max(0, e.touches[0].clientY - swipeStart))}
		ontouchend={onTouchEnd}
	>
		<button class="rounded-full p-2 hover:bg-white/10" aria-label="Close the player" onclick={onClose}>
			<Icon name="collapse" size={28} />
		</button>
		<div class="min-w-0 flex-1 truncate text-center text-sm text-muted lg:hidden">{room.state?.room.name}</div>
		<div class="ml-auto flex items-center lg:hidden">
			<button class="rounded-full p-2 hover:bg-white/10 {activeOutputs.length ? 'text-accent' : ''}" aria-label="Play on" onclick={() => (showOutputs = true)}>
				<Icon name="cast" />
			</button>
			{#if current}
				<button class="rounded-full p-2 hover:bg-white/10" aria-label="More for {current.item.song.title}" onclick={moreButton}>
					<Icon name="more" />
				</button>
			{/if}
		</div>
	</div>

	<div class="flex min-h-0 flex-1 lg:grid lg:grid-cols-[minmax(0,1fr)_minmax(360px,32rem)] lg:gap-8 lg:px-8 lg:pb-6">
		<!-- The song: everything on phones, only the art on desktop (the player bar has the rest, like YTM). -->
		<div
			class="flex min-h-0 min-w-0 flex-1 touch-pan-x flex-col items-center justify-center gap-5 px-6 pb-2"
			role="presentation"
			ontouchstart={(e) => (swipeStart = e.touches[0].clientY)}
			ontouchmove={(e) => swipeStart !== null && (dragY = Math.max(0, e.touches[0].clientY - swipeStart))}
			ontouchend={onTouchEnd}
		>
			<div class="flex min-h-0 w-full flex-1 items-center justify-center" role="presentation" oncontextmenu={onSongMenu}>
				<!-- Desktop: as big as fits, now that the song is in the player bar. -->
				<Art {song} size={1200} class="aspect-square max-h-full w-full max-w-[min(100%,36rem)] rounded-lg shadow-2xl lg:max-w-full" />
			</div>

			{#if song && current}
				<div class="flex w-full max-w-[36rem] min-w-0 shrink-0 items-center gap-1 lg:hidden" role="presentation" oncontextmenu={onSongMenu}>
				<div class="min-w-0 flex-1">
					<h1 class="truncate text-2xl font-bold">{song.title}</h1>
					<p class="truncate text-muted">
						{#if song.podcast}
							{@const podcast = song.podcast}
							<button class="hover:text-white hover:underline" onclick={() => open.onPodcast(podcast)} onpointerenter={() => prefetch('podcast', podcast.id)}>{podcast.name}</button>
						{/if}
						{#each song.artists as artist, i (i)}
							{#if i > 0}{', '}{/if}<button class="hover:text-white hover:underline" onclick={() => open.onArtist(artist)} onpointerenter={() => artist.id && prefetch('artist', artist.id)}>{artist.name}</button>
						{/each}
						{#if song.album}
							{@const album = song.album}
							• <button class="hover:text-white hover:underline" onclick={() => open.onAlbum(album)} onpointerenter={() => album.id && prefetch('album', album.id)}>{album.name}</button>
						{/if}
					</p>
					<p class="mt-0.5 truncate text-xs text-muted">
						Added by {current.item.addedByName}{loading ? ' • loading…' : player.buffering ? ' • buffering…' : ''}
					</p>
				</div>
				{#if youtube.account}
					{@const shown = song}
					<button
						class="shrink-0 rounded-full p-2.5 hover:bg-white/10 disabled:opacity-40"
						disabled={liked === null}
						aria-label={liked ? 'Remove like' : 'Like'}
						aria-pressed={!!liked}
						onclick={toggleLike}
					>
						<Icon name={liked ? 'liked' : 'like'} size={26} />
					</button>
					<button class="shrink-0 rounded-full p-2.5 hover:bg-white/10" aria-label="Save to playlist" onclick={() => onSave(shown)}>
						<Icon name="save" size={26} />
					</button>
				{/if}
				</div>

				<!-- Phones: the controls (desktop has them in the player bar). -->
				<div class="flex w-full max-w-[36rem] shrink-0 flex-col gap-3 lg:hidden">
					<Progress {room} {positionMs} times />
					<div class="flex items-center justify-between">
						<button class="rounded-full p-2 disabled:opacity-40" aria-label="Previous" disabled={!room.can('skip')} onclick={() => room.run({ kind: 'Previous' })}>
							<Icon name="previous" size={36} />
						</button>
						{#snippet playPause()}
							<button
								class="mx-2 grid h-18 w-18 place-items-center rounded-full bg-white text-black disabled:opacity-40"
								disabled={!room.can('play_pause')}
								aria-label={playback?.playing ? 'Pause' : 'Play'}
								onclick={() => room.run({ kind: playback?.playing ? 'Pause' : 'Play' })}
							>
								<Icon name={playback?.playing ? 'pause' : 'play'} size={40} />
							</button>
						{/snippet}
						{#if song.podcast}
							<SkipButtons {room} {positionMs} size={32}>{@render playPause()}</SkipButtons>
						{:else}
							{@render playPause()}
						{/if}
						<button class="rounded-full p-2 disabled:opacity-40" aria-label="Next" disabled={!room.can('skip')} onclick={() => room.run({ kind: 'Skip' })}>
							<Icon name="next" size={36} />
						</button>
					</div>
					<button
						class="flex items-center justify-center gap-2 self-center rounded-full px-3 py-1.5 text-sm {playingOn ? 'text-accent' : 'bg-white/10 text-white'}"
						onclick={() => (showOutputs = true)}
					>
						<Icon name={player.enabled ? 'headphones' : 'cast'} size={18} />
						{playingOn ? `Playing on ${playingOn}` : 'Not playing here. Tap to listen'}
					</button>
				</div>
			{:else}
				<p class="text-muted">Nothing playing. Search for a song to start the music.</p>
			{/if}
		</div>

		<!-- Desktop: Up next and Related next to the art. -->
		{#if wide.current}
			<div class="flex min-h-0 flex-col overflow-hidden rounded-xl bg-surface/60">
				{@render tabs()}
				<div class="min-h-0 flex-1">{@render tabContent()}</div>
			</div>
		{/if}
	</div>

	<!-- Phones: the tabs at the bottom; tapping one slides it up over the player. -->
	<!-- Swipe them up to open, like YTM (a tap works too). -->
	<div
		class="shrink-0 touch-none lg:hidden"
		role="presentation"
		ontouchstart={(e) => (tabsSwipe = { y: e.touches[0].clientY, x: e.touches[0].clientX })}
		ontouchmove={(e) => {
			if (!tabsSwipe) return;
			const dy = tabsSwipe.y - e.touches[0].clientY;
			if (dy > 30 && dy > Math.abs(e.touches[0].clientX - tabsSwipe.x)) {
				// The tab under the finger.
				const label = (document.elementFromPoint(tabsSwipe.x, tabsSwipe.y) as HTMLElement | null)?.closest('[role=tab]');
				const pick = label?.textContent?.trim().toLowerCase() === 'related' ? 'related' : 'queue';
				tabsSwipe = null;
				tab = pick;
				sheet = true;
			}
		}}
		ontouchend={() => (tabsSwipe = null)}
	>
		{@render tabs(true)}
	</div>
	{#if sheet && !wide.current}
		<div
			class="absolute inset-x-0 top-14 bottom-0 z-10 flex flex-col rounded-t-2xl bg-surface shadow-2xl lg:hidden {sheetY ? '' : 'transition-transform'}"
			style:transform={sheetY ? `translateY(${sheetY}px)` : undefined}
			transition:fly={{ y: 600, duration: 200, opacity: 1 }}
			role="presentation"
			ontouchstart={sheetTouchStart}
			ontouchmove={sheetTouchMove}
			ontouchend={sheetTouchEnd}
			ontouchcancel={sheetTouchEnd}
		>
			<button class="flex shrink-0 justify-center pt-2" aria-label="Close" onclick={() => (sheet = false)}>
				<span class="h-1 w-10 rounded-full bg-white/30"></span>
			</button>
			{@render tabs()}
			<div class="min-h-0 flex-1">{@render tabContent()}</div>
		</div>
	{/if}

	{#if showOutputs}
		<OutputsSheet {room} {player} onClose={() => (showOutputs = false)} {onToast} />
	{/if}
</div>

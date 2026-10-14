<script lang="ts">
	// The party screen (docs/features/ui.md#party-screen): the room on a TV, laptop or tablet.
	// Big album art on a background in its colour, the song and its progress, the next songs
	// and who added them, and a QR code and the room code to join. It only watches.
	import { onDestroy, onMount } from 'svelte';
	import { renderSVG } from 'uqr';
	import { artColor } from '../artColor';
	import { artistNames, artUrl, formatTime } from '../format';
	import type { HostInfo, QueueItem } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import { targetPosition } from '../sync';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import Lyrics from './Lyrics.svelte';

	let {
		room,
		host,
		onClose,
		cast = false
	}: {
		room: RoomConnection;
		host: HostInfo | null;
		onClose?: () => void;
		/** On a Chromecast: no mouse, no buttons. */
		cast?: unknown;
	} = $props();

	const s = $derived(room.state);
	const current = $derived(s?.nowPlaying?.item ?? null);
	const song = $derived(current?.song ?? null);
	const upNext = $derived([...(s?.queue ?? []), ...(s?.autoplay ?? [])].slice(0, 4));
	const votes = $derived(s?.skipVotes ?? null);
	// Karaoke mode (a room setting): the lyrics large, instead of the big album art and what's next.
	const karaoke = $derived(!!s?.settings.karaoke && !!song && !song.podcast);

	// The address to join at: the host's address for other devices, or this page's own.
	const loopback = ['127.0.0.1', 'localhost'].includes(location.hostname);
	const base = $derived(host?.shareUrl ?? host?.shareAddresses?.[0]?.url ?? (loopback ? null : location.origin) ?? location.origin);
	const link = $derived(s ? `${base}/room/${s.room.code}` : '');
	const qr = $derived(link ? renderSVG(link, { whiteColor: '#ffffff', blackColor: '#000000', border: 1 }) : '');

	// The background takes its colour from the art.
	let colour = $state<[number, number, number]>([40, 40, 40]);
	$effect(() => {
		const url = song ? artUrl(song, 120) : null;
		if (!url) return;
		artColor(url).then((c) => {
			if (c && song && artUrl(song, 120) === url) colour = c;
		});
	});
	const background = $derived(
		`radial-gradient(120% 120% at 25% 30%, rgb(${colour.map((c) => Math.round(c * 0.75)).join(',')}) 0%, rgb(${colour.map((c) => Math.round(c * 0.25)).join(',')}) 55%, #050505 100%)`
	);

	// The position moves on by itself between the host's updates.
	let now = $state(Date.now());
	const timer = setInterval(() => (now = Date.now()), 250);
	const duration = $derived(song?.durationMs ?? 0);
	const position = $derived.by(() => {
		void now;
		return s ? Math.min(duration, targetPosition(s.playback, room.hostNow(), 0)) : 0;
	});

	const addedBy = (item: QueueItem) => (item.origin === 'autoplay' ? 'Autoplay' : item.addedByName);

	// Controls (full screen, close) and the pointer only show while the mouse moves.
	let active = $state(true);
	let idleTimer: ReturnType<typeof setTimeout> | undefined;
	function wake() {
		active = true;
		clearTimeout(idleTimer);
		idleTimer = setTimeout(() => (active = false), 3000);
	}

	// The newest message (skipped by votes, someone shows the room on a TV), for a few seconds.
	const notice = $derived(room.notices.at(-1) ?? null);

	// Keep the screen on, like a video would.
	let lock: WakeLockSentinel | null = null;
	async function keepAwake() {
		try {
			if (document.visibilityState === 'visible') lock = await navigator.wakeLock?.request('screen');
		} catch {
			// Not allowed (no HTTPS, battery saver): the screen may dim.
		}
	}
	onMount(() => {
		wake();
		keepAwake();
		document.addEventListener('visibilitychange', keepAwake);
	});
	onDestroy(() => {
		clearInterval(timer);
		clearTimeout(idleTimer);
		document.removeEventListener('visibilitychange', keepAwake);
		lock?.release().catch(() => {});
	});

	function fullscreen() {
		if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
		else document.documentElement.requestFullscreen?.().catch(() => {});
	}

	function onKey(event: KeyboardEvent) {
		if (event.key === 'Escape' && !document.fullscreenElement) onClose?.();
		if (event.key === 'f') fullscreen();
	}
</script>

<svelte:window onkeydown={onKey} onmousemove={wake} onpointerdown={wake} />

<div
	class="fixed inset-0 z-[70] flex flex-col overflow-hidden text-white transition-[background] duration-1000 {active ? '' : 'cursor-none'}"
	style:background
	role="presentation"
	aria-label="Party screen"
>
	<!-- Top: the room, and (while the mouse moves) full screen and close. -->
	<div class="flex shrink-0 items-center gap-3 px-[4vw] pt-[3vh] text-[max(14px,2vh)] text-white/60">
		<Icon name="tv" size={20} />
		<span class="truncate">{s?.room.name ?? ''}</span>
		{#if room.status !== 'connected'}<span class="rounded-full bg-black/40 px-3 py-1 text-white/80">Reconnecting…</span>{/if}
		<div class="ml-auto flex gap-2 transition-opacity duration-300 {active && !cast ? 'opacity-100' : 'pointer-events-none opacity-0'}">
			<button class="rounded-full bg-black/30 p-2.5 hover:bg-black/50" aria-label="Full screen" title="Full screen (F)" onclick={fullscreen}>
				<Icon name="fullscreen" />
			</button>
			{#if onClose}
				<button class="rounded-full bg-black/30 p-2.5 hover:bg-black/50" aria-label="Close the party screen" title="Close (Esc)" onclick={onClose}>
					<Icon name="close" />
				</button>
			{/if}
		</div>
	</div>

	{#if notice}
		{#key notice.id}
			<p class="absolute top-[3vh] left-1/2 max-w-[60vw] -translate-x-1/2 truncate rounded-full bg-black/50 px-5 py-2 text-[max(14px,2.2vh)]">{notice.text}</p>
		{/key}
	{/if}

	<!-- Middle: the song (or how to start). -->
	{#if karaoke && song && current}
		<div class="flex min-h-0 flex-1 flex-col gap-[2vh] px-[6vw] py-[2vh]">
			<div class="flex shrink-0 items-center gap-[2vw] portrait:gap-[4vw]">
				<Art {song} size={300} class="aspect-square h-[11vh] shrink-0 rounded-[1vmin] shadow-xl" />
				<div class="min-w-0 flex-1">
					<p class="truncate text-[min(4vh,3vw)] leading-tight font-bold portrait:text-[min(3vh,5.5vw)]">{song.title}</p>
					<p class="truncate text-[min(2.8vh,2.1vw)] text-white/70 portrait:text-[min(2.2vh,4vw)]">{artistNames(song)} · Added by {addedBy(current)}</p>
					{#if duration > 0}
						<div class="mt-[1vh] h-[max(3px,0.5vh)] overflow-hidden rounded-full bg-white/20">
							<div class="h-full rounded-full bg-white transition-[width] duration-[250ms] ease-linear" style:width="{(position / duration) * 100}%"></div>
						</div>
					{/if}
				</div>
			</div>
			<div class="min-h-0 flex-1">
				<Lyrics {room} {song} positionMs={position} large seekable={false} />
			</div>
			{#if votes && votes.voters.length > 0}
				<p class="shrink-0 text-center text-[max(13px,2vh)] text-white/80">{votes.voters.length} of {votes.needed} voted to skip</p>
			{/if}
		</div>
	{:else if song && current}
		<div class="flex min-h-0 flex-1 items-center gap-[5vmin] overflow-hidden px-[6vw] py-[3vh] portrait:flex-col portrait:justify-center">
			<!-- Portrait: the art takes what's left above the song, so nothing overlaps. -->
			<Art {song} size={1200} class="aspect-square h-[min(52vh,40vw)] rounded-[1.5vmin] shadow-2xl portrait:h-auto portrait:max-h-[40vh] portrait:min-h-0 portrait:flex-1" />
			<div class="flex min-w-0 flex-1 flex-col gap-[1.2vh] portrait:w-full portrait:flex-none portrait:text-center">
				<p class="text-[max(13px,1.9vh)] font-medium tracking-[0.2em] text-white/55 uppercase">
					{s?.playback.playing || s?.playback.starting ? 'Now playing' : 'Paused'} · Added by {addedBy(current)}
				</p>
				<h1 class="line-clamp-2 text-[min(7vh,5vw)] leading-tight font-bold portrait:text-[min(4.5vh,7vw)]">{song.title}</h1>
				<p class="truncate text-[min(3.6vh,2.6vw)] text-white/75 portrait:text-[min(2.6vh,4.5vw)]">{artistNames(song)}</p>
				{#if duration > 0}
					<div class="mt-[2vh] h-[max(4px,0.7vh)] overflow-hidden rounded-full bg-white/20">
						<div class="h-full rounded-full bg-white transition-[width] duration-[250ms] ease-linear" style:width="{(position / duration) * 100}%"></div>
					</div>
					<div class="flex justify-between text-[max(13px,1.9vh)] text-white/60 tabular-nums">
						<span>{formatTime(position)}</span><span>{formatTime(duration)}</span>
					</div>
				{/if}
				{#if votes && votes.voters.length > 0}
					<p class="text-[max(13px,2vh)] text-white/80">{votes.voters.length} of {votes.needed} voted to skip</p>
				{/if}
			</div>
		</div>
	{:else}
		<div class="flex min-h-0 flex-1 flex-col items-center justify-center gap-[2vh] px-[6vw] text-center">
			<h1 class="text-[min(7vh,6vw)] font-bold">{s?.room.name ?? 'Party screen'}</h1>
			<p class="text-[min(3.5vh,3vw)] text-white/70 portrait:text-[min(2.6vh,4.5vw)]">Nothing playing yet. Scan the code to add a song.</p>
		</div>
	{/if}

	<!-- Bottom: up next, and how to join. -->
	<div class="flex shrink-0 items-end gap-[3vw] px-[4vw] {karaoke ? 'pb-[2.5vh]' : 'pb-[4vh]'} portrait:flex-col portrait:items-stretch portrait:gap-[2vh]">
		<div class="min-w-0 flex-1">
			{#if upNext.length && !karaoke}
				<p class="mb-[1.5vh] text-[max(13px,1.9vh)] font-medium tracking-[0.2em] text-white/55 uppercase">Up next</p>
				<div class="grid grid-cols-4 gap-[1.5vw] portrait:grid-cols-2 portrait:gap-[2vw] portrait:[&>*:nth-child(n+3)]:hidden">
					{#each upNext as item (item.itemId)}
						<div class="flex min-w-0 items-center gap-[1vw] rounded-[1vmin] bg-black/25 p-[1vmin] portrait:gap-[3vw]">
							<Art song={item.song} size={160} class="aspect-square h-[8vh] rounded-[0.6vmin] portrait:h-[6vh]" />
							<div class="min-w-0 text-[max(12px,1.7vh)]">
								<p class="truncate font-medium">{item.song.title}</p>
								<p class="truncate text-white/60">{artistNames(item.song)}</p>
								<p class="truncate text-white/45">{addedBy(item)}</p>
							</div>
						</div>
					{/each}
				</div>
			{/if}
		</div>
		{#if s}
			<div class="flex shrink-0 items-center gap-[1.5vw] rounded-[1.5vmin] bg-black/35 p-[1.5vmin] portrait:gap-[4vw]">
				<!-- Karaoke: smaller, so the lyrics get more room. -->
				<div
					class="overflow-hidden rounded-[1vmin] bg-white [&>svg]:h-full [&>svg]:w-full {karaoke
						? 'h-[12vh] w-[12vh]'
						: 'h-[18vh] w-[18vh] portrait:h-[14vh] portrait:w-[14vh]'}"
				>
					{@html qr}
				</div>
				<div class="min-w-0 text-[max(13px,1.9vh)]">
					<p class="text-white/70">Scan to add songs</p>
					<p class="mt-[0.5vh] font-mono text-[min(6vh,4vw)] leading-none font-bold tracking-[0.15em] portrait:text-[min(5vh,9vw)]">{s.room.code}</p>
					<p class="mt-[0.8vh] max-w-[22vw] truncate text-white/50 portrait:max-w-[50vw]">{base.replace(/^https?:\/\//, '')}</p>
				</div>
			</div>
		{/if}
	</div>
</div>

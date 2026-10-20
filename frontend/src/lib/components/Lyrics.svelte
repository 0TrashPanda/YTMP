<script lang="ts">
	// A song's lyrics (docs/features/ui.md#lyrics). Timed ones follow the song, with the line
	// being sung highlighted (tap a line to go there); plain ones are text. [large]: karaoke mode,
	// on the party screen and in the full player: big lines that glide up as the song goes, the
	// line being sung bright in the middle; plain lyrics scroll along with the song.
	import { tick } from 'svelte';
	import { getLyrics } from '../api';
	import type { Lyrics, Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';

	let {
		room,
		song,
		positionMs,
		large = false,
		seekable = true
	}: {
		room: RoomConnection;
		song: Song;
		positionMs: number;
		large?: boolean;
		/** False where it only watches (the party screen). */
		seekable?: boolean;
	} = $props();

	/** Undefined while loading; null when nobody has lyrics for the song. */
	let lyrics = $state<Lyrics | null | undefined>(undefined);
	let failed = $state(false);
	$effect(() => {
		const asked = song;
		lyrics = undefined;
		failed = false;
		getLyrics(asked).then(
			(found) => asked.id === song.id && (lyrics = found),
			() => asked.id === song.id && (failed = true)
		);
	});

	/** A line lights up this much before it starts: the position only moves on every 250 ms. */
	const LEAD_MS = 200;
	/** The line being sung: the last one that has started (-1 before the first). */
	const current = $derived.by(() => {
		const lines = lyrics?.synced;
		if (!lines) return -1;
		let index = -1;
		while (index + 1 < lines.length && lines[index + 1].timeMs <= positionMs + LEAD_MS) index++;
		return index;
	});

	const canSeek = $derived(seekable && room.can('seek'));
	function seek(timeMs: number) {
		if (canSeek) room.run({ kind: 'Seek', positionMs: timeMs });
	}

	// The list follows the song, unless you scrolled it yourself just now. It opens at the line
	// being sung, and glides from there.
	let list = $state<HTMLElement>();
	let placedIn: HTMLElement | null = null;
	let scrolledAt = 0;
	const userScrolled = () => (scrolledAt = Date.now());
	$effect(() => {
		const index = current;
		if (!list || large || index < 0 || Date.now() - scrolledAt < 4000 || !list.clientHeight) return;
		const line = list.querySelector<HTMLElement>(`[data-line="${index}"]`);
		if (!line) return;
		list.scrollTo({ top: line.offsetTop - list.clientHeight / 2 + line.offsetHeight / 2, behavior: placedIn === list ? 'smooth' : 'instant' });
		placedIn = list;
	});

	// --- karaoke (large) ---------------------------------------------------------------------
	// Sizes in pixels from the space it has: the party screen runs on a Chromecast's old Chrome,
	// which has no container units. Only transform and opacity move, so it stays smooth there.

	let boxWidth = $state(0);
	let boxHeight = $state(0);
	const fontPx = $derived(Math.round(Math.max(18, Math.min(120, boxHeight * 0.14, boxWidth * 0.09))));
	let column = $state<HTMLElement>();
	/** How far the column of lines is moved up, so the line being sung is in the middle. */
	let offset = $state(0);
	$effect(() => {
		void [current, fontPx, boxHeight, boxWidth, lyrics];
		if (!large) return;
		tick().then(() => {
			if (!column) return;
			if (lyrics?.synced) {
				const line = column.querySelector<HTMLElement>(`[data-line="${Math.max(0, current)}"]`);
				if (line) offset = boxHeight / 2 - (line.offsetTop + line.offsetHeight / 2);
			}
		});
	});
	// Plain lyrics: they scroll through as the song goes.
	const plainOffset = $derived.by(() => {
		if (!large || !column || lyrics?.synced || song.durationMs <= 0) return 0;
		void fontPx;
		const share = Math.min(1, Math.max(0, positionMs / song.durationMs));
		return -share * Math.max(0, column.offsetHeight - boxHeight / 2) + boxHeight / 4;
	});
	/** Lines further from the one being sung fade out. */
	const opacity = (index: number) => (index === current ? 1 : Math.max(0.15, 0.55 - 0.15 * (Math.abs(index - current) - 1)));
	const FADE = 'linear-gradient(to bottom, transparent, #000 20%, #000 80%, transparent)';
</script>

<!-- Karaoke is sized to the space it has. -->
<div class="relative h-full w-full {large ? 'overflow-hidden' : ''}" bind:clientWidth={boxWidth} bind:clientHeight={boxHeight}>
	{#if lyrics === undefined && !failed}
		<p class="p-6 text-center text-muted">Loading lyrics…</p>
	{:else if failed}
		<p class="p-6 text-center text-muted">Couldn't load the lyrics.</p>
	{:else if !lyrics}
		<p class="p-6 text-center text-muted">No lyrics for this song.</p>
	{:else if large}
		<!-- Fades out at the top and bottom (-webkit- for older Chrome, like a Chromecast's). -->
		<div class="absolute inset-0" style:-webkit-mask-image={FADE} style:mask-image={FADE}>
			<div
				bind:this={column}
				class="absolute inset-x-0 top-0 flex flex-col items-center px-[4%] text-center font-bold"
				style:transform="translateY({lyrics.synced ? offset : plainOffset}px)"
				style:transition="transform {lyrics.synced ? 600 : 300}ms {lyrics.synced ? 'cubic-bezier(0.25, 0.8, 0.3, 1)' : 'linear'}"
				style:font-size="{fontPx}px"
			>
				{#if lyrics.synced}
					{#each lyrics.synced as line, i (i)}
						<button
							data-line={i}
							class="block leading-tight {canSeek ? '' : 'cursor-default'}"
							style:padding="{Math.round(fontPx * 0.18)}px 0"
							style:opacity={opacity(i)}
							style:transform="scale({i === current ? 1 : 0.82})"
							style:transition="opacity 500ms ease, transform 500ms ease"
							onclick={() => seek(line.timeMs)}
						>
							{line.text || '♪'}
						</button>
					{/each}
				{:else}
					<p class="leading-snug whitespace-pre-line text-white/85" style:font-size="{Math.round(fontPx * 0.6)}px">{lyrics.plain}</p>
				{/if}
			</div>
		</div>
	{:else}
		<div bind:this={list} role="region" aria-label="Lyrics" class="h-full overflow-y-auto px-4 py-6" onwheel={userScrolled} ontouchmove={userScrolled}>
			{#if lyrics.synced}
				{#each lyrics.synced as line, i (i)}
					<button
						data-line={i}
						class="block w-full py-1.5 text-left text-2xl leading-snug font-bold transition-colors {i === current ? 'text-white' : i < current ? 'text-white/40' : 'text-white/60'} {canSeek
							? 'hover:text-white'
							: 'cursor-default'}"
						onclick={() => seek(line.timeMs)}
					>
						{line.text || '♪'}
					</button>
				{/each}
			{:else}
				<p class="text-lg leading-relaxed font-medium whitespace-pre-line text-white/85">{lyrics.plain}</p>
			{/if}
			<p class="pt-6 pb-2 text-xs text-muted">Lyrics: {lyrics.source}</p>
		</div>
	{/if}
</div>

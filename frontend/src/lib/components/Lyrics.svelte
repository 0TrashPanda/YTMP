<script lang="ts">
	// A song's lyrics (docs/features/ui.md#lyrics). Timed ones follow the song, with the line
	// being sung highlighted (tap a line to go there); plain ones are text. [large]: karaoke mode,
	// on the party screen and in the full player: the line being sung big, with the line before
	// and the next ones; plain lyrics scroll along with the song.
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

	// The list follows the song, unless you scrolled it yourself just now.
	let list = $state<HTMLElement>();
	let scrolledAt = 0;
	const userScrolled = () => (scrolledAt = Date.now());
	$effect(() => {
		const index = current;
		if (!list || large || index < 0 || Date.now() - scrolledAt < 4000) return;
		const line = list.querySelector<HTMLElement>(`[data-line="${index}"]`);
		if (line) list.scrollTo({ top: line.offsetTop - list.clientHeight / 2 + line.offsetHeight / 2, behavior: 'smooth' });
	});

	// Karaoke with plain lyrics: they scroll through as the song goes.
	$effect(() => {
		if (!list || !large || !lyrics || lyrics.synced || song.durationMs <= 0) return;
		const share = Math.min(1, Math.max(0, positionMs / song.durationMs));
		list.scrollTop = share * (list.scrollHeight - list.clientHeight);
	});

	/** Karaoke: the line before, the line being sung, and the next two. */
	const shown = $derived.by(() => {
		const lines = lyrics?.synced ?? [];
		const from = Math.max(0, current - 1);
		return lines.slice(from, from + (current < 0 ? 3 : 4)).map((line, i) => ({ ...line, index: from + i }));
	});
</script>

<!-- Karaoke is sized to the space it has (container units), on a TV as in the player. -->
<div class="h-full w-full {large ? '[container-type:size]' : ''}">
	{#if lyrics === undefined && !failed}
		<p class="p-6 text-center text-muted">Loading lyrics…</p>
	{:else if failed}
		<p class="p-6 text-center text-muted">Couldn't load the lyrics.</p>
	{:else if !lyrics}
		<p class="p-6 text-center text-muted">No lyrics for this song.</p>
	{:else if large && lyrics.synced}
		<div class="flex h-full w-full flex-col justify-center gap-[4cqh] overflow-hidden text-center">
			{#each shown as line (line.index)}
				<button
					class="leading-tight font-bold transition-all duration-300 {line.index === current
						? 'text-[min(16cqh,9cqw)] text-white'
						: line.index < current
							? 'text-[min(8cqh,5.5cqw)] text-white/30'
							: 'text-[min(9cqh,6cqw)] text-white/55'} {canSeek ? '' : 'cursor-default'}"
					onclick={() => seek(line.timeMs)}
				>
					{line.text || '♪'}
				</button>
			{/each}
		</div>
	{:else}
		<div
			bind:this={list}
			role="region"
			aria-label="Lyrics"
			class="h-full overflow-y-auto {large ? 'px-[4cqw] text-center text-[min(5cqh,4cqw)]' : 'px-4 py-6'}"
			onwheel={userScrolled}
			ontouchmove={userScrolled}
		>
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
				<p class="leading-relaxed font-medium whitespace-pre-line {large ? 'py-[40cqh] text-white/85' : 'text-lg text-white/85'}">{lyrics.plain}</p>
			{/if}
			{#if !large}
				<p class="pt-6 pb-2 text-xs text-muted">Lyrics: {lyrics.source}</p>
			{/if}
		</div>
	{/if}
</div>

<script lang="ts">
	// Recognize a song (docs/features/ui.md), like YouTube Music's song search: the
	// microphone listens (listen.ts), and the song shows with Play, Play next and Add to queue.
	// Recognized but not on YouTube Music: a search for it. Closing stops the microphone.
	import { onDestroy } from 'svelte';
	import { listen, type Heard } from '../listen';
	import type { RoomConnection } from '../room.svelte';
	import { addToQueue, playNext, playNow, quoted } from '../songActions';
	import { artistNames } from '../format';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';

	let {
		room,
		onClose,
		onToast,
		onSearch
	}: { room: RoomConnection; onClose: () => void; onToast: (text: string) => void; onSearch: (query: string) => void } = $props();

	let heard = $state<Heard | null>(null);
	let stopper: AbortController | null = null;

	function start() {
		stopper?.abort();
		const mine = (stopper = new AbortController());
		heard = null;
		listen(mine.signal).then((h) => {
			if (!mine.signal.aborted) heard = h;
		});
	}
	start();
	onDestroy(() => stopper?.abort());

	const song = $derived(heard?.kind === 'song' ? heard.answer.song : null);

	function act(what: 'play' | 'next' | 'queue') {
		if (!song) return;
		if (what === 'play') playNow(room, song, onToast);
		else if (what === 'next') playNext(room, [song], quoted(song), onToast);
		else addToQueue(room, [song], quoted(song), onToast);
		onClose();
	}
</script>

<div class="fixed inset-0 z-[60] flex items-end justify-center bg-black/70 sm:items-center" role="presentation" onclick={onClose}>
	<div
		class="flex w-full max-w-sm flex-col items-center gap-5 rounded-t-2xl bg-surface p-6 pb-8 text-center ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label="Recognize a song"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		onkeydown={(e) => e.key === 'Escape' && onClose()}
	>
		{#if !heard}
			<!-- Listening: a pulsing microphone. -->
			<div class="relative my-4 grid h-28 w-28 place-items-center">
				<span class="absolute inset-0 animate-ping rounded-full bg-accent/30"></span>
				<span class="relative grid h-24 w-24 place-items-center rounded-full bg-accent text-white">
					<Icon name="mic" size={44} />
				</span>
			</div>
			<div>
				<p class="text-lg font-semibold">Listening…</p>
				<p class="mt-1 text-sm text-muted">Hold your device near the music</p>
			</div>
			<button class="rounded-full px-5 py-2 font-medium hover:bg-raised" onclick={onClose}>Cancel</button>
		{:else if song}
			<Art {song} size={300} class="h-48 w-48 rounded-lg shadow-2xl" />
			<div class="min-w-0 max-w-full">
				<p class="truncate text-lg font-semibold">{song.title}</p>
				<p class="truncate text-sm text-muted">{artistNames(song)}</p>
			</div>
			<div class="flex w-full flex-col gap-2">
				<button class="flex items-center justify-center gap-2 rounded-full bg-white py-3 font-medium text-black" onclick={() => act('play')}>
					<Icon name="play" size={22} /> Play
				</button>
				<div class="flex gap-2">
					<button class="flex flex-1 items-center justify-center gap-2 rounded-full bg-raised py-2.5 font-medium hover:bg-line" onclick={() => act('next')}>Play next</button>
					<button class="flex flex-1 items-center justify-center gap-2 rounded-full bg-raised py-2.5 font-medium hover:bg-line" onclick={() => act('queue')}>Add to queue</button>
				</div>
			</div>
			<button class="text-sm text-muted hover:text-white" onclick={start}>Listen again</button>
		{:else if heard.kind === 'song'}
			{@const query = `${heard.answer.title} ${heard.answer.artist ?? ''}`.trim()}
			<Icon name="note" size={48} class="mt-2 text-muted" />
			<div>
				<p class="text-lg font-semibold">{heard.answer.title}</p>
				<p class="text-sm text-muted">{heard.answer.artist}</p>
				<p class="mt-2 text-sm text-muted">Not found on YouTube Music as it is.</p>
			</div>
			<button class="rounded-full bg-white px-6 py-3 font-medium text-black" onclick={() => (onSearch(query), onClose())}>Search for it</button>
		{:else}
			<Icon name={heard.kind === 'error' ? 'mic' : 'note'} size={48} class="mt-2 text-muted" />
			<div>
				<p class="text-lg font-semibold">{heard.kind === 'error' ? "Couldn't listen" : "Couldn't recognize this song"}</p>
				<p class="mt-1 text-sm text-muted">{heard.kind === 'error' ? heard.message : 'Try again closer to the music, where it is louder.'}</p>
			</div>
			<div class="flex gap-2">
				<button class="rounded-full px-5 py-2 font-medium hover:bg-raised" onclick={onClose}>Close</button>
				<button class="rounded-full bg-white px-5 py-2 font-medium text-black" onclick={start}>Try again</button>
			</div>
		{/if}
	</div>
</div>

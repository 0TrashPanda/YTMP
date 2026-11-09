<script lang="ts">
	// Recognize a song (docs/features/ui.md), like YouTube Music's song search: the microphone
	// listens (listen.ts). Shazam's answer shows at once (its cover, title and artist), and the
	// buttons come when the song is found on YouTube Music (Play, Play next, Add to queue);
	// not there: a search for it. Closing stops the microphone.
	import { onDestroy } from 'svelte';
	import { recognizedSong } from '../api';
	import { listen, type Heard } from '../listen';
	import type { Song } from '../protocol.gen';
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
	/** The song on YouTube Music: undefined while looking, null when it isn't there. */
	let song = $state<Song | null | undefined>(undefined);
	let stopper: AbortController | null = null;

	function start() {
		stopper?.abort();
		const mine = (stopper = new AbortController());
		heard = null;
		song = undefined;
		listen(mine.signal).then((h) => {
			if (mine.signal.aborted) return;
			heard = h;
			if (h.kind === 'found')
				recognizedSong(h.answer).then(
					(s) => !mine.signal.aborted && (song = s),
					() => !mine.signal.aborted && (song = null)
				);
		});
	}
	start();
	onDestroy(() => stopper?.abort());

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
		{:else if heard.kind === 'found'}
			{@const answer = heard.answer}
			<!-- YouTube Music's cover once the song is found there; Shazam's until then. -->
			{#if song}
				<Art {song} size={300} class="h-48 w-48 rounded-lg shadow-2xl" />
			{:else if answer.art}
				<img src={answer.art} alt="" referrerpolicy="no-referrer" class="h-48 w-48 rounded-lg object-cover shadow-2xl" />
			{:else}
				<div class="grid h-48 w-48 place-items-center rounded-lg bg-raised text-muted"><Icon name="note" size={64} /></div>
			{/if}
			<div class="min-w-0 max-w-full">
				<p class="truncate text-lg font-semibold">{song?.title ?? answer.title}</p>
				<p class="truncate text-sm text-muted">{song ? artistNames(song) : answer.artist}</p>
			</div>
			<!-- As high as the buttons, so the sheet doesn't jump when they come. -->
			<div class="flex min-h-[6.75rem] w-full flex-col items-center justify-center gap-2">
			{#if song === undefined}
				<p class="text-sm text-muted">Finding it on YouTube Music…</p>
			{:else if song}
				<div class="flex w-full flex-col gap-2">
					<button class="flex items-center justify-center gap-2 rounded-full bg-white py-3 font-medium text-black" onclick={() => act('play')}>
						<Icon name="play" size={22} /> Play
					</button>
					<div class="flex gap-2">
						<button class="flex flex-1 items-center justify-center gap-2 rounded-full bg-raised py-2.5 font-medium hover:bg-line" onclick={() => act('next')}>Play next</button>
						<button class="flex flex-1 items-center justify-center gap-2 rounded-full bg-raised py-2.5 font-medium hover:bg-line" onclick={() => act('queue')}>Add to queue</button>
					</div>
				</div>
			{:else}
				<p class="text-sm text-muted">Not found on YouTube Music as it is.</p>
				<button class="rounded-full bg-white px-6 py-3 font-medium text-black" onclick={() => (onSearch(`${answer.title} ${answer.artist ?? ''}`.trim()), onClose())}>Search for it</button>
			{/if}
			</div>
			<button class="text-sm text-muted hover:text-white" onclick={start}>Listen again</button>
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

<script lang="ts">
	// The sleep timer (docs/features/playback-and-outputs.md#sleep-timer), like YouTube Music's:
	// a few lengths, your own, or the end of the song; while it runs, how long is left and Turn off.
	import { onDestroy } from 'svelte';
	import type { RoomConnection } from '../room.svelte';
	import type { RoomPlayer } from '../player.svelte';
	import { sleepTimer } from '../sleepTimer.svelte';
	import Icon from './Icon.svelte';

	let { room, player, onClose, onToast }: { room: RoomConnection; player: RoomPlayer | null; onClose: () => void; onToast: (text: string) => void } = $props();

	const LENGTHS = [5, 10, 15, 30, 45, 60];

	let custom = $state('');
	let now = $state(Date.now());
	const clock = setInterval(() => (now = Date.now()), 1000);
	onDestroy(() => clearInterval(clock));

	const solo = $derived(room.state?.room.visibility === 'private');
	const current = $derived(room.state?.nowPlaying?.item ?? null);
	const episode = $derived(!!current?.song.podcast);
	/** In a shared room it can only stop this device: it has to be playing here. */
	const usable = $derived(solo || !!player?.enabled);
	const left = $derived(sleepTimer.minutesLeft(now));

	function what(): string {
		return solo ? 'the room pauses' : 'this device stops playing';
	}

	function start(when: number | 'song') {
		sleepTimer.set(when);
		onToast(when === 'song' ? `At the end of this ${episode ? 'episode' : 'song'}, ${what()}` : `In ${duration(when)}, ${what()}`);
		onClose();
	}

	function startCustom(event: SubmitEvent) {
		event.preventDefault();
		const minutes = Math.round(Number(custom));
		if (minutes > 0 && minutes <= 24 * 60) start(minutes);
	}

	function duration(minutes: number): string {
		if (minutes < 60) return `${minutes} min`;
		const hours = Math.floor(minutes / 60);
		const rest = minutes % 60;
		return rest ? `${hours} h ${rest} min` : hours === 1 ? '1 hour' : `${hours} hours`;
	}
</script>

<div class="fixed inset-0 z-[60] flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={onClose}>
	<div
		class="flex max-h-[80vh] w-full max-w-sm flex-col rounded-t-2xl bg-surface ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label="Sleep timer"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		onkeydown={(e) => e.key === 'Escape' && onClose()}
	>
		<div class="flex items-center gap-3 border-b border-line p-4">
			<Icon name="sleep" size={22} class="text-muted" />
			<div class="min-w-0 flex-1">
				<p class="font-medium">Sleep timer</p>
				<p class="text-xs text-muted">
					{#if sleepTimer.active}
						{left === null ? `Stops at the end of this ${episode ? 'episode' : 'song'}` : `Stops in ${duration(Math.max(1, left))}`}
					{:else}
						The sound fades out, then {what()}{solo ? '' : ' (the room plays on for the others)'}.
					{/if}
				</p>
			</div>
			<button class="rounded-full p-1 text-muted hover:bg-raised" aria-label="Close" onclick={onClose}><Icon name="close" /></button>
		</div>

		<div class="min-h-0 flex-1 overflow-y-auto py-2">
			{#if sleepTimer.active}
				<button class="flex w-full items-center gap-3 px-4 py-3 text-left hover:bg-raised" onclick={() => (sleepTimer.cancel(), onToast('Sleep timer off'), onClose())}>
					<Icon name="close" size={22} class="text-muted" />
					<span class="font-medium">Turn off the timer</span>
				</button>
				<div class="mx-4 my-1 border-t border-line"></div>
			{/if}
			{#if !usable}
				<p class="px-4 py-2 text-sm text-muted">In a room with others the timer only stops this device. Turn on this device under <em>Play on</em> first.</p>
			{:else}
				{#each LENGTHS as minutes (minutes)}
					<button class="flex w-full items-center px-4 py-3 text-left hover:bg-raised" onclick={() => start(minutes)}>{duration(minutes)}</button>
				{/each}
				{#if current}
					<button class="flex w-full items-center px-4 py-3 text-left hover:bg-raised" onclick={() => start('song')}>End of {episode ? 'episode' : 'song'}</button>
				{/if}
				<form class="flex items-center gap-2 px-4 py-2" onsubmit={startCustom}>
					<input
						class="w-24 min-w-0 rounded-lg bg-raised px-3 py-2 outline-none ring-white/40 focus:ring-2"
						type="number"
						inputmode="numeric"
						min="1"
						max="1440"
						bind:value={custom}
						placeholder="90"
						aria-label="Minutes"
					/>
					<span class="flex-1 text-sm text-muted">minutes</span>
					<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-40" disabled={!(Number(custom) > 0)}>Start</button>
				</form>
			{/if}
		</div>
	</div>
</div>

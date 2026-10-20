<script lang="ts">
	// The speed podcast episodes play at, for everyone in the room (music always plays at 1×).
	// Shows the speed; a tap opens the speeds to pick from, like YouTube Music's.
	import type { RoomConnection } from '../room.svelte';

	let { room, onToast, class: className = '', up = false }: { room: RoomConnection; onToast: (text: string) => void; class?: string; up?: boolean } = $props();

	/** The same as EPISODE_SPEEDS in protocol/.../Models.kt. */
	const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];
	const speed = $derived(room.state?.playback.speed ?? 1);
	let open = $state(false);

	const label = (s: number) => `${s}×`;

	async function pick(s: number) {
		open = false;
		if (s === speed) return;
		const error = await room.run({ kind: 'SetSpeed', speed: s });
		if (error) onToast(error.message);
	}
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && (open = false)} />

<div class="relative">
	<button
		class="min-w-12 rounded-full px-2 py-1.5 text-sm font-semibold tabular-nums hover:bg-raised disabled:opacity-40 {speed !== 1 ? 'text-accent' : ''} {className}"
		disabled={!room.can('play_pause')}
		aria-label="Playback speed: {label(speed)}"
		aria-expanded={open}
		title="Playback speed"
		onclick={() => (open = !open)}
	>
		{label(speed)}
	</button>
	{#if open}
		<div class="fixed inset-0 z-[55]" role="presentation" onclick={() => (open = false)}></div>
		<div class="absolute left-1/2 z-[56] w-32 -translate-x-1/2 overflow-hidden rounded-lg bg-raised py-1 shadow-2xl ring-1 ring-line {up ? 'bottom-full mb-2' : 'top-full mt-2'}" role="menu">
			<div class="px-4 pt-1 pb-2 text-xs text-muted">Speed</div>
			{#each SPEEDS as s (s)}
				<button role="menuitemradio" aria-checked={s === speed} class="flex w-full items-center justify-between px-4 py-2 text-left text-sm hover:bg-line {s === speed ? 'font-bold text-accent' : ''}" onclick={() => pick(s)}>
					{label(s)}
					{#if s === 1}<span class="text-xs text-muted">Normal</span>{/if}
				</button>
			{/each}
		</div>
	{/if}
</div>

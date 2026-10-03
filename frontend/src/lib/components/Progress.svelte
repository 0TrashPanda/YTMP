<script lang="ts">
	// The position of the current song, draggable to seek. [times]: elapsed and total below it (the full player).
	import { formatTime } from '../format';
	import type { RoomConnection } from '../room.svelte';

	let { room, positionMs, times = false, class: className = '' }: { room: RoomConnection; positionMs: number; times?: boolean; class?: string } = $props();

	const current = $derived(room.state?.nowPlaying ?? null);
	const duration = $derived(current?.item.song.durationMs ?? 0);

	// While dragging, show the drag position instead of the live one.
	let seeking = $state<number | null>(null);
	const shown = $derived(seeking ?? Math.min(positionMs, duration));
</script>

<div class={className}>
	<input
		type="range"
		class="block w-full cursor-pointer appearance-none accent-accent {times ? 'h-1 rounded-full' : 'h-1'}"
		min="0"
		max={duration || 1}
		value={shown}
		disabled={!current || !room.can('seek')}
		aria-label="Position"
		oninput={(e) => (seeking = Number(e.currentTarget.value))}
		onchange={(e) => {
			room.run({ kind: 'Seek', positionMs: Number(e.currentTarget.value) });
			seeking = null;
		}}
		style="background: linear-gradient(to right, var(--color-accent) {(shown / (duration || 1)) * 100}%, {times ? 'rgb(255 255 255 / 0.2)' : 'var(--color-line)'} 0)"
	/>
	{#if times}
		<div class="mt-1.5 flex justify-between text-xs text-muted tabular-nums">
			<span>{formatTime(shown)}</span>
			<span>{duration ? formatTime(duration) : ''}</span>
		</div>
	{/if}
</div>

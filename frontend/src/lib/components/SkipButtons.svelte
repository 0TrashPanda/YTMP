<script lang="ts">
	// Back 10 s and ahead 30 s, for podcast episodes. [children] goes between them (play/pause).
	import type { Snippet } from 'svelte';
	import type { RoomConnection } from '../room.svelte';
	import Icon from './Icon.svelte';

	let { room, positionMs, size = 28, children }: { room: RoomConnection; positionMs: number; size?: number; children?: Snippet } = $props();

	const BACK_MS = 10_000;
	const AHEAD_MS = 30_000;

	function skip(deltaMs: number) {
		// The host keeps it within the episode.
		room.run({ kind: 'Seek', positionMs: Math.max(0, Math.round(positionMs + deltaMs)) });
	}
</script>

{#snippet button(deltaMs: number, label: string)}
	<button
		class="relative rounded-full p-2 hover:bg-raised disabled:opacity-40"
		disabled={!room.can('seek')}
		aria-label={label}
		title={label}
		onclick={() => skip(deltaMs)}
	>
		<!-- Ahead is the same arrow, mirrored. -->
		<Icon name="replay" {size} class={deltaMs > 0 ? '-scale-x-100' : ''} />
		<span class="absolute inset-0 flex items-center justify-center pt-[12%] text-[0.6rem] font-bold">{Math.abs(deltaMs) / 1000}</span>
	</button>
{/snippet}

<div class="flex items-center">
	{@render button(-BACK_MS, 'Back 10 seconds')}
	{@render children?.()}
	{@render button(AHEAD_MS, 'Ahead 30 seconds')}
</div>

<script lang="ts">
	// Shuffle, a one-time action like YTM's: the upcoming songs in a random order (with fair order, keeping the rounds).
	import type { RoomConnection } from '../room.svelte';
	import Icon from './Icon.svelte';

	let { room, onToast, size = 24, class: className = '' }: { room: RoomConnection; onToast: (text: string) => void; size?: number; class?: string } = $props();
</script>

<button
	class="rounded-full disabled:opacity-40 {className}"
	aria-label="Shuffle the queue"
	title="Shuffle the queue"
	disabled={!room.can('reorder') || (room.state?.queue.length ?? 0) < 2}
	onclick={async (e) => {
		e.stopPropagation();
		const error = await room.run({ kind: 'ShuffleQueue' });
		onToast(error ? error.message : 'Queue shuffled');
	}}
>
	<Icon name="shuffle" {size} />
</button>

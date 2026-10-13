<script lang="ts">
	// Repeat, like YTM: off, then the queue, then one song (pressing again goes round).
	import type { RepeatMode } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Icon from './Icon.svelte';

	let { room, size = 24, class: className = '' }: { room: RoomConnection; size?: number; class?: string } = $props();

	const mode = $derived<RepeatMode>(room.state?.repeat ?? 'off');
	const next: Record<RepeatMode, RepeatMode> = { off: 'queue', queue: 'one', one: 'off' };
	const labels: Record<RepeatMode, string> = { off: 'Repeat off', queue: 'Repeat queue', one: 'Repeat one' };
</script>

<button
	class="rounded-full disabled:opacity-40 {mode === 'off' ? '' : 'text-accent'} {className}"
	aria-label={labels[mode]}
	title={labels[mode]}
	disabled={!room.can('repeat')}
	onclick={(e) => {
		e.stopPropagation();
		room.run({ kind: 'SetRepeat', mode: next[mode] });
	}}
>
	<Icon name={mode === 'one' ? 'repeatOne' : 'repeat'} {size} />
</button>

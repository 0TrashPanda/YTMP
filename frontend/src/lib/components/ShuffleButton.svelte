<script lang="ts">
	// Shuffle, a one-time action like YTM's: the upcoming songs in a random order (with fair order, keeping the rounds).
	// Held: shuffle the autoplay queue too (Settings → Song menu → Holding).
	import { hold } from '../hold';
	import type { RoomConnection } from '../room.svelte';
	import { holds, shownChoices } from '../songMenu.svelte';
	import HoldChoices from './HoldChoices.svelte';
	import Icon from './Icon.svelte';

	let { room, onToast, size = 24, class: className = '' }: { room: RoomConnection; onToast: (text: string) => void; size?: number; class?: string } = $props();

	let choosing = $state(false);
	const choices = $derived(
		[
			{ id: 'queue', icon: 'shuffle' as const, label: 'Shuffle the queue', run: () => void shuffle(false) },
			{ id: 'autoplay', icon: 'autoplay' as const, label: 'Shuffle the queue and autoplay', run: () => void shuffle(true) }
		].filter((c) => shownChoices('shuffle').includes(c.id))
	);

	async function shuffle(withAutoplay: boolean) {
		const error = (room.state?.queue.length ?? 0) >= 2 ? await room.run({ kind: 'ShuffleQueue' }) : null;
		const second = withAutoplay && !error ? await room.run({ kind: 'ShuffleAutoplay' }) : null;
		onToast(error?.message ?? second?.message ?? (withAutoplay ? 'Queue and autoplay shuffled' : 'Queue shuffled'));
	}
</script>

{#if choosing}
	<HoldChoices title="Shuffle" {choices} onClose={() => (choosing = false)} />
{/if}

<button
	class="rounded-full disabled:opacity-40 {className}"
	aria-label="Shuffle the queue"
	title="Shuffle the queue"
	disabled={!room.can('reorder') || ((room.state?.queue.length ?? 0) < 2 && (room.state?.autoplay.length ?? 0) < 2)}
	use:hold={holds('shuffle') && choices.length && room.can('autoplay_from_here') ? () => (choosing = true) : null}
	onclick={async (e) => {
		e.stopPropagation();
		if ((room.state?.queue.length ?? 0) < 2) return onToast('Hold Shuffle to shuffle autoplay');
		const error = await room.run({ kind: 'ShuffleQueue' });
		onToast(error ? error.message : 'Queue shuffled');
	}}
>
	<Icon name="shuffle" {size} />
</button>

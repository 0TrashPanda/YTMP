<script lang="ts">
	// Next: skips, or, when the room votes to skip and you may only vote, votes (pressed again,
	// takes the vote back). Then it shows the votes so far, like "1/3".
	import type { RoomConnection } from '../room.svelte';
	import Icon from './Icon.svelte';

	let { room, size = 24, class: className = '' }: { room: RoomConnection; size?: number; class?: string } = $props();

	const mode = $derived(room.skipMode);
	const votes = $derived(room.state?.skipVotes ?? null);
	const voted = $derived(room.votedToSkip);
	const label = $derived(
		mode !== 'vote' || !votes ? 'Next' : voted ? `Take back your vote to skip (${votes.voters.length} of ${votes.needed})` : `Vote to skip (${votes.voters.length} of ${votes.needed})`
	);
</script>

<button
	class="relative rounded-full disabled:opacity-40 {voted ? 'text-accent' : ''} {className}"
	aria-label={label}
	title={mode === 'vote' ? label : undefined}
	aria-pressed={mode === 'vote' ? voted : undefined}
	disabled={mode === 'none' || !room.state?.nowPlaying}
	onclick={(e) => {
		e.stopPropagation();
		room.next();
	}}
>
	<Icon name="next" {size} />
	{#if mode === 'vote' && votes && votes.voters.length > 0}
		<span class="absolute -right-1 -bottom-0.5 rounded-full bg-raised px-1 text-[10px] leading-4 font-medium text-white tabular-nums">
			{votes.voters.length}/{votes.needed}
		</span>
	{/if}
</button>

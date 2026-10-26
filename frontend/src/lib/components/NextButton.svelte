<script lang="ts">
	// Next: skips, or, when the room votes to skip and you may only vote, votes (pressed again,
	// takes the vote back). Then it shows the votes so far, like "1/3".
	// Held: skip to the next song from someone else, or past this album (Settings → Song menu → Holding).
	import { hold } from '../hold';
	import type { RoomConnection } from '../room.svelte';
	import { holds, shownChoices } from '../songMenu.svelte';
	import HoldChoices from './HoldChoices.svelte';
	import Icon from './Icon.svelte';

	let { room, size = 24, class: className = '' }: { room: RoomConnection; size?: number; class?: string } = $props();

	let choosing = $state(false);
	/** The first song in the queue that [differs] from the one playing: jumped to. */
	const firstOther = (differs: (addedBy: string, album: string | null | undefined) => boolean) => {
		const now = room.state?.nowPlaying?.item;
		if (!now) return null;
		return room.state?.queue.find((i) => differs(i.addedBy, i.song.album?.name)) ?? null;
	};
	const choices = $derived.by(() => {
		const now = room.state?.nowPlaying?.item;
		const list = [];
		const party = (room.state?.participants.filter((p) => p.online && !p.screen).length ?? 0) > 1;
		const other = firstOther((by) => by !== now?.addedBy);
		if (party && other) list.push({ id: 'other', icon: 'people' as const, label: `Next song from someone else (${other.addedByName})`, run: () => void room.run({ kind: 'JumpTo', itemId: other.itemId }) });
		const album = now?.song.album?.name;
		const after = album ? firstOther((_, a) => a !== album) : null;
		if (after) list.push({ id: 'album', icon: 'album' as const, label: `Skip to after ${album}`, run: () => void room.run({ kind: 'JumpTo', itemId: after.itemId }) });
		return list.filter((c) => shownChoices('next').includes(c.id));
	});

	const mode = $derived(room.skipMode);
	const votes = $derived(room.state?.skipVotes ?? null);
	const voted = $derived(room.votedToSkip);
	const label = $derived(
		mode !== 'vote' || !votes ? 'Next' : voted ? `Take back your vote to skip (${votes.voters.length} of ${votes.needed})` : `Vote to skip (${votes.voters.length} of ${votes.needed})`
	);
</script>

{#if choosing}
	<HoldChoices title="Skip to" {choices} onClose={() => (choosing = false)} />
{/if}

<button
	class="relative rounded-full disabled:opacity-40 {voted ? 'text-accent' : ''} {className}"
	aria-label={label}
	title={mode === 'vote' ? label : undefined}
	aria-pressed={mode === 'vote' ? voted : undefined}
	disabled={mode === 'none' || !room.state?.nowPlaying}
	use:hold={mode === 'skip' && holds('next') && choices.length && room.can('play_now') ? () => (choosing = true) : null}
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

<script lang="ts">
	// "Pause for everyone?": in a party room, what would interrupt the music for the others
	// (pause, seek, skip, play something else) waits for this (room.asking). Always runs it and
	// doesn't ask again (Settings → Playback switches it back on).
	import type { RoomConnection } from '../room.svelte';
	import { settings } from '../settings.svelte';

	let { room }: { room: RoomConnection } = $props();

	const asking = $derived(room.asking!);
	const title = $derived(
		({
			Pause: 'Pause for everyone?',
			Seek: 'Jump to another point for everyone?',
			Skip: 'Skip this song for everyone?',
			Previous: 'Go back for everyone?'
		} as Record<string, string>)[asking.command.kind] ?? 'Play this now for everyone?'
	);
	const others = $derived.by(() => {
		const s = room.state;
		if (!s) return 0;
		return s.participants.filter((p) => p.id !== room.participantId && p.online && (p.listening || p.speakers.length > 0)).length;
	});

	function always() {
		settings.askInParty.set(false);
		asking.answer(true);
	}
</script>

<div class="fixed inset-0 z-[90] flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={() => asking.answer(false)}>
	<div
		class="flex w-full max-w-sm flex-col gap-4 rounded-t-2xl bg-surface p-6 ring-1 ring-line sm:rounded-2xl"
		role="alertdialog"
		aria-label={title}
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		onkeydown={(e) => e.key === 'Escape' && asking.answer(false)}
	>
		<h2 class="text-lg font-bold">{title}</h2>
		<p class="text-sm text-muted">
			{others === 1 ? 'Someone else is' : others > 1 ? `${others} others are` : 'Others are'} listening along. Always does it and stops asking; you can switch asking back on in Settings → Playback.
		</p>
		<div class="flex justify-end gap-2">
			<button class="rounded-full px-4 py-2 text-sm font-medium hover:bg-raised" onclick={() => asking.answer(false)}>No</button>
			<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={() => asking.answer(true)}>Just once</button>
			<button class="rounded-full bg-accent px-4 py-2 text-sm font-medium" onclick={always}>Always</button>
		</div>
	</div>
</div>

<script lang="ts">
	// Leave Room. The owner of a party room picks who runs it after them (the person with the
	// top role who's been here longest is suggested), or nobody (they stay the owner).
	// Everyone else, and a solo room's owner, just leave.
	import { goto } from '$app/navigation';
	import type { RoomConnection } from '../room.svelte';

	let { room, onClose }: { room: RoomConnection; onClose: () => void } = $props();

	const rs = $derived(room.state!);
	const me = $derived(room.me);
	const others = $derived(rs.participants.filter((p) => p.id !== me?.id && !p.screen));
	const asks = $derived(!!me?.isOwner && rs.room.visibility === 'public' && others.length > 0);
	/** Like the host picks when you don't: the top role, here, the longest (the list is in the order people came). */
	const suggested = $derived(others.find((p) => p.online && p.roleId === rs.roles[0]?.id) ?? null);
	// svelte-ignore state_referenced_locally
	let nextOwner = $state(suggested?.id ?? '');
	let busy = $state(false);

	/** [newOwnerId]: who takes over; null: nobody, I stay the owner and can come back. */
	async function leave(newOwnerId: string | null) {
		busy = true;
		await room.run({ kind: 'LeaveRoom', newOwnerId, stayOwner: !newOwnerId });
		goto('/');
	}

	const input = 'w-full rounded-lg bg-raised px-3 py-2.5 outline-none';
</script>

<div class="fixed inset-0 z-[70] flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={onClose}>
	<div
		class="flex w-full max-w-sm flex-col gap-4 rounded-t-2xl bg-surface p-6 ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label="Leave room"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		onkeydown={(e) => e.key === 'Escape' && onClose()}
	>
		<h2 class="text-lg font-bold">Leave '{rs.room.name}'?</h2>
		{#if asks}
			<p class="text-sm text-muted">You're the owner. Who runs the room after you? They can change the settings, roles and who's in it.</p>
			<select class={input} bind:value={nextOwner}>
				{#each others as person (person.id)}
					<option value={person.id}>{person.name}{person.online ? '' : ' (away)'}{person.roleId === rs.roles[0]?.id ? ` · ${rs.roles[0].name}` : ''}</option>
				{/each}
				<option value="">Nobody: I stay the owner and can come back</option>
			</select>
		{:else if me?.isOwner}
			<p class="text-sm text-muted">It stays yours: you can open it again from the room list.</p>
		{:else}
			<p class="text-sm text-muted">Your songs stay in the queue. You can join again with the code, as someone new.</p>
		{/if}
		<div class="flex justify-end gap-2">
			<button class="rounded-full px-4 py-2 text-sm font-medium hover:bg-raised" onclick={onClose}>Cancel</button>
			<button class="rounded-full bg-accent px-4 py-2 text-sm font-medium disabled:opacity-50" disabled={busy} onclick={() => leave(asks ? nextOwner || null : null)}>
				Leave room
			</button>
		</div>
	</div>
</div>

<script lang="ts">
	// "Show on a TV": the TV opens <host>/tv and shows a code; entering it here makes the TV
	// this room's party screen (docs/features/ui.md#party-screen).
	import { linkScreen } from '../api';
	import type { HostInfo } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import { saved } from '../storage';

	let { room, host, onClose, onToast }: { room: RoomConnection; host: HostInfo | null; onClose: () => void; onToast: (text: string) => void } = $props();

	const input = 'rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2';
	// Where the TV goes: the host's address for other devices (on the phone itself, its Wi-Fi address).
	const loopback = ['127.0.0.1', 'localhost'].includes(location.hostname);
	const address = $derived(
		(host?.shareUrl ?? host?.shareAddresses?.[0]?.url ?? (loopback ? null : location.origin) ?? location.origin).replace(/^https?:\/\//, '') + '/tv'
	);
	let code = $state('');
	let busy = $state(false);
	let problem = $state<string | null>(null);
	const digits = $derived(code.replace(/\D/g, ''));

	async function link(event: SubmitEvent) {
		event.preventDefault();
		const guestToken = saved.guestToken(room.roomCode);
		if (!guestToken) return;
		busy = true;
		problem = null;
		try {
			await linkScreen(digits, room.roomCode, guestToken);
			onToast('The TV shows the room now');
			onClose();
		} catch (e) {
			problem = e instanceof Error ? e.message : "Couldn't reach the TV";
		} finally {
			busy = false;
		}
	}
</script>

<div class="fixed inset-0 z-[70] flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={onClose}>
	<div
		class="w-full max-w-sm rounded-t-2xl bg-surface p-6 ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label="Show on a TV"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		onkeydown={(e) => e.key === 'Escape' && onClose()}
	>
	<form class="flex flex-col gap-4" onsubmit={link}>
		<h2 class="text-lg font-bold">Show on a TV</h2>
		<p class="text-sm text-muted">
			On the TV (or any screen), open <b class="text-white">{address}</b> in its browser. Enter the code it shows: it becomes this room's party screen, with what plays,
			what's next, and a code to join.
		</p>
		<!-- svelte-ignore a11y_autofocus -->
		<input
			class="{input} text-center font-mono text-2xl tracking-[0.3em]"
			bind:value={code}
			inputmode="numeric"
			autocomplete="off"
			maxlength="7"
			placeholder="000 000"
			aria-label="Code on the TV"
			autofocus
		/>
		{#if problem}<p class="text-sm text-accent">{problem}</p>{/if}
		<div class="flex justify-end gap-2">
			<button type="button" class="rounded-full px-4 py-2 text-sm font-medium hover:bg-raised" onclick={onClose}>Cancel</button>
			<button class="rounded-full bg-white px-4 py-2 text-sm font-medium text-black disabled:opacity-50" disabled={busy || digits.length !== 6}>Show it</button>
		</div>
	</form>
	</div>
</div>

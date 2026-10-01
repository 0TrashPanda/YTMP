<script lang="ts">
	import { goto } from '$app/navigation';
	import { createRoom, getRoom } from '../lib/api';
	import { saved } from '../lib/storage';

	let name = $state(saved.displayName);
	let code = $state('');
	let roomName = $state('');
	let error = $state<string | null>(null);
	let busy = $state(false);

	const validName = $derived(name.trim().length > 0 && name.trim().length <= 32);

	async function join(event: SubmitEvent) {
		event.preventDefault();
		await attempt(async () => {
			const room = await getRoom(code.trim());
			saved.displayName = name.trim();
			await goto(`/room/${room.code}`);
		});
	}

	async function host(event: SubmitEvent) {
		event.preventDefault();
		await attempt(async () => {
			const created = await createRoom(roomName.trim() || `${name.trim()}'s room`);
			saved.displayName = name.trim();
			saved.setOwnerToken(created.code, created.ownerToken);
			await goto(`/room/${created.code}`);
		});
	}

	async function attempt(action: () => Promise<void>) {
		busy = true;
		error = null;
		try {
			await action();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Something went wrong';
		} finally {
			busy = false;
		}
	}
</script>

<main class="mx-auto flex min-h-full max-w-md flex-col justify-center gap-8 px-4 py-12">
	<header class="text-center">
		<h1 class="text-5xl font-black tracking-tight">YT<span class="text-accent">MP</span></h1>
		<p class="mt-2 text-muted">One queue for everyone.</p>
	</header>

	<label class="flex flex-col gap-2">
		<span class="text-sm text-muted">Your name</span>
		<input
			class="rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2"
			bind:value={name}
			maxlength="32"
			placeholder="What should others see?"
			autocomplete="nickname"
		/>
	</label>

	<form class="flex flex-col gap-3 rounded-xl bg-surface p-5 ring-1 ring-line" onsubmit={join}>
		<h2 class="text-lg font-bold">Join a room</h2>
		<input
			class="rounded-lg bg-raised px-4 py-3 text-center font-mono text-2xl tracking-[0.4em] uppercase outline-none ring-white/40 focus:ring-2"
			bind:value={code}
			maxlength="8"
			placeholder="CODE"
			autocapitalize="characters"
			autocomplete="off"
			spellcheck="false"
		/>
		<button class="rounded-full bg-white py-3 font-medium text-black disabled:opacity-40" disabled={busy || !validName || !code.trim()}>
			Join
		</button>
	</form>

	<form class="flex flex-col gap-3 rounded-xl bg-surface p-5 ring-1 ring-line" onsubmit={host}>
		<h2 class="text-lg font-bold">Host a room</h2>
		<input
			class="rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2"
			bind:value={roomName}
			maxlength="64"
			placeholder={name.trim() ? `${name.trim()}'s room` : 'Room name'}
		/>
		<button class="rounded-full bg-accent py-3 font-medium disabled:opacity-40" disabled={busy || !validName}>
			Host
		</button>
	</form>

	{#if error}
		<p class="text-center text-accent">{error}</p>
	{/if}
</main>

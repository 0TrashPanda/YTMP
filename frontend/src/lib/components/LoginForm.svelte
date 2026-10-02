<script lang="ts">
	import { authServer } from '../account';
	import type { AuthServerInfo } from '../protocol.gen';

	let { info, invite = null, onDone }: { info: AuthServerInfo; invite?: string | null; onDone: () => void } = $props();

	const canSignUp = $derived(info.needsAdmin || info.signup === 'open' || (info.signup === 'invite' && !!invite));
	let mode = $state<'login' | 'signup'>('login');
	$effect(() => {
		if (info.needsAdmin || invite) mode = 'signup';
	});

	let username = $state('');
	let displayName = $state('');
	let password = $state('');
	let error = $state<string | null>(null);
	let busy = $state(false);

	async function submit(event: SubmitEvent) {
		event.preventDefault();
		busy = true;
		error = null;
		try {
			if (mode === 'login') await authServer.login(username.trim(), password);
			else await authServer.signup(username.trim(), password, displayName.trim(), invite);
			onDone();
		} catch (e) {
			error = e instanceof Error ? e.message : 'Something went wrong';
		} finally {
			busy = false;
		}
	}
</script>

<form class="flex flex-col gap-3 rounded-xl bg-surface p-5 ring-1 ring-line" onsubmit={submit}>
	<h2 class="text-lg font-bold">
		{#if mode === 'login'}Log in{:else if info.needsAdmin}Create the admin account{:else}Create an account{/if}
		<span class="font-normal text-muted">on {info.issuer}</span>
	</h2>
	{#if info.needsAdmin}
		<p class="text-sm text-muted">This server has no accounts yet. The first account manages the server.</p>
	{/if}
	<input
		class="rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2"
		bind:value={username}
		placeholder="Username"
		autocomplete="username"
		autocapitalize="none"
		spellcheck="false"
		maxlength="32"
	/>
	{#if mode === 'signup'}
		<input
			class="rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2"
			bind:value={displayName}
			placeholder="Name others see (optional)"
			autocomplete="nickname"
			maxlength="32"
		/>
	{/if}
	<input
		class="rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2"
		type="password"
		bind:value={password}
		placeholder={mode === 'signup' ? 'Password (8+ characters)' : 'Password'}
		autocomplete={mode === 'signup' ? 'new-password' : 'current-password'}
	/>
	<button class="rounded-full bg-white py-3 font-medium text-black disabled:opacity-40" disabled={busy || !username.trim() || !password}>
		{mode === 'login' ? 'Log in' : 'Create account'}
	</button>
	{#if error}
		<p class="text-center text-accent">{error}</p>
	{/if}
	{#if canSignUp && !info.needsAdmin}
		<button type="button" class="text-sm text-muted underline" onclick={() => (mode = mode === 'login' ? 'signup' : 'login')}>
			{mode === 'login' ? 'Create an account instead' : 'I already have an account'}
		</button>
	{:else if mode === 'login' && info.signup !== 'open'}
		<p class="text-center text-sm text-muted">
			{info.signup === 'invite' ? 'New here? Ask the server admin for an invite link.' : 'Accounts are made by the server admin.'}
		</p>
	{/if}
</form>

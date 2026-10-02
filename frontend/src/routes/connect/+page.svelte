<script lang="ts">
	// Log in to a host's page with an account on this server (see docs/implementation/auth.md).
	// The password is typed here, on the auth server; the host's page only gets a token that
	// works for its own address, through the URL fragment.
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import { authServer, returnWithIdentity, session } from '../../lib/account';
	import LoginForm from '../../lib/components/LoginForm.svelte';
	import type { AccountInfo, AuthServerInfo } from '../../lib/protocol.gen';

	const returnUrl = page.url.searchParams.get('return');
	const target = (() => {
		try {
			const url = new URL(returnUrl ?? '');
			return url.protocol === 'http:' || url.protocol === 'https:' ? url : null;
		} catch {
			return null;
		}
	})();

	let info = $state<AuthServerInfo | null>(null);
	let account = $state<AccountInfo | null>(null);
	let error = $state<string | null>(null);
	let busy = $state(false);
	let loaded = $state(false);

	onMount(async () => {
		try {
			info = await authServer.info();
			account = await authServer.refresh();
		} catch (e) {
			error = e instanceof Error ? e.message : "Can't reach the server";
		}
		loaded = true;
		// Our own pages don't need to ask.
		if (account && target?.origin === location.origin) await allow();
	});

	async function allow() {
		if (!target) return;
		busy = true;
		error = null;
		try {
			returnWithIdentity(target.toString(), await authServer.hostToken(target.origin));
		} catch (e) {
			error = e instanceof Error ? e.message : 'Something went wrong';
			busy = false;
		}
	}

	async function switchAccount() {
		await authServer.logout();
		account = null;
	}
</script>

<main class="mx-auto flex min-h-full max-w-md flex-col justify-center gap-6 px-4 py-12">
	<header class="text-center">
		<h1 class="text-5xl font-black tracking-tight">YT<span class="text-accent">MP</span></h1>
	</header>

	{#if !target}
		<p class="text-center text-accent">This login link is broken.</p>
	{:else if !loaded}
		<p class="text-center text-muted">Loading…</p>
	{:else if info && !account}
		<LoginForm {info} onDone={() => (account = session.current?.account ?? null)} />
	{:else if account}
		<section class="flex flex-col gap-4 rounded-xl bg-surface p-5 ring-1 ring-line">
			<p>
				Join rooms on <span class="font-mono">{target.host}</span> as
				<span class="font-bold">{account.displayName}</span>
				<span class="text-muted">({account.id})</span>?
			</p>
			<p class="text-sm text-muted">That host will see your name and account ID. It never sees your password.</p>
			<button class="rounded-full bg-white py-3 font-medium text-black disabled:opacity-40" disabled={busy} onclick={allow}>Continue</button>
			<div class="flex justify-between text-sm text-muted">
				<button class="underline" onclick={switchAccount}>Use another account</button>
				<a class="underline" href={target.toString()}>Cancel</a>
			</div>
		</section>
	{/if}

	{#if error}
		<p class="text-center text-accent">{error}</p>
	{/if}
</main>

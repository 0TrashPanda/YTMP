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
	/** A host's page checking whether you're logged in here (see connectQuietly): no login form. */
	const quiet = page.url.searchParams.get('quiet') === '1';
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

	// Hosts you said yes to before, per account, in this browser: they don't ask again.
	const APPROVED = 'ytmp.approvedHosts';
	function approved(): Record<string, string[]> {
		try {
			return JSON.parse(localStorage.getItem(APPROVED) ?? '{}');
		} catch {
			return {};
		}
	}
	function remember(accountId: string, origin: string) {
		const all = approved();
		all[accountId] = [...new Set([...(all[accountId] ?? []), origin])];
		try {
			localStorage.setItem(APPROVED, JSON.stringify(all));
		} catch {
			// Asks again next time.
		}
	}

	/** Back to the host's page without a login: [answer] `none` (not logged in), or `declined`. */
	function goBack(answer: 'none' | 'declined') {
		if (!target) return;
		const url = new URL(target);
		url.hash = `ytmp-connect=${answer}`;
		location.href = url.toString();
	}

	onMount(async () => {
		try {
			info = await authServer.info();
			account = await authServer.refresh();
		} catch (e) {
			error = e instanceof Error ? e.message : "Can't reach the server";
		}
		loaded = true;
		// Our own pages, and hosts you said yes to, don't need to ask.
		if (account && target && (target.origin === location.origin || approved()[account.id]?.includes(target.origin))) await allow();
		// Not logged in here (or the server didn't answer): the host's page asks your name as before.
		else if (quiet && !account) goBack('none');
	});

	async function allow() {
		if (!target) return;
		busy = true;
		error = null;
		try {
			const token = await authServer.hostToken(target.origin);
			remember(token.account.id, target.origin);
			returnWithIdentity(target.toString(), token);
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
				{#if quiet}
					<button class="underline" onclick={() => goBack('declined')}>Not now, join as a guest</button>
				{:else}
					<a class="underline" href={target.toString()}>Cancel</a>
				{/if}
			</div>
		</section>
	{/if}

	{#if error}
		<p class="text-center text-accent">{error}</p>
	{/if}
</main>

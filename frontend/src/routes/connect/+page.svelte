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
	/** The host's signed word that it's one of your phones (see identity.logIn); only this server can tell whether it is. */
	const proof = page.url.searchParams.get('proof');
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

	// Hosts you said yes to before, per account, in this browser: they don't ask again. And
	// the ones that also get your YouTube Music from here.
	const APPROVED = 'ytmp.approvedHosts';
	const WITH_YOUTUBE = 'ytmp.youtubeHosts';
	function approved(key = APPROVED): Record<string, string[]> {
		try {
			return JSON.parse(localStorage.getItem(key) ?? '{}');
		} catch {
			return {};
		}
	}
	function remember(accountId: string, origin: string, key = APPROVED, yes = true) {
		const all = approved(key);
		const others = (all[accountId] ?? []).filter((o) => o !== origin);
		all[accountId] = yes ? [...others, origin] : others;
		try {
			localStorage.setItem(key, JSON.stringify(all));
		} catch {
			// Asks again next time.
		}
	}

	/** Also your YouTube Music and listening history there (they come from this server; that host never gets your sign-in). */
	let withYoutube = $state(true);

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
		if (account) await decide(account);
		// Not logged in here (or the server didn't answer): the host's page asks your name as before.
		else if (quiet) goBack('none');
	});

	/** Logged in: goes straight back where it needn't ask. */
	async function decide(account: AccountInfo) {
		if (!target) return;
		// Our own pages, and hosts you said yes to, don't need to ask (with what you said then).
		if (target.origin !== location.origin) withYoutube = approved(WITH_YOUTUBE)[account.id]?.includes(target.origin) ?? true;
		if (target.origin === location.origin || approved()[account.id]?.includes(target.origin)) return allow();
		// One of your own phones (this server checks the phone's proof), and your setting says
		// not to ask there: with your YouTube Music and history. Not remembered as a yes, so with
		// the setting off it asks again.
		if (proof) {
			deciding = true;
			const answer = await authServer.ownHost(target.origin, proof).catch(() => null);
			deciding = false;
			if (answer?.withoutAsking) {
				withYoutube = true;
				return allow(false);
			}
		}
	}

	/** Checking whether the host is one of your phones (the question waits for that). */
	let deciding = $state(false);

	/** [keep]: remember the answer for this host in this browser. */
	async function allow(keep = true) {
		if (!target) return;
		busy = true;
		error = null;
		try {
			const token = await authServer.hostToken(target.origin);
			const otherHost = target.origin !== location.origin;
			if (keep) remember(token.account.id, target.origin);
			if (keep && otherHost) remember(token.account.id, target.origin, WITH_YOUTUBE, withYoutube);
			const youtube = otherHost && withYoutube ? await authServer.youtubeToken().catch(() => undefined) : undefined;
			const history = otherHost && withYoutube ? await authServer.historyToken().catch(() => undefined) : undefined;
			returnWithIdentity(target.toString(), token, youtube, history);
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
		<LoginForm
			{info}
			onDone={() => {
				account = session.current?.account ?? null;
				if (account) decide(account);
			}}
		/>
	{:else if account && (deciding || busy) && !error}
		<p class="text-center text-muted">Logging in…</p>
	{:else if account}
		<section class="flex flex-col gap-4 rounded-xl bg-surface p-5 ring-1 ring-line">
			<p>
				Join rooms on <span class="font-mono">{target.host}</span> as
				<span class="font-bold">{account.displayName}</span>
				<span class="text-muted">({account.id})</span>?
			</p>
			<p class="text-sm text-muted">That host will see your name and account ID. It never sees your password.</p>
			<label class="flex items-start gap-2 text-sm">
				<input type="checkbox" class="mt-0.5 h-5 w-5 shrink-0 accent-accent" bind:checked={withYoutube} />
				<span>
					Also use my YouTube Music and history there
					<span class="block text-xs text-muted">
						Your Home, Library, likes and listening history in rooms there come from this server; the host never gets your sign-in. Deleting or changing playlists stays here.
					</span>
				</span>
			</label>
			<button class="rounded-full bg-white py-3 font-medium text-black disabled:opacity-40" disabled={busy} onclick={() => allow()}>Continue</button>
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

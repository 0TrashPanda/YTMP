<script lang="ts">
	// Your account on this server. The server admin also manages accounts and invites here.
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import { authServer, session } from '../../lib/account';
	import LoginForm from '../../lib/components/LoginForm.svelte';
	import TemplateSettings from '../../lib/components/settings/TemplateSettings.svelte';
	import type { AccountInfo, AuthServerInfo } from '../../lib/protocol.gen';

	const invite = page.url.searchParams.get('invite');

	let info = $state<AuthServerInfo | null>(null);
	let account = $state<AccountInfo | null>(null);
	let loaded = $state(false);
	let error = $state<string | null>(null);
	let message = $state<string | null>(null);
	let busy = $state(false);

	let editingRoles = $state(false);
	let currentPassword = $state('');
	let newPassword = $state('');

	// Admin
	let accounts = $state<AccountInfo[]>([]);
	let inviteLink = $state<string | null>(null);
	let newUsername = $state('');
	let newDisplayName = $state('');
	let newUserPassword = $state('');
	let resetFor = $state<string | null>(null);
	let resetPassword = $state('');

	onMount(async () => {
		try {
			info = await authServer.info();
			account = await authServer.refresh();
			await loadAdmin();
		} catch {
			error = "This host doesn't have accounts. Accounts live on a YTMP server.";
		}
		loaded = true;
	});

	async function loadAdmin() {
		if (account?.isAdmin) accounts = await authServer.listAccounts();
	}

	async function loggedIn() {
		account = session.current?.account ?? null;
		info = await authServer.info();
		// An invite is used up: drop it from the URL.
		if (invite) history.replaceState(history.state, '', '/account');
		await loadAdmin();
	}

	async function attempt(action: () => Promise<void>, done?: string) {
		busy = true;
		error = null;
		message = null;
		try {
			await action();
			message = done ?? null;
		} catch (e) {
			error = e instanceof Error ? e.message : 'Something went wrong';
		} finally {
			busy = false;
		}
	}

	const changePassword = (event: SubmitEvent) => {
		event.preventDefault();
		return attempt(async () => {
			await authServer.changePassword(currentPassword, newPassword);
			currentPassword = newPassword = '';
		}, 'Password changed. Other devices are logged out.');
	};

	const createInvite = () =>
		attempt(async () => {
			const created = await authServer.createInvite();
			inviteLink = `${location.origin}/account?invite=${encodeURIComponent(created.code)}`;
		});

	const createAccount = (event: SubmitEvent) => {
		event.preventDefault();
		return attempt(async () => {
			await authServer.createAccount(newUsername.trim(), newUserPassword, newDisplayName.trim());
			newUsername = newDisplayName = newUserPassword = '';
			await loadAdmin();
		}, 'Account created.');
	};

	const setPassword = (event: SubmitEvent) => {
		event.preventDefault();
		return attempt(async () => {
			await authServer.setPassword(resetFor!, resetPassword);
			resetFor = null;
			resetPassword = '';
		}, 'Password set. That account is logged out everywhere.');
	};

	async function logout() {
		await authServer.logout();
		account = null;
	}

	const input = 'rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2';
	const card = 'flex flex-col gap-3 rounded-xl bg-surface p-5 ring-1 ring-line';
</script>

<svelte:head>
	<title>Account · YTMP</title>
</svelte:head>

<main class="mx-auto flex min-h-full max-w-md flex-col justify-center gap-6 px-4 py-12">
	<header class="text-center">
		<a href="/" class="text-5xl font-black tracking-tight">YT<span class="text-accent">MP</span></a>
	</header>

	{#if !loaded}
		<p class="text-center text-muted">Loading…</p>
	{:else if info && !account}
		<LoginForm {info} {invite} onDone={loggedIn} />
	{:else if info && account}
		<section class={card}>
			<div class="flex items-center gap-3">
				<div class="min-w-0 flex-1">
					<p class="truncate text-lg font-bold">{account.displayName}</p>
					<p class="truncate text-sm text-muted">{account.id}{account.isAdmin ? ' · server admin' : ''}</p>
				</div>
				<button class="rounded-full bg-raised px-4 py-2 text-sm hover:bg-line" onclick={logout}>Log out</button>
			</div>
		</section>

		<section class={card}>
			<h2 class="font-bold">Your roles</h2>
			<p class="text-sm text-muted">The roles and permissions every room you create starts with (Admin, DJ, Listener unless you change them).</p>
			<button class="rounded-full bg-raised py-2 font-medium hover:bg-line" onclick={() => (editingRoles = true)}>Edit roles</button>
		</section>

		<form class={card} onsubmit={changePassword}>
			<h2 class="font-bold">Change password</h2>
			<input class={input} type="password" bind:value={currentPassword} placeholder="Current password" autocomplete="current-password" />
			<input class={input} type="password" bind:value={newPassword} placeholder="New password (8+ characters)" autocomplete="new-password" />
			<button class="rounded-full bg-raised py-2 font-medium hover:bg-line disabled:opacity-40" disabled={busy || !currentPassword || !newPassword}>
				Change password
			</button>
		</form>

		{#if account.isAdmin}
			<section class={card}>
				<h2 class="font-bold">Invite someone</h2>
				<p class="text-sm text-muted">Sign-up here: {info.signup}. An invite link works once, for 7 days.</p>
				{#if inviteLink}
					<input class="{input} font-mono text-sm" readonly value={inviteLink} onfocus={(e) => e.currentTarget.select()} />
				{/if}
				<button class="rounded-full bg-raised py-2 font-medium hover:bg-line disabled:opacity-40" disabled={busy} onclick={createInvite}>
					New invite link
				</button>
			</section>

			<form class={card} onsubmit={createAccount}>
				<h2 class="font-bold">Create an account</h2>
				<input class={input} bind:value={newUsername} placeholder="Username" autocapitalize="none" spellcheck="false" maxlength="32" />
				<input class={input} bind:value={newDisplayName} placeholder="Name others see (optional)" maxlength="32" />
				<input class={input} type="password" bind:value={newUserPassword} placeholder="Password (8+ characters)" autocomplete="new-password" />
				<button class="rounded-full bg-raised py-2 font-medium hover:bg-line disabled:opacity-40" disabled={busy || !newUsername.trim() || !newUserPassword}>
					Create
				</button>
			</form>

			<section class="flex flex-col gap-1">
				<h2 class="px-1 pb-1 text-sm font-medium tracking-wide text-muted uppercase">Accounts</h2>
				{#each accounts as a (a.id)}
					<div class="flex flex-col gap-2 rounded-lg bg-surface px-3 py-2 ring-1 ring-line">
						<div class="flex items-center gap-3">
							<span class="min-w-0 flex-1 truncate">{a.displayName} <span class="text-sm text-muted">{a.username}{a.isAdmin ? ' · admin' : ''}</span></span>
							<button class="text-sm text-muted underline" onclick={() => (resetFor = resetFor === a.username ? null : a.username)}>Set password</button>
						</div>
						{#if resetFor === a.username}
							<form class="flex gap-2" onsubmit={setPassword}>
								<input class="{input} min-w-0 flex-1 py-2" type="password" bind:value={resetPassword} placeholder="New password" autocomplete="new-password" />
								<button class="rounded-full bg-raised px-4 text-sm hover:bg-line disabled:opacity-40" disabled={busy || !resetPassword}>Set</button>
							</form>
						{/if}
					</div>
				{/each}
			</section>
		{/if}
	{/if}

	{#if message}
		<p class="text-center text-muted">{message}</p>
	{/if}
	{#if error}
		<p class="text-center text-accent">{error}</p>
	{/if}
	<a href="/" class="text-center text-sm text-muted underline">Back</a>
</main>

{#if editingRoles}
	<TemplateSettings onClose={() => (editingRoles = false)} />
{/if}

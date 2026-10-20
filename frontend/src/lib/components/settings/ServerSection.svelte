<script lang="ts">
	// Settings → Server (the server admin only): invites, new accounts, and setting someone's password.
	import { authServer } from '../../account';
	import type { AccountInfo, AuthServerInfo } from '../../protocol.gen';
	import CacheSection from './CacheSection.svelte';

	let { info, onToast }: { info: AuthServerInfo; onToast: (text: string) => void } = $props();

	let accounts = $state<AccountInfo[]>([]);
	let inviteLink = $state<string | null>(null);
	let newUsername = $state('');
	let newDisplayName = $state('');
	let newUserPassword = $state('');
	let resetFor = $state<string | null>(null);
	let resetPassword = $state('');
	let busy = $state(false);

	const load = async () => (accounts = await authServer.listAccounts());
	load().catch(() => {});

	async function attempt(action: () => Promise<void>, done?: string) {
		busy = true;
		try {
			await action();
			if (done) onToast(done);
		} catch (e) {
			onToast(e instanceof Error ? e.message : 'Something went wrong');
		} finally {
			busy = false;
		}
	}

	const createInvite = () =>
		attempt(async () => {
			const created = await authServer.createInvite();
			inviteLink = `${location.origin}/account?invite=${encodeURIComponent(created.code)}`;
		});

	function createAccount(event: SubmitEvent) {
		event.preventDefault();
		attempt(async () => {
			await authServer.createAccount(newUsername.trim(), newUserPassword, newDisplayName.trim());
			newUsername = newDisplayName = newUserPassword = '';
			await load();
		}, 'Account created.');
	}

	function setPassword(event: SubmitEvent) {
		event.preventDefault();
		attempt(async () => {
			await authServer.setPassword(resetFor!, resetPassword);
			resetFor = null;
			resetPassword = '';
		}, 'Password set. That account is logged out everywhere.');
	}

	const card = 'flex flex-col gap-3 rounded-xl bg-raised/60 p-4';
	const input = 'rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2';
</script>

<section class={card}>
	<h3 class="font-bold">Invite someone</h3>
	<p class="text-xs text-muted">Sign-up here: {info.signup}. An invite link works once, for 7 days.</p>
	{#if inviteLink}
		<input class="{input} font-mono text-sm" readonly value={inviteLink} onfocus={(e) => e.currentTarget.select()} />
	{/if}
	<button class="rounded-full bg-raised py-2 font-medium hover:bg-line disabled:opacity-40" disabled={busy} onclick={createInvite}>New invite link</button>
</section>

<form class={card} onsubmit={createAccount}>
	<h3 class="font-bold">Create an account</h3>
	<input class={input} bind:value={newUsername} placeholder="Username" autocapitalize="none" spellcheck="false" maxlength="32" />
	<input class={input} bind:value={newDisplayName} placeholder="Name others see (optional)" maxlength="32" />
	<input class={input} type="password" bind:value={newUserPassword} placeholder="Password (8+ characters)" autocomplete="new-password" />
	<button class="rounded-full bg-raised py-2 font-medium hover:bg-line disabled:opacity-40" disabled={busy || !newUsername.trim() || !newUserPassword}>Create</button>
</form>

<div class={card}>
	<CacheSection load={authServer.songCache} clear={authServer.clearSongCache} card {onToast} />
</div>

<section class="flex flex-col gap-1">
	<h3 class="px-1 pb-1 text-sm font-medium tracking-wide text-muted uppercase">Accounts</h3>
	{#each accounts as a (a.id)}
		<div class="flex flex-col gap-2 rounded-lg bg-raised/60 px-3 py-2">
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

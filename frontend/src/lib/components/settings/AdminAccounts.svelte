<script lang="ts">
	// Server admin → Accounts: invite links, new accounts, and per account: its name, server
	// admin or not, a new password, logging out everywhere, deleting it.
	import { authServer } from '../../account';
	import { timeAgo } from '../../format';
	import type { AccountInfo, AdminAccountInfo, AuthServerInfo, InviteInfo } from '../../protocol.gen';
	import Switch from './Switch.svelte';

	let { info, me, onToast }: { info: AuthServerInfo; me: AccountInfo; onToast: (text: string) => void } = $props();

	let accounts = $state<AdminAccountInfo[]>([]);
	let invites = $state<InviteInfo[]>([]);
	let inviteLink = $state<string | null>(null);
	let newUsername = $state('');
	let newDisplayName = $state('');
	let newUserPassword = $state('');
	let newIsAdmin = $state(false);
	/** The account whose actions are open. */
	let open = $state<string | null>(null);
	let rename = $state('');
	let newPassword = $state('');
	let busy = $state(false);

	async function load() {
		[accounts, invites] = await Promise.all([authServer.listAccounts(), authServer.listInvites()]);
	}
	load().catch((e) => onToast(e instanceof Error ? e.message : "Couldn't load the accounts"));

	async function attempt(action: () => Promise<unknown>, done?: string) {
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
			invites = await authServer.listInvites();
		});

	const revokeInvite = (id: string) =>
		attempt(async () => {
			await authServer.revokeInvite(id);
			invites = invites.filter((i) => i.id !== id);
			inviteLink = null;
		}, 'Invite link revoked.');

	function createAccount(event: SubmitEvent) {
		event.preventDefault();
		attempt(async () => {
			await authServer.createAccount(newUsername.trim(), newUserPassword, newDisplayName.trim(), newIsAdmin);
			newUsername = newDisplayName = newUserPassword = '';
			newIsAdmin = false;
			await load();
		}, 'Account created.');
	}

	function toggle(a: AdminAccountInfo) {
		open = open === a.username ? null : a.username;
		rename = a.displayName;
		newPassword = '';
	}

	function saveName(event: SubmitEvent, a: AdminAccountInfo) {
		event.preventDefault();
		attempt(async () => {
			await authServer.updateAccount(a.username, { displayName: rename.trim() });
			await load();
		}, 'Name changed.');
	}

	function setAdmin(a: AdminAccountInfo, on: boolean) {
		if (!on && a.username === me.username && !confirm("Stop being a server admin? You can't open this page any more after that.")) return;
		attempt(async () => {
			await authServer.updateAccount(a.username, { isAdmin: on });
			await load();
		}, on ? `${a.displayName} is now a server admin.` : `${a.displayName} is no longer a server admin.`);
	}

	function setPassword(event: SubmitEvent, a: AdminAccountInfo) {
		event.preventDefault();
		attempt(async () => {
			await authServer.setPassword(a.username, newPassword);
			newPassword = '';
			await load();
		}, 'Password set. That account is logged out everywhere.');
	}

	function logOut(a: AdminAccountInfo) {
		if (!confirm(`Log ${a.displayName} out on every device? Rooms they're in keep them until they leave.`)) return;
		attempt(async () => {
			await authServer.logOutEverywhere(a.username);
			await load();
		}, `${a.displayName} is logged out everywhere.`);
	}

	const disabled = (a: AdminAccountInfo) => a.disabledUntil === -1 || a.disabledUntil > Date.now();

	/** Disable for a while ([minutes]) or until enabled again (null). */
	function disable(a: AdminAccountInfo, minutes: number | null) {
		attempt(async () => {
			await authServer.disableAccount(a.username, minutes);
			await load();
		}, `${a.displayName} is disabled and logged out everywhere.`);
	}

	function enable(a: AdminAccountInfo) {
		attempt(async () => {
			await authServer.enableAccount(a.username);
			await load();
		}, `${a.displayName} can log in again.`);
	}

	function remove(a: AdminAccountInfo) {
		if (!confirm(`Delete the account ${a.username}? Its listening history, settings and rooms go too. This can't be undone.`)) return;
		attempt(async () => {
			await authServer.deleteAccount(a.username);
			open = null;
			await load();
		}, 'Account deleted.');
	}

	const date = (ms: number) => new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
	const card = 'flex flex-col gap-3 rounded-xl bg-raised/60 p-4';
	const input = 'rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2';
	const small = 'rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-40';
</script>

<section class="flex flex-col gap-1">
	{#each accounts as a (a.id)}
		<div class="flex flex-col gap-3 rounded-lg bg-raised/60 px-3 py-2">
			<button class="flex items-center gap-3 text-left" aria-expanded={open === a.username} onclick={() => toggle(a)}>
				<span class="min-w-0 flex-1">
					<span class="block truncate">
						{a.displayName}
						<span class="text-sm text-muted">{a.username}{a.username === me.username ? ' · you' : ''}</span>
						{#if a.isAdmin}<span class="ml-1 rounded bg-white/15 px-1.5 py-0.5 text-xs">Admin</span>{/if}
						{#if disabled(a)}<span class="ml-1 rounded bg-red-500/30 px-1.5 py-0.5 text-xs">Disabled{a.disabledUntil > 0 ? ` until ${new Date(a.disabledUntil).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}` : ''}</span>{/if}
					</span>
					<span class="block truncate text-xs text-muted">
						{a.lastSeen ? `Seen ${timeAgo(a.lastSeen)}` : 'Not logged in'}{a.logins > 1 ? ` · ${a.logins} devices` : ''}
					</span>
				</span>
				<span class="text-muted transition-transform {open === a.username ? 'rotate-180' : ''}" aria-hidden="true">▾</span>
			</button>
			{#if open === a.username}
				<div class="flex flex-col gap-3 border-t border-line pt-3 pb-1">
					<div class="flex items-center gap-3">
						<div class="flex-1">
							<p class="text-sm font-medium">Server admin</p>
							<p class="text-xs text-muted">Can open this page: accounts, server settings, rooms.</p>
						</div>
						<Switch checked={a.isAdmin} disabled={busy} label="Server admin" onchange={(on) => setAdmin(a, on)} />
					</div>
					<form class="flex gap-2" onsubmit={(e) => saveName(e, a)}>
						<input class="{input} min-w-0 flex-1 py-2" bind:value={rename} placeholder="Name others see" maxlength="32" aria-label="Name others see" />
						<button class={small} disabled={busy || !rename.trim() || rename.trim() === a.displayName}>Rename</button>
					</form>
					<form class="flex gap-2" onsubmit={(e) => setPassword(e, a)}>
						<input class="{input} min-w-0 flex-1 py-2" type="password" bind:value={newPassword} placeholder="New password" autocomplete="new-password" aria-label="New password" />
						<button class={small} disabled={busy || !newPassword}>Set</button>
					</form>
					<div class="flex flex-col gap-2">
						<p class="text-sm font-medium">Disable</p>
						<p class="text-xs text-muted">Logs it out everywhere and stops it logging in, for a while or until you enable it. Its listening history, settings and rooms stay.</p>
						{#if disabled(a)}
							<div><button class={small} disabled={busy} onclick={() => enable(a)}>Enable again</button></div>
						{:else if a.username !== me.username}
							<div class="flex flex-wrap gap-2">
								<button class={small} disabled={busy} onclick={() => disable(a, 60)}>1 hour</button>
								<button class={small} disabled={busy} onclick={() => disable(a, 24 * 60)}>1 day</button>
								<button class={small} disabled={busy} onclick={() => disable(a, 7 * 24 * 60)}>1 week</button>
								<button class={small} disabled={busy} onclick={() => disable(a, null)}>Until I enable it</button>
							</div>
						{/if}
					</div>
					<div class="flex flex-wrap gap-2">
						<button class={small} disabled={busy || a.logins === 0} onclick={() => logOut(a)}>Log out everywhere</button>
						<button class="{small} text-red-400" disabled={busy} onclick={() => remove(a)}>Delete account</button>
					</div>
				</div>
			{/if}
		</div>
	{/each}
</section>

<section class={card}>
	<h3 class="font-bold">Invite someone</h3>
	<p class="text-xs text-muted">
		{#if info.signup === 'open'}Anyone can sign up here now, so invite links aren't needed (Settings tab).{:else}An invite link works once, for 7 days.{/if}
	</p>
	{#if inviteLink}
		<input class="{input} font-mono text-sm" readonly value={inviteLink} onfocus={(e) => e.currentTarget.select()} />
	{/if}
	<button class="rounded-full bg-raised py-2 font-medium hover:bg-line disabled:opacity-40" disabled={busy} onclick={createInvite}>New invite link</button>
	{#if invites.length}
		<div class="flex flex-col gap-1">
			<p class="text-sm font-medium">Open invite links</p>
			{#each invites as i (i.id)}
				<div class="flex items-center gap-3 text-sm">
					<span class="min-w-0 flex-1 truncate text-muted">By {i.createdBy}, {date(i.createdAt)} · until {date(i.expiresAt)}</span>
					<button class="text-sm text-muted underline disabled:opacity-40" disabled={busy} onclick={() => revokeInvite(i.id)}>Revoke</button>
				</div>
			{/each}
		</div>
	{/if}
</section>

<form class={card} onsubmit={createAccount}>
	<h3 class="font-bold">Create an account</h3>
	<input class={input} bind:value={newUsername} placeholder="Username" autocapitalize="none" spellcheck="false" maxlength="32" />
	<input class={input} bind:value={newDisplayName} placeholder="Name others see (optional)" maxlength="32" />
	<input class={input} type="password" bind:value={newUserPassword} placeholder="Password (8+ characters)" autocomplete="new-password" />
	<div class="flex items-center gap-3">
		<p class="flex-1 text-sm">Server admin</p>
		<Switch checked={newIsAdmin} label="Server admin" onchange={(on) => (newIsAdmin = on)} />
	</div>
	<button class="rounded-full bg-raised py-2 font-medium hover:bg-line disabled:opacity-40" disabled={busy || !newUsername.trim() || !newUserPassword}>Create</button>
</form>

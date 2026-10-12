<script lang="ts">
	// Settings → Account. On a server: your account there (log in, listening history, your
	// rooms on other devices, your roles, password). On another host (a phone): who you're
	// logged in as there, and a link to your account on your server.
	import { authServer, identity, session } from '../../account';
	import type { AccountInfo, AccountSettings, AuthServerInfo, HostInfo, RoomOpenMode } from '../../protocol.gen';
	import LoginForm from '../LoginForm.svelte';
	import Switch from './Switch.svelte';
	import TemplateSettings from './TemplateSettings.svelte';

	let {
		host,
		info,
		account,
		invite = null,
		onAccount,
		onToast
	}: {
		host: HostInfo | null;
		/** This server's accounts (null on other hosts). */
		info: AuthServerInfo | null;
		/** Logged in on this server's own pages. */
		account: AccountInfo | null;
		invite?: string | null;
		onAccount: (account: AccountInfo | null) => void;
		onToast: (text: string) => void;
	} = $props();

	const me = $derived(host ? identity.get(host.authServers) : identity.stored);

	let settings = $state<AccountSettings | null>(null);
	let editingRoles = $state(false);
	let currentPassword = $state('');
	let newPassword = $state('');
	let busy = $state(false);

	$effect(() => {
		if (account) authServer.settings().then((s) => (settings = s)).catch(() => (settings = null));
	});

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

	const saveSettings = (next: AccountSettings) => attempt(async () => void (settings = await authServer.saveSettings(next)));

	function changePassword(event: SubmitEvent) {
		event.preventDefault();
		attempt(async () => {
			await authServer.changePassword(currentPassword, newPassword);
			currentPassword = newPassword = '';
		}, 'Password changed. Other devices are logged out.');
	}

	async function logOut() {
		if (account) await authServer.logout();
		identity.forget();
		onAccount(null);
	}

	function loggedIn() {
		// An invite is used up: drop it from the address.
		if (invite) history.replaceState(history.state, '', location.pathname);
		onAccount(session.current?.account ?? null);
	}

	const card = 'flex flex-col gap-3 rounded-xl bg-raised/60 p-4';
	const input = 'rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2';
	const yourServer = $derived(host?.authServers.find((s) => s.url && me?.account.id.endsWith(`@${s.issuer}`))?.url ?? null);
</script>

{#if info && account}
	<div class="flex items-center gap-3">
		<div class="min-w-0 flex-1">
			<p class="truncate text-lg font-bold">{account.displayName}</p>
			<p class="truncate text-sm text-muted">{account.id}{account.isAdmin ? ' · server admin' : ''}</p>
		</div>
		<button class="rounded-full bg-raised px-4 py-2 text-sm hover:bg-line" onclick={logOut}>Log out</button>
	</div>

	{#if settings}
		{@const s = settings}
		<section class={card}>
			<div class="flex items-center justify-between">
				<h3 class="font-bold">Listening history</h3>
				<a href="/account/history" class="text-sm text-muted underline">View</a>
			</div>
			<div class="flex items-center gap-4">
				<div class="min-w-0 flex-1">
					<p class="text-sm font-medium">Keep my history</p>
					<p class="text-xs text-muted">Every song you hear in a room, for recaps and radio. Turn it off to pause.</p>
				</div>
				<Switch checked={s.tracking} label="Keep my history" disabled={busy} onchange={(on) => saveSettings({ ...s, tracking: on })} />
			</div>
			<div class="flex items-center gap-4">
				<div class="min-w-0 flex-1">
					<p class="text-sm font-medium">Keep me out of others' history</p>
					<p class="text-xs text-muted">Others won't see that they listened with you. Applies to rooms you join after changing it.</p>
				</div>
				<Switch checked={s.hideFromOthers} label="Keep me out of others' history" disabled={busy} onchange={(on) => saveSettings({ ...s, hideFromOthers: on })} />
			</div>
		</section>

		<section class={card}>
			<h3 class="font-bold">Your rooms on your other devices</h3>
			<p class="text-xs text-muted">
				A room lives on one device: this server or your phone. When you open it on the other one, it can move there and continue where it was (each room can
				change this in its settings).
			</p>
			<select
				class="rounded-lg bg-raised px-3 py-2.5 outline-none"
				value={s.openElsewhere}
				disabled={busy}
				onchange={(e) => saveSettings({ ...s, openElsewhere: e.currentTarget.value as RoomOpenMode })}
			>
				<option value="ask">Ask each time: open it where it is, or move it</option>
				<option value="move">Always move it to where I open it</option>
				<option value="move_if_away">Move it only if its device is away</option>
			</select>
		</section>
	{/if}

	<section class={card}>
		<h3 class="font-bold">Your roles</h3>
		<p class="text-xs text-muted">The roles and permissions every room you create starts with (Admin, DJ, Listener unless you change them).</p>
		<button class="rounded-full bg-raised py-2 font-medium hover:bg-line" onclick={() => (editingRoles = true)}>Edit roles</button>
	</section>

	<form class={card} onsubmit={changePassword}>
		<h3 class="font-bold">Change password</h3>
		<input class={input} type="password" bind:value={currentPassword} placeholder="Current password" autocomplete="current-password" />
		<input class={input} type="password" bind:value={newPassword} placeholder="New password (8+ characters)" autocomplete="new-password" />
		<button class="rounded-full bg-raised py-2 font-medium hover:bg-line disabled:opacity-40" disabled={busy || !currentPassword || !newPassword}>
			Change password
		</button>
	</form>
{:else if info}
	<!-- This server's own pages: log in (or sign up) right here. -->
	<LoginForm {info} {invite} onDone={loggedIn} />
{:else if me}
	<div class="flex items-center gap-3">
		<div class="min-w-0 flex-1">
			<p class="truncate text-lg font-bold">{me.account.displayName}</p>
			<p class="truncate text-sm text-muted">{me.account.id}</p>
		</div>
		<button class="rounded-full bg-raised px-4 py-2 text-sm hover:bg-line" onclick={logOut}>Log out here</button>
	</div>
	<p class="text-sm text-muted">You're logged in on this host with your account. Your history, roles and password are on your server.</p>
	{#if yourServer}
		<a class="rounded-full bg-raised py-2.5 text-center font-medium hover:bg-line" href="{yourServer}/account">Manage your account on {new URL(yourServer).host}</a>
	{/if}
{:else if host?.authServers.length}
	<p class="text-sm text-muted">Log in with your YTMP account: rooms you make are yours on all your devices, and your history and roles come along.</p>
	{#each host.authServers as server (server.issuer)}
		<a href={identity.loginUrl(server)} class="rounded-full bg-white py-2.5 text-center font-medium text-black">
			Log in{host.authServers.length > 1 ? ` with ${server.url ? new URL(server.url).host : server.issuer}` : ''}
		</a>
	{/each}
{:else}
	<p class="text-sm text-muted">This host only has guests. On a phone, link it to a YTMP server (on the room list) to use accounts.</p>
{/if}

{#if editingRoles}
	<TemplateSettings onClose={() => (editingRoles = false)} />
{/if}

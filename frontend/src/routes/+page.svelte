<script lang="ts">
	import { goto } from '$app/navigation';
	import { onDestroy, onMount } from 'svelte';
	import { identity, type Identity } from '../lib/account';
	import { closeRoom, createRoom, getHost, getRoom, linkAuthServer, listRooms } from '../lib/api';
	import Icon from '../lib/components/Icon.svelte';
	import { nativeBridge, type NearbyRoom } from '../lib/native';
	import type { HostInfo, RoomInfo, RoomVisibility } from '../lib/protocol.gen';
	import { saved } from '../lib/storage';

	let name = $state(saved.displayName);
	let code = $state('');
	let roomName = $state('');
	let error = $state<string | null>(null);
	let busy = $state(false);

	let host = $state<HostInfo | null>(null);
	let myRooms = $state<RoomInfo[]>([]);
	let nearby = $state<NearbyRoom[]>([]);
	/** Logged in with an account this host accepts. */
	let me = $state<Identity | null>(null);
	let authUrl = $state('');
	let editingAuth = $state(false);

	const validName = $derived(!!me || (name.trim().length > 0 && name.trim().length <= 32));
	/** The page is shown by the phone that hosts it: it can host rooms itself. */
	const onThisPhone = $derived(host?.kind === 'phone' && host.canCreateRooms);
	const defaultRoomName = $derived(name.trim() ? `${name.trim()}'s room` : 'Room name');

	onMount(async () => {
		try {
			host = await getHost();
			me = identity.get(host.authServers);
			if (me) name = me.account.displayName;
			if (onThisPhone) myRooms = await listRooms();
		} catch (e) {
			error = e instanceof Error ? e.message : "Can't reach the host";
		}
		if (nativeBridge) {
			nearby = JSON.parse(nativeBridge.nearbyRooms());
			window.__ytmpNative = { ...window.__ytmpNative!, onNearbyRooms: (rooms) => (nearby = rooms) };
		}
	});

	onDestroy(() => {
		if (window.__ytmpNative) window.__ytmpNative.onNearbyRooms = undefined;
	});

	async function join(event: SubmitEvent) {
		event.preventDefault();
		await attempt(async () => {
			const room = await getRoom(code.trim());
			saved.displayName = name.trim();
			await goto(`/room/${room.code}`);
		});
	}

	async function hostRoom(visibility: RoomVisibility) {
		await attempt(async () => {
			const created = await createRoom(roomName.trim() || defaultRoomName, visibility, me?.token ?? null);
			saved.displayName = name.trim();
			saved.setOwnerToken(created.code, created.ownerToken);
			await goto(`/room/${created.code}`);
		});
	}

	async function close(room: RoomInfo) {
		await attempt(async () => {
			await closeRoom(room.code);
			myRooms = await listRooms();
		});
	}

	function logout() {
		identity.forget();
		me = null;
	}

	async function linkAccounts(url: string | null) {
		await attempt(async () => {
			await linkAuthServer(url);
			host = await getHost();
			me = identity.get(host.authServers);
			editingAuth = false;
		});
	}

	function joinNearby(room: NearbyRoom) {
		saved.displayName = name.trim();
		location.href = `${room.url}/room/${room.code}`;
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

<main class="mx-auto flex min-h-full max-w-md flex-col justify-center gap-6 px-4 py-12">
	<header class="text-center">
		<h1 class="text-5xl font-black tracking-tight">YT<span class="text-accent">MP</span></h1>
		<p class="mt-2 text-muted">One queue for everyone.</p>
	</header>

	{#if me}
		<div class="flex items-center gap-3 rounded-xl bg-surface px-4 py-3 ring-1 ring-line">
			<Icon name="person" size={20} class="text-muted" />
			<div class="min-w-0 flex-1">
				<p class="truncate font-medium">{me.account.displayName}</p>
				<p class="truncate text-sm text-muted">{me.account.id}</p>
			</div>
			<button class="text-sm text-muted underline" onclick={logout}>Log out</button>
		</div>
	{:else}
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
		{#each host?.authServers ?? [] as server (server.issuer)}
			<a href={identity.loginUrl(server)} class="-mt-3 text-center text-sm text-muted underline">
				Or log in with your account on {server.issuer}
			</a>
		{/each}
	{/if}

	{#if onThisPhone}
		<!-- On the phone itself: host here, see your rooms, find rooms nearby. -->
		<section class="flex flex-col gap-3 rounded-xl bg-surface p-5 ring-1 ring-line">
			<h2 class="text-lg font-bold">Host a room on this phone</h2>
			<input
				class="rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2"
				bind:value={roomName}
				maxlength="64"
				placeholder={defaultRoomName}
			/>
			<div class="grid grid-cols-2 gap-3">
				<button
					class="flex flex-col items-center gap-1 rounded-xl bg-raised p-3 disabled:opacity-40"
					disabled={busy || !validName}
					onclick={() => hostRoom('private')}
				>
					<Icon name="headphones" />
					<span class="font-medium">Solo</span>
					<span class="text-xs text-muted">Just you</span>
				</button>
				<button
					class="flex flex-col items-center gap-1 rounded-xl bg-accent p-3 disabled:opacity-40"
					disabled={busy || !validName}
					onclick={() => hostRoom('public')}
				>
					<Icon name="people" />
					<span class="font-medium">Party</span>
					<span class="text-xs text-white/80">Friends on this Wi-Fi join</span>
				</button>
			</div>
		</section>

		<section class="flex flex-col gap-2 rounded-xl bg-surface p-4 ring-1 ring-line">
			{#if host?.authServers.length && !editingAuth}
				<p class="text-sm">
					<span class="text-muted">Accounts:</span>
					{host.authServers[0].issuer}
					<button class="ml-1 text-muted underline" onclick={() => ((authUrl = host?.authServers[0].url ?? ''), (editingAuth = true))}>Change</button>
					·
					<button class="text-muted underline" disabled={busy} onclick={() => linkAccounts(null)}>Guests only</button>
				</p>
			{:else if editingAuth}
				<form
					class="flex gap-2"
					onsubmit={(e) => {
						e.preventDefault();
						linkAccounts(authUrl.trim());
					}}
				>
					<input
						class="min-w-0 flex-1 rounded-lg bg-raised px-3 py-2 outline-none ring-white/40 focus:ring-2"
						bind:value={authUrl}
						placeholder="https://ytmp.example.com"
						autocapitalize="none"
						spellcheck="false"
					/>
					<button class="rounded-full bg-raised px-4 text-sm hover:bg-line disabled:opacity-40" disabled={busy || !authUrl.trim()}>Use</button>
				</form>
				<p class="text-xs text-muted">Friends can then join with their account on that YTMP server.</p>
			{:else}
				<p class="text-sm text-muted">
					Only guests can join rooms on this phone.
					<button class="underline" onclick={() => ((authUrl = nativeBridge?.serverUrl() ?? ''), (editingAuth = true))}>Use accounts from a YTMP server</button>
				</p>
			{/if}
		</section>

		{#if myRooms.length > 0}
			<section class="flex flex-col gap-1">
				<h2 class="px-1 pb-1 text-sm font-medium tracking-wide text-muted uppercase">Your rooms on this phone</h2>
				{#each myRooms as room (room.code)}
					<div class="flex items-center gap-3 rounded-lg bg-surface px-3 py-2 ring-1 ring-line">
						<a href="/room/{room.code}" class="flex min-w-0 flex-1 items-center gap-3">
							<Icon name={room.visibility === 'private' ? 'headphones' : 'people'} size={20} class="text-muted" />
							<span class="min-w-0 flex-1 truncate">{room.name}</span>
							<span class="font-mono text-sm text-muted">{room.code}</span>
						</a>
						<button
							class="rounded-full p-1.5 text-muted hover:bg-line hover:text-white"
							aria-label="Close room"
							onclick={() => close(room)}
						>
							<Icon name="close" size={18} />
						</button>
					</div>
				{/each}
			</section>
		{/if}
	{:else if host}
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

		{#if host.canCreateRooms}
			<section class="flex flex-col gap-3 rounded-xl bg-surface p-5 ring-1 ring-line">
				<h2 class="text-lg font-bold">Host a room</h2>
				<input
					class="rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2"
					bind:value={roomName}
					maxlength="64"
					placeholder={defaultRoomName}
				/>
				<button
					class="rounded-full bg-accent py-3 font-medium disabled:opacity-40"
					disabled={busy || !validName}
					onclick={() => hostRoom('public')}
				>
					Host
				</button>
			</section>
		{/if}
	{/if}

	{#if nativeBridge}
		<section class="flex flex-col gap-1">
			<h2 class="px-1 pb-1 text-sm font-medium tracking-wide text-muted uppercase">Nearby rooms</h2>
			{#each nearby as room (room.url + room.code)}
				<button
					class="flex items-center gap-3 rounded-lg bg-surface px-3 py-3 text-left ring-1 ring-line disabled:opacity-40"
					disabled={!validName}
					onclick={() => joinNearby(room)}
				>
					<Icon name="people" size={20} class="text-muted" />
					<span class="min-w-0 flex-1 truncate">{room.name}</span>
					<span class="font-mono text-sm text-muted">{room.code}</span>
				</button>
			{:else}
				<p class="px-1 text-sm text-muted">No rooms found on this Wi-Fi.</p>
			{/each}
		</section>

		<p class="text-center text-sm text-muted">
			{#if onThisPhone}
				<button class="underline" onclick={() => nativeBridge?.openServer()}>Join a room on a server</button>
				·
				<button class="underline" onclick={() => nativeBridge?.changeServer()}>Change server</button>
			{:else}
				On {location.host} ·
				<button class="underline" onclick={() => nativeBridge?.openHome()}>Back to this phone</button>
			{/if}
		</p>
	{/if}

	{#if host?.kind === 'server'}
		<a href="/account" class="text-center text-sm text-muted underline">{me ? 'Your account' : 'Accounts on this server'}</a>
	{/if}

	{#if error}
		<p class="text-center text-accent">{error}</p>
	{/if}
</main>

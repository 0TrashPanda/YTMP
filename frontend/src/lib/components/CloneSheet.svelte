<script lang="ts">
	// "Clone room": a new room with a copy of this one (queue, history, autoplay, roles, where
	// the song is), here, on this phone or on your server. Optionally everyone is invited or
	// moved to it, and keeps their role (docs/features/room-lifecycle.md#cloning-a-room).
	import { positionNow } from '../sync';
	import { cloneToServer, createRoom } from '../api';
	import { identity } from '../account';
	import { nativeBridge } from '../native';
	import type { CreateRoomResponse, HostInfo, MigrationMode, RoomClone, RoomVisibility } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import { saved } from '../storage';
	import Icon from './Icon.svelte';

	let {
		room,
		host,
		onClose,
		onToast
	}: { room: RoomConnection; host: HostInfo | null; onClose: () => void; onToast: (text: string) => void } = $props();

	type Place = 'here' | 'phone' | 'server';

	const s = $derived(room.state!);
	const onPhonePage = location.hostname === '127.0.0.1' || location.hostname === 'localhost';
	const serverName = $derived(host?.authServers[0]?.url ? new URL(host.authServers[0].url).host : null);

	// Where a clone can go from this page.
	const places = $derived(
		(
			[
				['here', host?.kind === 'phone' ? 'On this phone' : `On ${location.host}`, !!host?.canCreateRooms],
				['phone', 'On this phone', !!nativeBridge?.cloneRoom && !onPhonePage],
				['server', `On your server${serverName ? ` (${serverName})` : ''}`, onPhonePage && !!identity.token && !!serverName]
			] as [Place, string, boolean][]
		).filter(([, , ok]) => ok)
	);
	// svelte-ignore state_referenced_locally
	let place = $state<Place | null>(places[0]?.[0] ?? null);
	// svelte-ignore state_referenced_locally
	let name = $state(s.room.name);
	// svelte-ignore state_referenced_locally
	let visibility = $state<RoomVisibility>(s.room.visibility);
	const soloPossible = $derived(place !== 'here' || !!host?.supportsPrivateRooms);
	$effect(() => {
		if (!soloPossible && visibility === 'private') visibility = 'public';
	});

	const othersHere = $derived(s.participants.filter((p) => p.id !== room.participantId && p.online && !p.screen).length);
	const canBring = $derived(room.can('send_migration') && s.room.visibility === 'public' && visibility === 'public' && othersHere > 0);
	let bring = $state<'none' | MigrationMode>('invite');
	let busy = $state(false);

	/** The room as this page sees it, as a clone. */
	function copy(): RoomClone {
		const playback = s.playback;
		const position = positionNow(playback, room.hostNow());
		const top = s.roles[0]?.id;
		return {
			queue: s.queue,
			history: s.history,
			current: s.nowPlaying?.item ?? null,
			positionMs: Math.max(0, Math.round(position)),
			autoplay: s.autoplay,
			autoplaySeed: s.autoplaySeed,
			roles: s.roles,
			settings: { ...s.settings, openElsewhere: null, alwaysOn: false },
			migrants: s.participants
				.filter((p) => p.tag && !p.screen)
				.map((p) => ({ tag: p.tag, id: p.id, name: p.name, roleId: p.isOwner && top ? top : p.roleId, accountId: p.accountId, allow: p.allow, deny: p.deny }))
		};
	}

	/** Makes the room: its code, the owner token, its link for others, and where this page opens it. */
	async function make(clone: RoomClone): Promise<{ created: CreateRoomResponse; open: string }> {
		const title = name.trim();
		if (place === 'phone') {
			const answer = JSON.parse(nativeBridge!.cloneRoom!(JSON.stringify({ name: title, visibility, clone })));
			if (answer.error) throw new Error(answer.error);
			const created = answer as CreateRoomResponse;
			// The phone's own page opens it; the owner token goes along in the link (it's for that page).
			return { created, open: `http://127.0.0.1:8765/room/${created.code}#owner=${encodeURIComponent(created.ownerToken)}` };
		}
		if (place === 'server') {
			const created = await cloneToServer(title, visibility, clone, identity.token!);
			return { created, open: `${created.url}#owner=${encodeURIComponent(created.ownerToken)}` };
		}
		const created = await createRoom(title, visibility, identity.token, clone);
		saved.setOwnerToken(created.code, created.ownerToken);
		return { created, open: `/room/${created.code}` };
	}

	async function clone() {
		if (!place || !name.trim()) return;
		busy = true;
		try {
			const { created, open } = await make(copy());
			const link = created.url ?? `${location.origin}/room/${created.code}`;
			if (canBring && bring !== 'none') {
				const error = await room.run({ kind: 'SendMigration', target: { url: link, code: created.code, name: name.trim() }, mode: bring });
				if (error) onToast(`The room is made, but: ${error.message}`);
			}
			// Me too, with my role and name (another host's page may not know me yet).
			const extra = [room.migrationKey ? `migrate=${encodeURIComponent(room.migrationKey)}` : '', room.me ? `name=${encodeURIComponent(room.me.name)}` : '']
				.filter(Boolean)
				.join('&');
			location.href = extra ? `${open}${open.includes('#') ? '&' : '#'}${extra}` : open;
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't clone the room");
			busy = false;
		}
	}
</script>

<div class="fixed inset-0 z-[70] flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={onClose}>
	<div
		class="flex max-h-[90%] w-full max-w-sm flex-col gap-4 overflow-y-auto rounded-t-2xl bg-surface p-6 ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label="Clone room"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		onkeydown={(e) => e.key === 'Escape' && onClose()}
	>
		<div class="flex items-center justify-between">
			<h2 class="text-lg font-bold">Clone room</h2>
			<button class="rounded-full p-1 text-muted hover:bg-raised" aria-label="Close" onclick={onClose}><Icon name="close" /></button>
		</div>
		<p class="text-sm text-muted">
			A new room with this one's queue, what played, autoplay and roles. It starts paused where this one is. This room stays as it is.
		</p>

		{#if !places.length}
			<p class="text-sm">
				You can't make rooms from this page. Open YTMP on your own phone, or this room on a YTMP server, to clone it there.
			</p>
		{:else}
			<div class="flex flex-col gap-2" role="radiogroup" aria-label="Where">
				<p class="text-sm font-medium">Where</p>
				{#each places as [id, label] (id)}
					<button
						class="rounded-lg px-3 py-2 text-left text-sm ring-1 {place === id ? 'bg-raised ring-white' : 'ring-line hover:bg-raised'}"
						role="radio"
						aria-checked={place === id}
						onclick={() => (place = id)}>{label}</button
					>
				{/each}
			</div>

			<label class="flex flex-col gap-1">
				<span class="text-sm font-medium">Name</span>
				<input class="rounded-lg bg-raised px-3 py-2 outline-none ring-white/40 focus:ring-2" maxlength="64" bind:value={name} />
			</label>

			<div class="flex rounded-full bg-raised p-1" role="radiogroup" aria-label="Kind of room">
				{#each [['public', 'Party'], ['private', 'Solo']] as const as [value, label] (value)}
					<button
						class="flex-1 rounded-full py-1.5 text-sm disabled:opacity-40 {visibility === value ? 'bg-white font-medium text-black' : 'text-muted'}"
						role="radio"
						aria-checked={visibility === value}
						disabled={value === 'private' && !soloPossible}
						onclick={() => (visibility = value)}>{label}</button
					>
				{/each}
			</div>

			{#if canBring}
				<div class="flex flex-col gap-2">
					<p class="text-sm font-medium">The others here ({othersHere})</p>
					{#each [['none', "Don't tell them"], ['invite', 'Invite them: they see a message and can follow'], ['forced', 'Move everyone there now']] as const as [value, label] (value)}
						<label class="flex items-center gap-2 text-sm">
							<input type="radio" name="bring" value={value} bind:group={bring} />
							{label}
						</label>
					{/each}
					<p class="text-xs text-muted">People keep their role in the new room.{bring === 'forced' ? ' The music stops here.' : ''}</p>
				</div>
			{/if}

			<button class="rounded-full bg-white py-3 font-medium text-black disabled:opacity-50" disabled={busy || !place || !name.trim()} onclick={clone}>
				{busy ? 'Cloning…' : 'Clone'}
			</button>
		{/if}
	</div>
</div>

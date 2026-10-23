<script lang="ts">
	// Server admin → Rooms: every room on the server, who's in it and what plays; Close.
	import { authServer } from '../../account';
	import { timeAgo } from '../../format';
	import type { AdminRoomInfo } from '../../protocol.gen';

	let { onToast }: { onToast: (text: string) => void } = $props();

	let rooms = $state<AdminRoomInfo[] | null>(null);
	let busy = $state(false);

	async function load() {
		try {
			rooms = await authServer.listRooms();
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't load the rooms");
		}
	}
	load();

	async function close(room: AdminRoomInfo) {
		const people = room.online ? ` ${room.online} ${room.online === 1 ? 'person is' : 'people are'} in it now.` : '';
		if (!confirm(`Close the room "${room.name}" (${room.code})? Its queue is gone for good.${people}`)) return;
		busy = true;
		try {
			await authServer.closeRoom(room.code);
			onToast('Room closed.');
			await load();
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't close it");
		} finally {
			busy = false;
		}
	}

	const owner = (id: string | null) => (id ? `${id.split('@')[0]}'s room` : "A guest's room");
</script>

<div class="flex items-center gap-3">
	<p class="flex-1 text-sm text-muted">
		{#if rooms}{rooms.length} {rooms.length === 1 ? 'room' : 'rooms'}, {rooms.reduce((n, r) => n + r.online, 0)} people in them now{/if}
	</p>
	<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={load}>Refresh</button>
</div>

{#if rooms}
	<section class="flex flex-col gap-1">
		{#each rooms as room (room.code)}
			<div class="flex items-center gap-3 rounded-lg bg-raised/60 px-3 py-2">
				<div class="min-w-0 flex-1">
					<p class="truncate">
						{room.name}
						<span class="font-mono text-sm text-muted">{room.code}</span>
						{#if room.visibility === 'private'}<span class="ml-1 rounded bg-white/15 px-1.5 py-0.5 text-xs">Solo</span>{/if}
						{#if room.alwaysOn}<span class="ml-1 rounded bg-white/15 px-1.5 py-0.5 text-xs">Always on</span>{/if}
					</p>
					<p class="truncate text-xs text-muted">
						{owner(room.owner)} ·
						{room.online ? `${room.online} here now` : `empty, last used ${timeAgo(room.lastActive)}`}
						{#if room.current}· {room.playing ? 'playing' : 'paused'}: {room.current}{/if}
						{#if room.queued}· {room.queued} queued{/if}
					</p>
				</div>
				<button class="shrink-0 rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-40" disabled={busy} onclick={() => close(room)}>Close</button>
			</div>
		{:else}
			<p class="px-1 text-sm text-muted">No rooms right now.</p>
		{/each}
	</section>
{/if}

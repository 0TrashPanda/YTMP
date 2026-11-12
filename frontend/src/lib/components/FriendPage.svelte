<script lang="ts">
	// A friend's or a group's page (docs/features/friends.md), opened from Settings → Friends: a
	// switch for whether what they share shows on your Home, and everything they ever shared with
	// you, newest first (also what you took off Home). In a room the songs play like other lists,
	// with Play for all of them; in Settings outside a room they only show.
	import { untrack } from 'svelte';
	import { friends, type FriendTarget } from '../friends.svelte';
	import { listening } from '../listening.svelte';
	import type { ReceivedShare } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import type { Toast } from '../songActions';
	import FriendShares from './FriendShares.svelte';
	import Icon from './Icon.svelte';
	import PlayAllButtons from './PlayAllButtons.svelte';
	import Switch from './settings/Switch.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let {
		target,
		room = null,
		onToast,
		onMenu
	}: {
		target: FriendTarget;
		/** Null (Settings outside a room): the songs only show. */
		room?: RoomConnection | null;
		onToast: Toast;
		onMenu?: (target: MenuTarget) => void;
	} = $props();

	let shares = $state<ReceivedShare[] | null>(null);
	let error = $state<string | null>(null);
	let busy = $state(false);

	const group = $derived(target.kind === 'group' ? (friends.overview?.groups.find((g) => g.id === target.id) ?? null) : null);
	const person = $derived(target.kind === 'friend' ? (friends.overview?.friends.find((f) => f.username === target.username) ?? null) : null);
	const name = $derived(group?.name ?? person?.displayName ?? target.name);
	const subtitle = $derived(
		target.kind === 'friend' ? target.username : group ? `${group.members.length} ${group.members.length === 1 ? 'person' : 'people'} · ${group.members.map((m) => m.displayName).join(', ')}` : 'Group'
	);
	const onHome = $derived(friends.onHome(target));

	$effect(() => {
		const t = target;
		untrack(() => {
			shares = null;
			error = null;
		});
		listening
			.load()
			.then(() => Promise.all([friends.overview ? null : friends.load(), friends.history(t)]))
			.then(([, list]) => {
				if (t === target) shares = list;
			})
			.catch((e) => {
				if (t === target) error = e instanceof Error ? e.message : "Couldn't load what they shared";
			});
	});

	async function toggle(show: boolean) {
		busy = true;
		try {
			await friends.showOnHome(target, show);
			onToast(show ? `${name} shows on your Home again` : `${name} won't show on your Home`);
		} catch (e) {
			onToast(e instanceof Error ? e.message : "That didn't work");
		} finally {
			busy = false;
		}
	}

	/** On a friend's page the group it came through; on a group's page who shared it. */
	const label = (share: ReceivedShare) => (target.kind === 'friend' ? (share.group ? `In ${share.group}` : '') : share.from.displayName);
</script>

<div class="flex flex-col gap-6 pb-4">
	<div class="flex items-center gap-4 px-2">
		<span class="grid h-20 w-20 shrink-0 place-items-center rounded-full bg-raised text-muted sm:h-28 sm:w-28">
			<Icon name={target.kind === 'friend' ? 'person' : 'people'} size={40} />
		</span>
		<div class="min-w-0 flex-1">
			<h1 class="truncate text-2xl font-bold sm:text-3xl">{name}</h1>
			<p class="line-clamp-2 text-sm text-muted">{subtitle}</p>
		</div>
	</div>

	<div class="flex items-center gap-4 rounded-xl bg-raised/50 px-4 py-3 ring-1 ring-line">
		<div class="min-w-0 flex-1">
			<div class="font-medium">Show on Home</div>
			<div class="text-sm text-muted">
				{target.kind === 'friend'
					? `Songs ${name} shares with you, also in groups, show on your Home under From your friends.`
					: `Songs shared in ${name} show on your Home under From your friends.`}
				They always stay here.
			</div>
		</div>
		<Switch checked={onHome} disabled={busy || !friends.overview} label="Show on Home" onchange={toggle} />
	</div>

	<section class="flex flex-col gap-2">
		<h2 class="px-2 text-lg font-bold">Shared with you</h2>
		{#if room && shares?.length}
			<div class="px-2">
				<PlayAllButtons {room} songs={shares.map((s) => s.song)} name="songs from {name}" {onToast} />
			</div>
		{/if}
		{#if error}
			<p class="px-2 text-sm text-muted">{error}</p>
		{:else if !shares}
			<p class="px-2 text-sm text-muted">Loading…</p>
		{:else if !shares.length}
			<p class="px-2 text-sm text-muted">
				{target.kind === 'friend' ? `Nothing yet. Songs ${name} shares with you show here.` : `Nothing yet. Songs shared in ${name} show here.`}
			</p>
		{:else}
			<FriendShares {room} {shares} list removable={false} {label} {onToast} {onMenu} />
		{/if}
	</section>
</div>

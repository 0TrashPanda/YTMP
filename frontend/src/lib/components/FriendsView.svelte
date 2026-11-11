<script lang="ts">
	// Your friends and groups (docs/features/friends.md), from Library, the avatar menu or Home's
	// "From your friends": add a friend by their username, say yes to requests, and make groups to
	// share songs with (like a hardcore group). Friends are people with an account on your server.
	import { listening } from '../listening.svelte';
	import { friends } from '../friends.svelte';
	import type { FriendGroup, Person } from '../protocol.gen';
	import Icon from './Icon.svelte';

	let { onToast }: { onToast: (text: string) => void } = $props();

	let error = $state<string | null>(null);
	let username = $state('');
	let busy = $state(false);
	/** The group being made (id null) or changed. */
	let editing = $state<{ id: string | null; name: string; members: string[] } | null>(null);

	const overview = $derived(friends.overview);
	const me = $derived(overview?.me.username ?? null);

	listening
		.load()
		.then(() => (listening.available ? friends.load() : null))
		.catch((e) => (error = e instanceof Error ? e.message : "Couldn't load your friends"));

	async function act(what: () => Promise<unknown>, done?: string) {
		busy = true;
		try {
			await what();
			if (done) onToast(done);
			error = null;
		} catch (e) {
			onToast(e instanceof Error ? e.message : "That didn't work");
		} finally {
			busy = false;
		}
	}

	async function add(event: SubmitEvent) {
		event.preventDefault();
		const name = username.trim();
		if (!name) return;
		await act(async () => {
			const next = await friends.add(name);
			const friend = next.friends.find((f) => f.username === name.toLowerCase().split('@')[0]);
			onToast(friend ? `You and ${friend.displayName} are friends` : `Asked ${name}: they'll see it in their Friends`);
			username = '';
		});
	}

	function startGroup(group?: FriendGroup) {
		editing = group ? { id: group.id, name: group.name, members: group.members.map((m) => m.username).filter((u) => u !== group.owner.username) } : { id: null, name: '', members: [] };
	}

	async function saveGroup(event: SubmitEvent) {
		event.preventDefault();
		const group = editing;
		if (!group || !group.name.trim()) return;
		await act(async () => {
			if (group.id) await friends.updateGroup(group.id, group.name, group.members);
			else await friends.createGroup(group.name, group.members);
			editing = null;
		}, group.id ? 'Group saved' : `Made ${group.name.trim()}`);
	}

	/** Who can be in the group being changed: your friends, and anyone in it already. */
	const candidates = $derived.by((): Person[] => {
		const list = [...(overview?.friends ?? [])];
		const group = overview?.groups.find((g) => g.id === editing?.id);
		for (const m of group?.members ?? []) if (m.username !== group?.owner.username && !list.some((p) => p.username === m.username)) list.push(m);
		return list;
	});

	const toggle = (list: string[], value: string) => (list.includes(value) ? list.filter((v) => v !== value) : [...list, value]);
</script>

{#snippet avatar(icon: 'person' | 'people')}
	<span class="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-raised text-muted"><Icon name={icon} size={22} /></span>
{/snippet}

<div class="flex flex-col gap-6 pb-4">
	<h1 class="px-2 text-2xl font-bold sm:text-3xl">Friends</h1>

	{#if !listening.available}
		<p class="px-2 text-muted">Log in with your YTMP account to add friends and share songs with them.</p>
	{:else if !overview}
		<p class="px-2 text-muted">{error ?? 'Loading…'}</p>
	{:else}
		<form class="mx-2 flex gap-2 sm:max-w-md" onsubmit={add}>
			<input
				class="min-w-0 flex-1 rounded-lg bg-raised px-4 py-2 outline-none ring-white/40 placeholder:text-muted focus:ring-2"
				bind:value={username}
				placeholder="Add a friend by username"
				aria-label="Username"
				autocapitalize="none"
				spellcheck="false"
			/>
			<button class="rounded-full bg-white px-4 font-medium text-black disabled:opacity-40" disabled={busy || !username.trim()}>Add</button>
		</form>
		{#if me}<p class="-mt-4 px-2 text-xs text-muted">Your username is <b>{me}</b>: friends add you with it.</p>{/if}

		{#if overview.incoming.length}
			<section class="flex flex-col">
				<h2 class="px-2 pb-1 text-lg font-bold">Asking to be your friend</h2>
				{#each overview.incoming as person (person.username)}
					<div class="flex items-center gap-3 px-2 py-2">
						{@render avatar('person')}
						<span class="min-w-0 flex-1"><span class="block truncate font-medium">{person.displayName}</span><span class="block truncate text-xs text-muted">{person.username}</span></span>
						<button class="rounded-full bg-white px-4 py-1.5 text-sm font-medium text-black disabled:opacity-40" disabled={busy} onclick={() => act(() => friends.add(person.username), `You and ${person.displayName} are friends`)}>Accept</button>
						<button class="rounded-full px-3 py-1.5 text-sm text-muted hover:bg-raised disabled:opacity-40" disabled={busy} onclick={() => act(() => friends.remove(person.username))}>Decline</button>
					</div>
				{/each}
			</section>
		{/if}

		<section class="flex flex-col">
			<div class="flex items-center gap-2 px-2 pb-1">
				<h2 class="flex-1 text-lg font-bold">Groups</h2>
				{#if !editing}
					<button class="flex items-center gap-1 rounded-full border border-line px-3 py-1 text-sm font-medium hover:bg-raised" onclick={() => startGroup()}><Icon name="add" size={18} />New group</button>
				{/if}
			</div>
			{#if editing}
				<form class="mx-2 flex flex-col gap-2 rounded-xl bg-raised/50 p-3 ring-1 ring-line" onsubmit={saveGroup}>
					<!-- svelte-ignore a11y_autofocus -->
					<input class="rounded-lg bg-raised px-3 py-2 outline-none ring-white/40 focus:ring-2" bind:value={editing.name} maxlength="64" placeholder="Group name, like Hardcore" autofocus />
					{#if candidates.length}
						<p class="pt-1 text-xs font-medium tracking-wide text-muted uppercase">Who's in it</p>
						{#each candidates as person (person.username)}
							{@const checked = editing.members.includes(person.username)}
							<button type="button" class="flex items-center gap-3 rounded-lg px-1 py-1.5 text-left hover:bg-raised" role="checkbox" aria-checked={checked} onclick={() => editing && (editing.members = toggle(editing.members, person.username))}>
								{@render avatar('person')}
								<span class="min-w-0 flex-1 truncate">{person.displayName}</span>
								<span class="grid h-6 w-6 shrink-0 place-items-center rounded-full border-2 {checked ? 'border-white bg-white text-black' : 'border-muted'}">
									{#if checked}<Icon name="check" size={16} />{/if}
								</span>
							</button>
						{/each}
					{:else}
						<p class="text-sm text-muted">Add friends first: a group is made of your friends.</p>
					{/if}
					<div class="flex justify-end gap-2 pt-1">
						<button type="button" class="rounded-full px-4 py-1.5 text-sm text-muted hover:bg-raised" onclick={() => (editing = null)}>Cancel</button>
						<button class="rounded-full bg-white px-4 py-1.5 text-sm font-medium text-black disabled:opacity-40" disabled={busy || !editing.name.trim()}>{editing.id ? 'Save' : 'Make group'}</button>
					</div>
				</form>
			{/if}
			{#each overview.groups as group (group.id)}
				{@const mine = group.owner.username === me}
				<div class="flex items-center gap-3 px-2 py-2">
					{@render avatar('people')}
					<span class="min-w-0 flex-1">
						<span class="block truncate font-medium">{group.name}</span>
						<span class="block truncate text-xs text-muted">{group.members.map((m) => m.displayName).join(', ')}</span>
					</span>
					{#if mine}
						<button class="rounded-full px-3 py-1.5 text-sm text-muted hover:bg-raised" onclick={() => startGroup(group)}>Edit</button>
					{/if}
					<button
						class="rounded-full px-3 py-1.5 text-sm text-muted hover:bg-raised disabled:opacity-40"
						disabled={busy}
						onclick={() => confirm(mine ? `Delete ${group.name} for everyone in it?` : `Leave ${group.name}?`) && act(() => friends.leaveGroup(group.id), mine ? `Deleted ${group.name}` : `Left ${group.name}`)}
					>
						{mine ? 'Delete' : 'Leave'}
					</button>
				</div>
			{:else}
				{#if !editing}<p class="px-2 text-sm text-muted">Make a group, like your hardcore friends: a song shared with it shows on everyone's Home.</p>{/if}
			{/each}
		</section>

		<section class="flex flex-col">
			<h2 class="px-2 pb-1 text-lg font-bold">Friends</h2>
			{#each overview.friends as person (person.username)}
				<div class="flex items-center gap-3 px-2 py-2">
					{@render avatar('person')}
					<span class="min-w-0 flex-1"><span class="block truncate font-medium">{person.displayName}</span><span class="block truncate text-xs text-muted">{person.username}</span></span>
					<button class="rounded-full px-3 py-1.5 text-sm text-muted hover:bg-raised disabled:opacity-40" disabled={busy} onclick={() => confirm(`Remove ${person.displayName} as a friend?`) && act(() => friends.remove(person.username))}>Remove</button>
				</div>
			{:else}
				<p class="px-2 text-sm text-muted">No friends yet. Add one by their username: once they say yes, you can share songs with each other.</p>
			{/each}
			{#each overview.outgoing as person (person.username)}
				<div class="flex items-center gap-3 px-2 py-2 opacity-70">
					{@render avatar('person')}
					<span class="min-w-0 flex-1"><span class="block truncate font-medium">{person.displayName}</span><span class="block truncate text-xs text-muted">Asked · waiting for a yes</span></span>
					<button class="rounded-full px-3 py-1.5 text-sm text-muted hover:bg-raised disabled:opacity-40" disabled={busy} onclick={() => act(() => friends.remove(person.username))}>Cancel</button>
				</div>
			{/each}
		</section>
	{/if}
</div>

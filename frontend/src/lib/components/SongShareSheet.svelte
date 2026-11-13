<script lang="ts">
	// Share a song (the song menu's Share): hold another phone against this one, or send its link
	// with the phone's share sheet, or copy it. The link is the song's YouTube Music link
	// (share.svelte.ts): a friend with YTMP gets its song screen, one without it YouTube Music.
	// With your YTMP account also your groups and friends (docs/features/accounts/friends.md): ticked, it
	// shows on their Home under "From your friends", with a note if you like.
	import { modal } from '../modal';
	import { artistNames } from '../format';
	import { friends } from '../friends.svelte';
	import type { Song } from '../protocol.gen';
	import { copyLink, shareLink, songLink } from '../share.svelte';
	import type { Toast } from '../songActions';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import TapShareHint from './TapShareHint.svelte';

	let { song, onClose, onToast, onFriends }: { song: Song; onClose: () => void; onToast: Toast; /** Opens your friends and groups. */ onFriends?: () => void } = $props();

	const link = $derived(songLink(song) ?? '');

	let error = $state<string | null>(null);
	let groups = $state<string[]>([]);
	let people = $state<string[]>([]);
	let note = $state('');
	let sending = $state(false);
	const overview = $derived(friends.overview);
	const picked = $derived(groups.length + people.length);

	if (friends.available) friends.load().catch((e) => (error = e instanceof Error ? e.message : "Couldn't load your friends"));

	const toggle = (list: string[], value: string) => (list.includes(value) ? list.filter((v) => v !== value) : [...list, value]);

	async function send() {
		sending = true;
		try {
			const shared = await friends.share(song, people, groups, note);
			onClose();
			const names = [...(overview?.groups.filter((g) => groups.includes(g.id)).map((g) => g.name) ?? []), ...(overview?.friends.filter((f) => people.includes(f.username)).map((f) => f.displayName) ?? [])];
			onToast(`Shared with ${names.length === 1 ? names[0] : `${shared.recipients} people`}`, { label: 'Undo', run: () => void friends.dismiss(shared.id).then(() => onToast('Not shared')) });
		} catch (e) {
			error = e instanceof Error ? e.message : "Couldn't share it";
		} finally {
			sending = false;
		}
	}
</script>

{#snippet row(checked: boolean, label: string, sub: string, icon: 'people' | 'person', toggleIt: () => void)}
	<button class="flex w-full items-center gap-3 rounded-lg px-1 py-2 text-left hover:bg-raised" role="checkbox" aria-checked={checked} onclick={toggleIt}>
		<span class="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-raised text-muted"><Icon name={icon} size={22} /></span>
		<span class="min-w-0 flex-1">
			<span class="block truncate font-medium">{label}</span>
			<span class="block truncate text-xs text-muted">{sub}</span>
		</span>
		<span class="grid h-6 w-6 shrink-0 place-items-center rounded-full border-2 {checked ? 'border-white bg-white text-black' : 'border-muted'}">
			{#if checked}<Icon name="check" size={16} />{/if}
		</span>
	</button>
{/snippet}

<div class="fixed inset-0 z-[60] flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={onClose}>
	<!-- Keys: use:modal (Escape, Tab). The click only keeps it from reaching the backdrop. -->
	<!-- svelte-ignore a11y_click_events_have_key_events -->
	<div
		class="flex max-h-screen-90 w-full max-w-sm flex-col gap-4 overflow-y-auto rounded-t-2xl bg-surface p-4 pb-[max(1rem,env(safe-area-inset-bottom))] ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label="Share song"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		use:modal={() => onClose()}
	>
		<div class="flex items-center justify-between">
			<h2 class="font-bold">Share song</h2>
			<button class="rounded-full p-1 text-muted hover:bg-raised" aria-label="Close" onclick={onClose}><Icon name="close" /></button>
		</div>
		<div class="flex items-center gap-3">
			<Art {song} size={96} class="h-12 w-12" />
			<div class="min-w-0 flex-1">
				<p class="truncate font-medium">{song.title}</p>
				<p class="truncate text-sm text-muted">{song.podcast?.name ?? artistNames(song)}</p>
			</div>
		</div>
		{#if link}
			<TapShareHint url={link} what="this song" />
			<div class="grid grid-cols-2 gap-2">
				<button class="flex items-center justify-center gap-2 rounded-full bg-raised py-2.5 text-sm font-medium hover:bg-line" onclick={() => (onClose(), void shareLink(song.title, link, onToast))}>
					<Icon name="share" size={20} /> Send…
				</button>
				<button class="flex items-center justify-center gap-2 rounded-full bg-raised py-2.5 text-sm font-medium hover:bg-line" onclick={() => (onClose(), void copyLink(link, onToast))}>
					<Icon name="copy" size={20} /> Copy link
				</button>
			</div>
		{:else if !friends.available}
			<p class="text-sm text-muted">This song is only on this device, so it can't be shared.</p>
		{/if}

		{#if friends.available}
			<div class="flex flex-col">
				{#if !overview}
					<p class="text-sm text-muted">{error ?? 'Loading your friends…'}</p>
				{:else}
					{#if overview.groups.length}
						<p class="pb-1 text-xs font-medium tracking-wide text-muted uppercase">Groups</p>
						{#each overview.groups as group (group.id)}
							{@render row(groups.includes(group.id), group.name, `${group.members.length} people`, 'people', () => (groups = toggle(groups, group.id)))}
						{/each}
					{/if}
					{#if overview.friends.length}
						<p class="pt-2 pb-1 text-xs font-medium tracking-wide text-muted uppercase">Friends</p>
						{#each overview.friends as friend (friend.username)}
							{@render row(people.includes(friend.username), friend.displayName, friend.username, 'person', () => (people = toggle(people, friend.username)))}
						{/each}
					{/if}
					{#if !overview.groups.length && !overview.friends.length}
						<p class="text-sm text-muted">Add friends to share songs with them: they show on their Home.</p>
					{/if}
					{#if onFriends}
						<button class="flex w-full items-center gap-3 rounded-lg px-1 py-2 text-left text-sm text-muted hover:bg-raised hover:text-white" onclick={() => (onClose(), onFriends())}>
							<span class="grid h-10 w-10 place-items-center"><Icon name="add" size={22} /></span>
							<span>Friends and groups</span>
						</button>
					{/if}
				{/if}
			</div>
			{#if overview && picked}
				<form class="flex flex-col gap-2" onsubmit={(e) => (e.preventDefault(), void send())}>
					{#if error}<p class="text-sm text-accent">{error}</p>{/if}
					<div class="flex gap-2">
						<input class="min-w-0 flex-1 rounded-lg bg-raised px-3 py-2 outline-none ring-white/40 focus:ring-2" bind:value={note} maxlength="200" placeholder="Add a note (optional)" />
						<button class="rounded-full bg-white px-4 font-medium text-black disabled:opacity-40" disabled={sending}>Send</button>
					</div>
				</form>
			{/if}
		{/if}
	</div>
</div>

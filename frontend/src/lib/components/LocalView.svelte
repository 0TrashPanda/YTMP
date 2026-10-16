<script lang="ts">
	// The Local tab: music the host reaches itself, like a Raspberry Pi's music folder or the
	// music on this phone. Like YTM's library: albums, artists or songs, a search box for just
	// this music, and Shuffle all. Settings → Local music says which music shows here.
	import { getLocalLibrary } from '../api';
	import { itemCard, type PageHandlers } from '../cards';
	import { fuzzyFind } from '../format';
	import { local, loadLocal } from '../local.svelte';
	import type { LocalLibraryPage, SearchItem, Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import CardGrid, { type Card } from './CardGrid.svelte';
	import Icon from './Icon.svelte';
	import type { MenuTarget } from './SongMenu.svelte';
	import SongList from './SongList.svelte';

	let {
		room,
		onToast,
		onMenu,
		...open
	}: { room: RoomConnection; onToast: (text: string) => void; onMenu: (target: MenuTarget) => void } & PageHandlers = $props();

	type Chip = 'albums' | 'artists' | 'songs';
	const chips: [Chip, string][] = [
		['albums', 'Albums'],
		['artists', 'Artists'],
		['songs', 'Songs']
	];
	let chip = $state<Chip>('albums');
	let query = $state('');
	let picked = $state<string | null>(null);
	let page = $state<LocalLibraryPage | null>(null);
	let error = $state<string | null>(null);

	/** Many songs make a long page: past this many, search narrows it down. */
	const MAX_SONGS = 300;

	$effect(() => {
		void loadLocal();
	});

	const libraries = $derived(local.libraries ?? []);
	const libraryId = $derived(libraries.find((l) => l.id === picked)?.id ?? libraries[0]?.id ?? null);

	$effect(() => {
		const id = libraryId;
		page = null;
		error = null;
		if (!id) return;
		getLocalLibrary(id)
			.then((p) => id === libraryId && (page = p))
			.catch((e) => (error = e instanceof Error ? e.message : "Couldn't load this music"));
	});

	const cards = (items: SearchItem[]): Card[] => items.flatMap((i) => itemCard(i, open) ?? []);
	const text = (card: Card) => `${card.title} ${card.subtitle ?? ''}`;
	const albums = $derived(page ? fuzzyFind(cards(page.albums), query, text) : []);
	const artists = $derived(page ? fuzzyFind(cards(page.artists), query, text) : []);
	const songs = $derived(page ? fuzzyFind(page.songs, query, (s: Song) => `${s.title} ${s.artists.map((a) => a.name).join(' ')} ${s.album?.name ?? ''}`) : []);

	/** Plays the whole library mixed: the first song now, the rest in autoplay (the queue stays as it is). */
	async function shuffleAll() {
		const all = [...(page?.songs ?? [])];
		for (let i = all.length - 1; i > 0; i--) {
			const j = Math.floor(Math.random() * (i + 1));
			[all[i], all[j]] = [all[j], all[i]];
		}
		const [first, ...rest] = all;
		if (!first) return;
		let e = await room.run({ kind: 'PlayNow', song: first });
		if (!e && rest.length) e = await room.run({ kind: 'AutoplaySongs', songs: rest.slice(0, 200) });
		if (e) onToast(e.message);
	}
</script>

<div class="flex flex-col gap-5 pb-4">
	{#if local.libraries === null}
		<div class="grid animate-pulse grid-cols-[repeat(auto-fill,minmax(9rem,1fr))] gap-4 px-2">
			{#each [0, 1, 2, 3, 4, 5] as i (i)}
				<div class="aspect-square rounded-md bg-raised"></div>
			{/each}
		</div>
	{:else if !libraries.length}
		<div class="flex flex-col items-center gap-4 px-6 py-16 text-center">
			<Icon name="local" size={56} class="text-muted" />
			<h2 class="text-xl font-bold">No music here</h2>
			<p class="max-w-sm text-muted">
				Music on this network (say a Raspberry Pi with your music) shows up here once it's shared with YTMP. Settings → Local music says which music this tab shows.
			</p>
		</div>
	{:else}
		{#if libraries.length > 1}
			<div class="flex gap-2 overflow-x-auto px-2">
				{#each libraries as library (library.id)}
					<button
						class="shrink-0 rounded-lg px-3 py-1.5 text-sm {library.id === libraryId ? 'bg-white text-black' : 'bg-raised hover:bg-line'}"
						onclick={() => (picked = library.id)}>{library.name}</button
					>
				{/each}
			</div>
		{/if}
		<label class="mx-2 flex items-center gap-3 rounded-lg bg-raised px-4 py-2 sm:max-w-md">
			<Icon name="search" size={22} class="shrink-0 text-muted" />
			<input
				class="min-w-0 flex-1 bg-transparent py-1 outline-none placeholder:text-muted"
				type="text"
				bind:value={query}
				placeholder="Search this music"
				aria-label="Search this music"
				autocapitalize="none"
				spellcheck="false"
				enterkeyhint="search"
			/>
			{#if query}
				<button class="-my-1 -mr-2 rounded-full p-1.5 text-muted hover:text-white" aria-label="Clear" onclick={() => (query = '')}><Icon name="close" size={20} /></button>
			{/if}
		</label>
		<div class="flex flex-wrap items-center gap-2 px-2">
			{#each chips as [value, label] (value)}
				<button class="rounded-lg px-3 py-1.5 text-sm {chip === value ? 'bg-white text-black' : 'bg-raised hover:bg-line'}" onclick={() => (chip = value)}>{label}</button>
			{/each}
			{#if room.can('play_now')}
				<button class="ml-auto flex items-center gap-2 rounded-full bg-white px-4 py-1.5 text-sm font-medium text-black disabled:opacity-40" disabled={!page?.songs.length} onclick={shuffleAll}>
					<Icon name="shuffle" size={18} /> Shuffle all
				</button>
			{/if}
		</div>

		{#if error}
			<p class="px-2 text-muted">{error}</p>
		{:else if !page}
			<div class="grid animate-pulse grid-cols-[repeat(auto-fill,minmax(9rem,1fr))] gap-4 px-2">
				{#each [0, 1, 2, 3, 4, 5] as i (i)}
					<div class="aspect-square rounded-md bg-raised"></div>
				{/each}
			</div>
		{:else if chip === 'albums'}
			{#if albums.length}<CardGrid cards={albums} />{:else}<p class="px-2 text-muted">{query.trim() ? `No albums match “${query.trim()}”.` : 'No albums.'}</p>{/if}
		{:else if chip === 'artists'}
			{#if artists.length}<CardGrid cards={artists} />{:else}<p class="px-2 text-muted">{query.trim() ? `No artists match “${query.trim()}”.` : 'No artists.'}</p>{/if}
		{:else if songs.length}
			<SongList {room} songs={songs.slice(0, MAX_SONGS)} {onToast} {onMenu} />
			{#if songs.length > MAX_SONGS}
				<p class="px-2 text-sm text-muted">{MAX_SONGS} of {songs.length} songs. Search to find the others.</p>
			{/if}
		{:else}
			<p class="px-2 text-muted">{query.trim() ? `No songs match “${query.trim()}”.` : 'No songs.'}</p>
		{/if}
	{/if}
</div>

<script lang="ts">
	// The Local tab: music the host reaches itself, like a Raspberry Pi's music folder or the
	// music on this phone. Like YTM's library: albums, artists, songs, genres or folders, a search
	// box for just this music, and Shuffle all. It stays where you were (localPlace). Settings →
	// Local music says which music shows here.
	import { getLocalLibrary } from '../api';
	import { itemCard, type PageHandlers } from '../cards';
	import { fuzzyFind } from '../format';
	import { local, loadLocal, localPlace, subfolders, type LocalChip } from '../local.svelte';
	import type { LocalGroup, LocalLibraryPage, SearchItem, Song } from '../protocol.gen';
	import { settings } from '../settings.svelte';
	import type { RoomConnection } from '../room.svelte';
	import CardGrid, { type Card } from './CardGrid.svelte';
	import Icon from './Icon.svelte';
	import type { MenuTarget } from './SongMenu.svelte';
	import SongList from './SongList.svelte';

	let {
		room,
		onToast,
		onMenu,
		onGenre,
		onFolder,
		...open
	}: {
		room: RoomConnection;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
		onGenre: (library: string, name: string) => void;
		onFolder: (library: string, path: string) => void;
	} & PageHandlers = $props();

	const chips: [LocalChip, string][] = [
		['albums', 'Albums'],
		['artists', 'Artists'],
		['songs', 'Songs'],
		['genres', 'Genres'],
		['folders', 'Folders']
	];
	let page = $state<LocalLibraryPage | null>(null);
	let error = $state<string | null>(null);

	/** Many songs make a long page: this many at first, and this many more with Load more. */
	const pageSize = settings.pageSize.value;
	const shown = $derived(Math.max(localPlace.songsShown, pageSize));

	$effect(() => {
		void loadLocal();
	});

	const libraries = $derived(local.libraries ?? []);
	const libraryId = $derived(libraries.find((l) => l.id === localPlace.library)?.id ?? libraries[0]?.id ?? null);

	$effect(() => {
		const id = libraryId;
		page = null;
		error = null;
		if (!id) return;
		getLocalLibrary(id)
			.then((p) => id === libraryId && (page = p))
			.catch((e) => (error = e instanceof Error ? e.message : "Couldn't load this music"));
	});

	const query = $derived(localPlace.query);
	const cards = (items: SearchItem[]): Card[] => items.flatMap((i) => itemCard(i, open) ?? []);
	const text = (card: Card) => `${card.title} ${card.subtitle ?? ''}`;
	const albums = $derived(page ? fuzzyFind(cards(page.albums), query, text) : []);
	const artists = $derived(page ? fuzzyFind(cards(page.artists), query, text) : []);
	const songs = $derived(page ? fuzzyFind(page.songs, query, (s: Song) => `${s.title} ${s.artists.map((a) => a.name).join(' ')} ${s.album?.name ?? ''}`) : []);
	const genres = $derived(page ? fuzzyFind(page.genres, query, (g: LocalGroup) => g.name) : []);
	/**
	 * Where the folder list starts: the top, or - when the top holds nothing but one folder (the
	 * phone's "Music", say) - inside that folder, and so on while it's the only one.
	 */
	const base = $derived.by(() => {
		if (!page) return '';
		let path = '';
		for (;;) {
			const subs = subfolders(page.folders, path);
			const direct = page.folders.find((f) => f.name === path)?.songs.length ?? 0;
			if (subs.length !== 1 || direct > 0) return path;
			path = subs[0].path;
		}
	});
	// The folders at that place; searching finds folders at any depth (shown with their path).
	const folders = $derived.by(() => {
		if (!page) return [];
		if (!query.trim()) return subfolders(page.folders, base);
		const all = page.folders.filter((f) => f.name).map((f) => ({ path: f.name, name: f.name, songs: f.songs.length }));
		return fuzzyFind(all, query, (f) => f.path);
	});
	/** Songs right in that folder, outside any folder inside it. */
	const looseSongs = $derived(query.trim() ? [] : (page?.folders.find((f) => f.name === base)?.songs ?? []).map((i) => page!.songs[i]));

	// Back where you were: the chip picked is in view in its row (only sideways, the page stays).
	let chipRow = $state<HTMLElement>();
	$effect(() => {
		const picked = chipRow?.querySelector<HTMLElement>('.bg-white');
		if (chipRow && picked) chipRow.scrollLeft = picked.offsetLeft - chipRow.offsetLeft - 8;
	});

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
		if (e && !room.wasNoticed(e)) onToast(e.message);
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
						onclick={() => ((localPlace.library = library.id), (localPlace.songsShown = 0))}>{library.name}</button
					>
				{/each}
			</div>
		{/if}
		<label class="mx-2 flex items-center gap-3 rounded-lg bg-raised px-4 py-2 sm:max-w-md">
			<Icon name="search" size={22} class="shrink-0 text-muted" />
			<input
				class="min-w-0 flex-1 bg-transparent py-1 outline-none placeholder:text-muted"
				type="text"
				bind:value={localPlace.query}
				placeholder="Search this music"
				aria-label="Search this music"
				autocapitalize="none"
				spellcheck="false"
				enterkeyhint="search"
			/>
			{#if query}
				<button class="-my-1 -mr-2 rounded-full p-1.5 text-muted hover:text-white" aria-label="Clear" onclick={() => (localPlace.query = '')}><Icon name="close" size={20} /></button>
			{/if}
		</label>
		<!-- One row of chips, sideways on phones (like YTM's); Shuffle all next to it. -->
		<div class="flex items-center gap-2 px-2">
			<div bind:this={chipRow} class="flex min-w-0 flex-1 gap-2 overflow-x-auto [scrollbar-width:none]">
				{#each chips as [value, label] (value)}
					<button class="shrink-0 rounded-lg px-3 py-1.5 text-sm {localPlace.chip === value ? 'bg-white text-black' : 'bg-raised hover:bg-line'}" onclick={() => (localPlace.chip = value)}>{label}</button>
				{/each}
			</div>
			{#if room.can('play_now')}
				<button
					class="flex shrink-0 items-center gap-2 rounded-full bg-white px-4 py-1.5 text-sm font-medium text-black disabled:opacity-40 max-sm:px-2"
					disabled={!page?.songs.length}
					aria-label="Shuffle all"
					title="Shuffle all"
					onclick={shuffleAll}
				>
					<Icon name="shuffle" size={18} /> <span class="max-sm:hidden">Shuffle all</span>
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
		{:else if localPlace.chip === 'albums'}
			{#if albums.length}<CardGrid cards={albums} />{:else}<p class="px-2 text-muted">{query.trim() ? `No albums match “${query.trim()}”.` : 'No albums.'}</p>{/if}
		{:else if localPlace.chip === 'artists'}
			{#if artists.length}<CardGrid cards={artists} />{:else}<p class="px-2 text-muted">{query.trim() ? `No artists match “${query.trim()}”.` : 'No artists.'}</p>{/if}
		{:else if localPlace.chip === 'genres'}
			{#if genres.length}
				<div class="flex flex-col gap-1">
					{#each genres as genre (genre.name)}
						<button class="flex items-center gap-3 rounded-md px-2 py-2 text-left hover:bg-raised" onclick={() => libraryId && onGenre(libraryId, genre.name)}>
							<span class="grid h-12 w-12 shrink-0 place-items-center rounded bg-raised"><Icon name="note" class="text-muted" /></span>
							<span class="min-w-0 flex-1 truncate font-medium">{genre.name}</span>
							<span class="text-sm text-muted">{genre.songs.length} {genre.songs.length === 1 ? 'song' : 'songs'}</span>
						</button>
					{/each}
				</div>
			{:else}
				<p class="px-2 text-muted">{query.trim() ? `No genres match “${query.trim()}”.` : 'No genres: the songs have none in their tags.'}</p>
			{/if}
		{:else if localPlace.chip === 'folders'}
			{#if base && !query.trim()}
				<p class="truncate px-2 text-sm text-muted">{base}</p>
			{/if}
			{#if folders.length || looseSongs.length}
				<div class="flex flex-col gap-1">
					{#each folders as folder (folder.path)}
						<button class="flex items-center gap-3 rounded-md px-2 py-2 text-left hover:bg-raised" onclick={() => libraryId && onFolder(libraryId, folder.path)}>
							<span class="grid h-12 w-12 shrink-0 place-items-center rounded bg-raised"><Icon name="local" class="text-muted" /></span>
							<span class="min-w-0 flex-1 truncate font-medium">{folder.name}</span>
							<span class="text-sm text-muted">{folder.songs} {folder.songs === 1 ? 'song' : 'songs'}</span>
						</button>
					{/each}
				</div>
				{#if looseSongs.length}
					<SongList {room} songs={looseSongs.slice(0, shown)} {onToast} {onMenu} />
				{/if}
			{:else}
				<p class="px-2 text-muted">{query.trim() ? `No folders match “${query.trim()}”.` : 'No folders.'}</p>
			{/if}
		{:else if songs.length}
			<SongList {room} songs={songs.slice(0, shown)} {onToast} {onMenu} />
			{#if songs.length > shown}
				<div class="flex items-center gap-3 px-2">
					<p class="text-sm text-muted">{shown} of {songs.length} songs</p>
					<button class="rounded-full bg-raised px-4 py-1.5 text-sm hover:bg-line" onclick={() => (localPlace.songsShown = shown + pageSize)}>Load more</button>
				</div>
			{/if}
		{:else}
			<p class="px-2 text-muted">{query.trim() ? `No songs match “${query.trim()}”.` : 'No songs.'}</p>
		{/if}
	{/if}
</div>

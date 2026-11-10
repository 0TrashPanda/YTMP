<script lang="ts">
	// A genre, a folder or a playlist of local music (from the Local tab): its songs, and a
	// folder's folders. Play and the other buttons take all of it, also what's in the folders
	// inside. A playlist is one whose songs are all on the phone: it plays from there, offline too.
	import { getLocalLibrary } from '../api';
	import { songsUnder, subfolders } from '../local.svelte';
	import { thumbUrl } from '../images.svelte';
	import type { LocalLibraryPage } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import { settings } from '../settings.svelte';
	import Icon from './Icon.svelte';
	import Skeleton from './Skeleton.svelte';
	import PlayAllButtons from './PlayAllButtons.svelte';
	import type { MenuTarget } from './SongMenu.svelte';
	import SongList from './SongList.svelte';

	let {
		room,
		library,
		genre,
		folder,
		playlist,
		onFolder,
		onToast,
		onMenu
	}: {
		room: RoomConnection;
		library: string;
		/** One of these: the genre's name, the folder's path ("" is the top), or the playlist's ID. */
		genre?: string;
		folder?: string;
		playlist?: string;
		onFolder: (path: string) => void;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
	} = $props();

	let page = $state<LocalLibraryPage | null>(null);
	let error = $state<string | null>(null);
	$effect(() => {
		getLocalLibrary(library)
			.then((p) => (page = p))
			.catch((e) => (error = e instanceof Error ? e.message : "Couldn't load this music"));
	});

	const pageSize = settings.pageSize.value;
	let shown = $state(pageSize);

	const list = $derived(playlist !== undefined ? (page?.playlists.find((p) => p.id === playlist) ?? null) : null);
	const title = $derived(playlist !== undefined ? (list?.title ?? (page ? 'Playlist' : '')) : (genre ?? (folder ? folder.split('/').at(-1)! : 'Music folder')));
	const inside = $derived(page && folder !== undefined ? subfolders(page.folders, folder) : []);
	/** The songs listed here: the genre's, or the ones right in the folder. */
	const songs = $derived.by(() => {
		if (!page) return [];
		const places =
			playlist !== undefined ? list?.songs : genre !== undefined ? page.genres.find((g) => g.name === genre)?.songs : page.folders.find((f) => f.name === folder)?.songs;
		return (places ?? []).map((i) => page!.songs[i]);
	});
	/** Everything the buttons play: with a folder, also the songs in the folders inside it. */
	const all = $derived(page && folder !== undefined ? songsUnder(page.folders, folder).map((i) => page!.songs[i]) : songs);
</script>

<div class="flex flex-col gap-6">
	<header class="flex flex-col gap-1 px-2">
		{#if list && thumbUrl(list.thumbnails, 544)}
			<img src={thumbUrl(list.thumbnails, 544)} alt="" class="mb-4 aspect-square w-48 rounded-lg object-cover shadow-2xl sm:w-56" />
		{/if}
		<p class="text-xs font-medium tracking-wide text-muted uppercase">{playlist !== undefined ? 'Playlist · on this phone' : genre !== undefined ? 'Genre' : 'Folder'}</p>
		<h1 class="text-3xl font-black break-words sm:text-4xl">{title}</h1>
		{#if list?.author}
			<p class="font-medium">{list.author}</p>
		{/if}
		{#if folder && folder.includes('/')}
			<p class="truncate text-sm text-muted">{folder}</p>
		{/if}
		{#if page}
			<p class="text-muted">
				{[inside.length ? `${inside.length} ${inside.length === 1 ? 'folder' : 'folders'}` : '', `${all.length} ${all.length === 1 ? 'song' : 'songs'}`].filter(Boolean).join(' • ')}
			</p>
		{/if}
		<!-- As many as a playlist plays at most. -->
		<PlayAllButtons {room} songs={page ? all : null} name={title} toAutoplay {onToast} />
	</header>

	{#if error}
		<p class="px-2 text-accent">{error}</p>
	{:else if !page}
		<Skeleton />
	{:else}
		{#if inside.length}
			<div class="flex flex-col gap-1">
				{#each inside as sub (sub.path)}
					<button class="flex items-center gap-3 rounded-md px-2 py-2 text-left hover:bg-raised" onclick={() => onFolder(sub.path)}>
						<span class="grid h-12 w-12 shrink-0 place-items-center rounded bg-raised"><Icon name="local" class="text-muted" /></span>
						<span class="min-w-0 flex-1 truncate font-medium">{sub.name}</span>
						<span class="text-sm text-muted">{sub.songs} {sub.songs === 1 ? 'song' : 'songs'}</span>
					</button>
				{/each}
			</div>
		{/if}
		{#if songs.length}
			<SongList {room} songs={songs.slice(0, shown)} {onToast} {onMenu} />
			{#if songs.length > shown}
				<div class="flex items-center gap-3 px-2">
					<p class="text-sm text-muted">{shown} of {songs.length} songs</p>
					<button class="rounded-full bg-raised px-4 py-1.5 text-sm hover:bg-line" onclick={() => (shown += pageSize)}>Load more</button>
				</div>
			{/if}
		{:else if playlist !== undefined}
			<p class="px-2 text-muted">Not all of this playlist's songs are on this phone now.</p>
		{:else if !inside.length}
			<p class="px-2 text-muted">No songs here.</p>
		{/if}
	{/if}
</div>

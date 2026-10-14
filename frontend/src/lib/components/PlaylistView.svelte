<script lang="ts">
	// A playlist page (community or YouTube Music's own, or one of yours): its songs, in order.
	import { thumbUrl } from '../images.svelte';
	import { deletePlaylist, getPlaylist, movePlaylistItem, renamePlaylist } from '../api';
	import Icon from './Icon.svelte';
	import { youtube } from '../youtube.svelte';
	import { formatTime } from '../format';
	import type { PlaylistPage } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import PlayAllButtons from './PlayAllButtons.svelte';
	import type { MenuTarget } from './SongMenu.svelte';
	import SongList from './SongList.svelte';

	let {
		room,
		id,
		title,
		personal = false,
		onToast,
		onMenu,
		onGone
	}: {
		room: RoomConnection;
		id: string;
		/** One of yours, from your library (through your YouTube Music sign-in). */
		personal?: boolean;
		/** Shown while loading. */
		title: string;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
		/** The playlist was deleted: leave its page. */
		onGone?: () => void;
	} = $props();

	let playlist = $state<PlaylistPage | null>(null);
	let error = $state<string | null>(null);
	let showDescription = $state(false);

	// Liked music (on Home, in Listen again) is always yours: only your sign-in can open it.
	const mine = $derived(personal || (id === 'LM' && !!youtube.account));

	let version = $state(0);
	$effect(() => {
		const playlistId = id;
		const own = mine;
		void version;
		error = null;
		getPlaylist(playlistId, own)
			// One of your private playlists (on Home or in search): through your sign-in.
			.catch((e) => {
				if (own || !youtube.account) throw e;
				return getPlaylist(playlistId, true).catch(() => Promise.reject(e));
			})
			.then((p) => playlistId === id && (playlist = p))
			.catch((e) => (error = e instanceof Error ? e.message : "Couldn't load the playlist"));
	});
	$effect(() => {
		void id;
		playlist = null;
	});
	/** Load it again (after removing a song). */
	const reload = () => version++;

	// Your own playlists: move songs, rename, delete. Liked music: remove (unlike).
	const likedMusic = $derived(mine && id === 'LM');
	// From your server in a room on another host, playlists can't be changed (only there).
	const editable = $derived(!!playlist?.owned && !youtube.from);
	let menuOpen = $state(false);
	let renaming = $state<string | null>(null);

	async function move(from: number, to: number) {
		const p = playlist;
		if (!p) return;
		const songs = [...p.songs];
		const items = [...p.itemIds];
		const [song] = songs.splice(from, 1);
		const [itemId] = items.splice(from, 1);
		songs.splice(to, 0, song);
		items.splice(to, 0, itemId);
		playlist = { ...p, songs, itemIds: items };
		try {
			await movePlaylistItem(p.id, itemId, items[to + 1] || null);
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't move it");
			reload();
		}
	}

	async function rename() {
		const title = renaming?.trim();
		renaming = null;
		if (!playlist || !title || title === playlist.title) return;
		try {
			await renamePlaylist(playlist.id, title);
			playlist = { ...playlist, title };
			onToast('Playlist renamed');
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't rename it");
		}
	}

	async function remove() {
		menuOpen = false;
		if (!playlist || !confirm(`Delete the playlist "${playlist.title}"? This can't be undone.`)) return;
		try {
			await deletePlaylist(playlist.id);
			onToast('Playlist deleted');
			onGone?.();
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't delete it");
		}
	}

	const total = $derived(playlist?.songs.reduce((sum, s) => sum + s.durationMs, 0) ?? 0);
</script>

<div class="flex flex-col gap-6">
	<header class="flex flex-col gap-5 px-2 sm:flex-row sm:items-end">
		<div class="aspect-square w-48 shrink-0 overflow-hidden rounded-lg bg-raised shadow-2xl sm:w-56">
			{#if playlist?.thumbnails.at(-1)}
				<img src={thumbUrl(playlist.thumbnails, 544)} alt="" referrerpolicy="no-referrer" class="h-full w-full object-cover" />
			{/if}
		</div>
		<div class="min-w-0">
			<p class="text-xs font-medium tracking-wide text-muted uppercase">Playlist</p>
			{#if renaming !== null}
				<form
					onsubmit={(e) => {
						e.preventDefault();
						rename();
					}}
				>
					<!-- svelte-ignore a11y_autofocus -->
					<input
						class="w-full rounded-lg bg-raised px-3 py-1 text-3xl font-black outline-none ring-white/40 focus:ring-2"
						bind:value={renaming}
						maxlength="150"
						autofocus
						onblur={rename}
						onkeydown={(e) => e.key === 'Escape' && (renaming = null)}
					/>
				</form>
			{:else}
				<div class="flex items-start gap-2">
					<h1 class="text-3xl font-black break-words sm:text-4xl">{playlist?.title ?? title}</h1>
					{#if editable}
						<div class="relative mt-1">
							<button class="rounded-full p-1.5 text-muted hover:bg-raised hover:text-white" aria-label="Playlist options" onclick={() => (menuOpen = !menuOpen)}>
								<Icon name="more" />
							</button>
							{#if menuOpen}
								<div class="absolute top-10 left-0 z-20 flex w-44 flex-col rounded-lg bg-surface p-1.5 shadow-xl ring-1 ring-line">
									<button class="rounded px-3 py-2 text-left text-sm hover:bg-raised" onclick={() => ((renaming = playlist?.title ?? ''), (menuOpen = false))}>Rename</button>
									<button class="rounded px-3 py-2 text-left text-sm text-accent hover:bg-raised" onclick={remove}>Delete playlist</button>
								</div>
							{/if}
						</div>
					{/if}
				</div>
			{/if}
			{#if playlist}
				<p class="mt-1 text-muted">
					{#if playlist.author}<span class="font-medium text-white">{playlist.author}</span> • {/if}{playlist.songs.length} songs • {formatTime(total)}
				</p>
				{#if playlist.description}
					<button class="mt-1 max-w-prose text-left text-sm text-muted {showDescription ? '' : 'line-clamp-2'}" onclick={() => (showDescription = !showDescription)}>
						{playlist.description}
					</button>
				{/if}
			{/if}
			<PlayAllButtons {room} songs={playlist?.songs ?? null} name={playlist?.title ?? title} toAutoplay {onToast} />
		</div>
	</header>

	{#if error}
		<p class="px-2 text-accent">{error}</p>
	{:else if !playlist}
		<p class="px-2 text-muted">Loading…</p>
	{:else if playlist.songs.length === 0}
		<p class="px-2 text-muted">This playlist has no songs that can be played.</p>
	{:else}
		<section class="flex flex-col gap-1">
			<SongList
				{room}
				songs={playlist.songs}
				playlist={editable || likedMusic ? { id: playlist.id, itemIds: playlist.itemIds, likedMusic, onChanged: reload } : undefined}
				onMove={editable ? move : undefined}
				{onToast}
				{onMenu}
			/>
		</section>
	{/if}
</div>

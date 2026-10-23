<script lang="ts">
	// A playlist page (community or YouTube Music's own, or one of yours): its songs, in order.
	// Yours (Liked music too) load in parts: more songs as you scroll down.
	import { thumbUrl } from '../images.svelte';
	import { deletePlaylist, getPlaylist, getPlaylistPart, movePlaylistItem, renamePlaylist } from '../api';
	import { saved } from '../storage';
	import Icon from './Icon.svelte';
	import { youtube } from '../youtube.svelte';
	import { formatTime } from '../format';
	import type { PlaylistPage, Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import PinButton from './PinButton.svelte';
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
	/** Load it again (after a move that didn't work). */
	const reload = () => version++;

	// Long lists: this many songs at first, this many more each time you scroll to the end;
	// your own playlists load the next part from YouTube Music when it's needed.
	const pageSize = saved.pageSize;
	let shown = $state(pageSize);
	let loadingPart = $state<Promise<void> | null>(null);
	let partFailed = $state(false);

	/** Adds the next part of the playlist (once at a time). */
	function loadPart(): Promise<void> {
		const p = playlist;
		if (!p?.more || partFailed) return Promise.resolve();
		loadingPart ??= getPlaylistPart(p.id, p.more)
			.then((next) => {
				// Songs moved or removed meanwhile are kept as they are now.
				if (playlist?.id === p.id && playlist.more === p.more)
					playlist = { ...playlist, songs: [...playlist.songs, ...next.songs], itemIds: [...playlist.itemIds, ...next.itemIds], more: next.more };
			})
			.catch((e) => {
				partFailed = true;
				onToast(e instanceof Error ? e.message : "Couldn't load more songs");
			})
			.finally(() => (loadingPart = null));
		return loadingPart;
	}

	// Part after part until there are enough to show.
	$effect(() => {
		if (playlist?.more && playlist.songs.length < shown && !partFailed && !loadingPart) void loadPart();
	});

	/** At the end of the list: more songs. */
	let end = $state<HTMLElement>();
	$effect(() => {
		if (!end) return;
		const watcher = new IntersectionObserver(
			(entries) => {
				if (entries.some((e) => e.isIntersecting) && playlist && (playlist.songs.length > shown || playlist.more)) shown += pageSize;
			},
			{ rootMargin: '800px 0px' }
		);
		watcher.observe(end);
		return () => watcher.disconnect();
	});

	/** All of it (at most 1000 songs, like other playlists), for Play and the other buttons. */
	async function whole(): Promise<Song[]> {
		while (playlist?.more && playlist.songs.length < 1000 && !partFailed) await loadPart();
		return playlist?.songs.slice(0, 1000) ?? [];
	}

	/** A song removed from it (Liked music: unliked): gone from the list right away. */
	function removed(index: number) {
		if (!playlist) return;
		const others = <T,>(list: T[]) => list.filter((_, i) => i !== index);
		playlist = { ...playlist, songs: others(playlist.songs), itemIds: others(playlist.itemIds), songCount: playlist.songCount && playlist.songCount - 1 };
	}

	// Your own playlists: move songs, rename, delete. Liked music: remove (unlike).
	const likedMusic = $derived(mine && id === 'LM');
	// From your server in a room on another host, playlists can't be changed (only there).
	const editable = $derived(!!playlist?.owned && !youtube.from);
	let menuOpen = $state(false);
	let renaming = $state<string | null>(null);

	async function move(from: number, to: number) {
		// To the end of what's loaded: the song after it is in the next part.
		if (to === (playlist?.songs.length ?? 0) - 1) await loadPart();
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
					{#if playlist.author}<span class="font-medium text-white">{playlist.author}</span> • {/if}{playlist.songCount ?? playlist.songs.length} songs{playlist.more ? '' : ` • ${formatTime(total)}`}
				</p>
				{#if playlist.description}
					<button class="mt-1 max-w-prose text-left text-sm text-muted {showDescription ? '' : 'line-clamp-2'}" onclick={() => (showDescription = !showDescription)}>
						{playlist.description}
					</button>
				{/if}
			{/if}
			<PlayAllButtons {room} songs={playlist?.songs ?? null} name={playlist?.title ?? title} toAutoplay whole={playlist?.more ? whole : undefined} {onToast}>
				<PinButton pin={playlist ? { kind: 'playlist', id, title: playlist.title, art: thumbUrl(playlist.thumbnails, 120), personal: personal || undefined } : null} {onToast} />
			</PlayAllButtons>
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
				songs={playlist.songs.slice(0, shown)}
				playlist={editable || likedMusic ? { id: playlist.id, itemIds: playlist.itemIds, likedMusic, onRemoved: removed } : undefined}
				onMove={editable ? move : undefined}
				{onToast}
				{onMenu}
			/>
			<div bind:this={end}></div>
			{#if playlist.songs.length > shown || playlist.more}
				<p class="px-2 py-2 text-sm text-muted">{partFailed ? "Couldn't load more songs." : 'Loading more…'}</p>
			{/if}
		</section>
	{/if}
</div>

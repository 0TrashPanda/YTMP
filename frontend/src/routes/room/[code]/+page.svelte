<script lang="ts">
	import { page } from '$app/state';
	import { pushState } from '$app/navigation';
	import { onDestroy, untrack } from 'svelte';
	import Icon from '../../../lib/components/Icon.svelte';
	import PlayerBar from '../../../lib/components/PlayerBar.svelte';
	import NowPlaying from '../../../lib/components/NowPlaying.svelte';
	import HomeView from '../../../lib/components/HomeView.svelte';
	import LibraryView from '../../../lib/components/LibraryView.svelte';
	import YoutubeSheet from '../../../lib/components/YoutubeSheet.svelte';
	import { youtube } from '../../../lib/youtube.svelte';
	import SearchResults from '../../../lib/components/SearchResults.svelte';
	import ArtistView from '../../../lib/components/ArtistView.svelte';
	import AlbumView from '../../../lib/components/AlbumView.svelte';
	import PlaylistView from '../../../lib/components/PlaylistView.svelte';
	import PodcastView from '../../../lib/components/PodcastView.svelte';
	import SearchBox from '../../../lib/components/SearchBox.svelte';
	import SongMenu, { type MenuTarget } from '../../../lib/components/SongMenu.svelte';
	import type { AlbumRef, ArtistRef, PodcastRef, SearchType, Song } from '../../../lib/protocol.gen';
	import ShareSheet from '../../../lib/components/ShareSheet.svelte';
	import RoomSettings from '../../../lib/components/settings/RoomSettings.svelte';
	import { getHost } from '../../../lib/api';
	import type { HostInfo } from '../../../lib/protocol.gen';
	import { createPlayer, type RoomPlayer } from '../../../lib/player.svelte';
	import { RoomConnection } from '../../../lib/room.svelte';
	import { saved } from '../../../lib/storage';
	import { identity } from '../../../lib/account';
	import type { Participant } from '../../../lib/protocol.gen';

	const code = page.params.code!.toUpperCase();

	// Logged in: the host takes the name from the account.
	const initialName = identity.stored?.account.displayName ?? saved.displayName;
	let name = $state(initialName);
	let room = $state<RoomConnection | null>(null);
	let player = $state<RoomPlayer | null>(null);
	// The search chip: stays as picked for the next search.
	let searchType = $state<SearchType>('all');
	let menu = $state<MenuTarget | null>(null);

	// What the main area shows besides the home page: search, find similar, artist, album,
	// playlist and podcast pages, as a stack (the back arrow pops it). The logo switches between home
	// and the last one, which stays as it was.
	type View =
		| { kind: 'search'; query: string }
		| { kind: 'similar'; song: Song }
		| { kind: 'artist'; id: string; name: string }
		| { kind: 'album'; id: string; title: string }
		| { kind: 'playlist'; id: string; title: string; personal?: boolean }
		| { kind: 'podcast'; id: string; title: string };
	let views = $state<View[]>([]);
	let browsing = $state(false);
	const view = $derived(browsing ? (views.at(-1) ?? null) : null);

	function show(next: View) {
		closePlayer();
		const top = views.at(-1);
		// A new search replaces the results on top instead of piling up.
		views = [...(next.kind === 'search' && top?.kind === 'search' ? views.slice(0, -1) : views), next].slice(-20);
		browsing = true;
	}

	/** The search shown in the search box: the last one. */
	const query = $derived(views.findLast((v): v is Extract<View, { kind: 'search' }> => v.kind === 'search')?.query ?? '');

	function back() {
		views = views.slice(0, -1);
		if (views.length === 0) browsing = false;
	}

	function toggleHome() {
		if (browsing) browsing = false;
		else if (section !== 'home') section = 'home';
		else if (views.length) browsing = true;
	}

	// Home and Library (the bottom bar, in the app with YouTube Music sign-in), under the pages above.
	let section = $state<'home' | 'library'>('home');
	let accountOpen = $state(false);

	function pickSection(next: 'home' | 'library') {
		section = next;
		browsing = false;
		scroller?.scrollTo({ top: 0 });
	}

	// The full player (album art, controls, Up next) over everything. A history entry, so
	// the phone's Back button closes it.
	const expanded = $derived(!!page.state.player);

	function openPlayer() {
		if (!page.state.player) pushState('', { player: true });
	}

	function closePlayer() {
		if (page.state.player) history.back();
	}

	function openArtist(artist: ArtistRef) {
		if (artist.id) show({ kind: 'artist', id: artist.id, name: artist.name });
		else searchFor(artist.name);
	}

	function openAlbum(album: AlbumRef, artist = '') {
		if (album.id) show({ kind: 'album', id: album.id, title: album.name });
		else searchFor(`${album.name} ${artist}`.trim());
	}

	/** [personal]: one of yours, from your library. */
	function openPlaylist(playlist: { id: string; title: string }, personal = false) {
		show({ kind: 'playlist', id: playlist.id, title: playlist.title, personal });
	}

	function openPodcast(podcast: PodcastRef) {
		show({ kind: 'podcast', id: podcast.id, title: podcast.name });
	}

	function searchFor(text: string) {
		show({ kind: 'search', query: text });
	}

	/** Right-click (or ⋮) on the current song outside the queue: the full player and the player bar. */
	function currentMenu(event: MouseEvent) {
		const item = room?.state?.nowPlaying?.item;
		if (!item) return;
		event.preventDefault();
		menu = { song: item.song, item, place: 'current', x: event.clientX, y: event.clientY };
	}
	let toasts = $state<{ id: number; text: string }[]>([]);
	let now = $state(Date.now());
	let toastId = 0;

	function start() {
		if (!identity.stored) saved.displayName = name.trim();
		room = new RoomConnection(code, name.trim());
		player = createPlayer(room);
		room.connect();
		youtube.load();
	}
	if (initialName.trim()) start();

	// Tick for the progress bar.
	const clock = setInterval(() => (now = Date.now()), 250);
	onDestroy(() => {
		clearInterval(clock);
		player?.destroy();
		room?.close();
	});

	// Follow the room: whenever the song or playback changes, re-sync the audio.
	$effect(() => {
		const s = room?.state;
		void [s?.nowPlaying?.item.itemId, s?.nowPlaying?.streamUrl, s?.playback.playing, s?.playback.positionMs, s?.playback.hostTimeMs];
		void room?.clockOffset;
		void room?.listeningAlone;
		void room?.participantId;
		untrack(() => player?.sync());
	});

	// In the app, "Play here" stays on between visits (a browser needs a click first).
	$effect(() => {
		if (room?.status === 'connected' && player?.canStartWithoutGesture && saved.listening) {
			untrack(() => !player!.enabled && player!.enable());
		}
	});

	const positionMs = $derived.by(() => {
		const playback = room?.state?.playback;
		if (!room || !playback) return 0;
		void now;
		return playback.playing ? playback.positionMs + (room.hostNow() - playback.hostTimeMs) : playback.positionMs;
	});

	// Phones: hide the header while scrolling down, show it again on any scroll up.
	let scroller = $state<HTMLElement>();
	let headerHidden = $state(false);
	let lastScrollTop = 0;
	function onScroll() {
		const top = scroller?.scrollTop ?? 0;
		if (top < lastScrollTop || top < 64) headerHidden = false;
		else if (top > lastScrollTop + 4) headerHidden = true;
		lastScrollTop = top;
	}

	function toast(text: string) {
		// Negative, so they never clash with the host's notice IDs.
		const id = -++toastId;
		toasts.push({ id, text });
		setTimeout(() => (toasts = toasts.filter((t) => t.id !== id)), 3000);
	}

	let sharing = $state(false);
	let settings = $state<'list' | 'members' | null>(null);
	let host = $state<HostInfo | null>(null);
	getHost().then((h) => (host = h)).catch(() => {});
</script>

<svelte:head>
	<title>{room?.state ? `${room.state.room.name} · YTMP` : 'YTMP'}</title>
</svelte:head>

{#if !room}
	<main class="mx-auto flex min-h-full max-w-sm flex-col justify-center gap-4 px-4">
		<h1 class="text-2xl font-bold">Join room <span class="font-mono">{code}</span></h1>
		<form
			class="flex flex-col gap-3"
			onsubmit={(e) => {
				e.preventDefault();
				if (name.trim()) start();
			}}
		>
			<input
				class="rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2"
				bind:value={name}
				maxlength="32"
				placeholder="Your name"
				autocomplete="nickname"
			/>
			{#if host?.authServers.length}
				<label class="flex items-center gap-2 text-sm text-muted">
					<input type="checkbox" class="accent-accent" checked={saved.hideFromHistory} onchange={(e) => (saved.hideFromHistory = e.currentTarget.checked)} />
					Keep me out of others' listening history
				</label>
			{/if}
			<button class="rounded-full bg-white py-3 font-medium text-black disabled:opacity-40" disabled={!name.trim()}>
				Join
			</button>
		</form>
	</main>
{:else if room.status === 'rejected'}
	<main class="mx-auto flex min-h-full max-w-sm flex-col items-center justify-center gap-4 px-4 text-center">
		<p class="text-lg">{room.rejectMessage}</p>
		<a href="/" class="rounded-full bg-white px-6 py-3 font-medium text-black">Back</a>
	</main>
{:else}
	<div class="flex h-full flex-col">
		<div class="relative min-h-0 flex-1">
			<!-- One scrolling page whose header slides away while scrolling down (phones). -->
			<div bind:this={scroller} onscroll={onScroll} class="h-full overflow-y-auto">
				<header
					class="sticky top-0 z-20 flex items-center gap-3 border-b border-line bg-bg/95 px-3 py-2 backdrop-blur transition-transform duration-200 sm:px-4 lg:translate-y-0
						{headerHidden ? '-translate-y-full' : ''}"
				>
					<button class="text-xl font-black tracking-tight" title={browsing ? 'Home' : 'Back to where you were'} onclick={toggleHome}>
						YT<span class="text-accent">MP</span>
					</button>
					<SearchBox {query} onopen={() => (headerHidden = false)} onsearch={searchFor} />
					<div class="ml-auto flex items-center gap-2">
						<span class="hidden truncate text-sm font-medium md:inline">{room.state?.room.name}</span>
						<!-- Phones: centered in the header. -->
						<button
							class="flex items-center gap-2 rounded-full bg-raised px-3 py-1.5 font-mono tracking-widest hover:bg-line max-sm:absolute max-sm:left-1/2 max-sm:-translate-x-1/2"
							onclick={() => (sharing = true)}
							title={room.state?.room.visibility === 'private' ? 'Solo room' : 'Share this room'}
						>
							{#if room.state?.room.visibility === 'private'}
								<Icon name="headphones" size={16} class="text-muted" />
							{/if}
							{code}
							<Icon name="share" size={16} class="text-muted" />
						</button>
						{#if youtube.available}
							<button class="shrink-0 rounded-full p-1" aria-label="YouTube Music account" onclick={() => (accountOpen = true)}>
								{#if youtube.account?.photoUrl}
									<img src={youtube.account.photoUrl} alt="" referrerpolicy="no-referrer" class="h-7 w-7 rounded-full" />
								{:else}
									<Icon name="person" size={22} class="text-muted" />
								{/if}
							</button>
						{/if}
						<button
							class="flex items-center gap-1 rounded-full px-2 py-1.5 text-sm text-muted hover:bg-raised hover:text-white"
							aria-label="People in this room"
							onclick={() => (settings = 'members')}
							title={room.state?.participants
								.filter((p: Participant) => p.online)
								.map((p: Participant) => p.name + (p.accountId ? ` (${p.accountId})` : '') + (p.listening ? ' 🎧' : ''))
								.join(', ')}
						>
							<Icon name="people" size={20} />
							<span class="hidden sm:inline">{room.state?.participants.filter((p: Participant) => p.online).length ?? 0}</span>
						</button>
						{#if room.can('change_settings') || room.can('edit_roles')}
							<button
								class="rounded-full p-1.5 text-muted hover:bg-raised hover:text-white"
								aria-label="Room settings"
								title="Room settings"
								onclick={() => (settings = 'list')}
							>
								<Icon name="settings" size={20} />
							</button>
						{/if}
					</div>
				</header>

				{#if room.status !== 'connected'}
					<div class="bg-raised px-4 py-1 text-center text-sm text-muted">
						{room.status === 'connecting' ? 'Connecting…' : 'Connection lost, reconnecting…'}
					</div>
				{/if}

				<main class="mx-auto w-full max-w-7xl p-3 sm:p-6">
					{#if view}
						{#if views.length > 1}
							<button class="mb-2 flex items-center gap-1 rounded-full px-2 py-1 text-sm text-muted hover:bg-raised hover:text-white" onclick={back}>
								<Icon name="back" size={20} />
								Back
							</button>
						{/if}
						{#if view.kind === 'search'}
							<SearchResults
								{room}
								query={view.query}
								type={searchType}
								onType={(t) => (searchType = t)}
								onToast={toast}
								onMenu={(target) => (menu = target)}
								onArtist={openArtist}
								onAlbum={(a) => openAlbum(a)}
								onPlaylist={openPlaylist}
								onPodcast={openPodcast}
							/>
						{:else if view.kind === 'similar'}
							<SearchResults {room} query="" similarTo={view.song} onToast={toast} onMenu={(target) => (menu = target)} />
						{:else if view.kind === 'artist'}
							<ArtistView {room} id={view.id} name={view.name} onToast={toast} onMenu={(target) => (menu = target)} onAlbum={(a) => openAlbum({ id: a.id, name: a.title })} />
						{:else if view.kind === 'album'}
							<AlbumView {room} id={view.id} title={view.title} onToast={toast} onMenu={(target) => (menu = target)} onArtist={openArtist} />
						{:else if view.kind === 'playlist'}
							<PlaylistView {room} id={view.id} title={view.title} personal={view.personal} onToast={toast} onMenu={(target) => (menu = target)} />
						{:else if view.kind === 'podcast'}
							<PodcastView {room} id={view.id} title={view.title} onToast={toast} onMenu={(target) => (menu = target)} />
						{/if}
					{:else if section === 'library'}
						<LibraryView
							onToast={toast}
							onArtist={openArtist}
							onAlbum={(a) => openAlbum(a)}
							onPlaylist={(p) => openPlaylist(p, true)}
							onPodcast={openPodcast}
						/>
					{:else}
						<!-- Signing in or out changes the suggestions. -->
						{#key youtube.version}
							<HomeView
								{room}
								onToast={toast}
								onMenu={(target) => (menu = target)}
								onArtist={openArtist}
								onAlbum={(a) => openAlbum(a)}
								onPlaylist={openPlaylist}
								onPodcast={openPodcast}
							/>
						{/key}
					{/if}
				</main>
			</div>

			{#if expanded && player}
				<NowPlaying
					{room}
					{player}
					{positionMs}
					onClose={closePlayer}
					onToast={toast}
					onMenu={(target) => (menu = target)}
					onSongMenu={currentMenu}
					onArtist={openArtist}
					onAlbum={(a) => openAlbum(a, room?.state?.nowPlaying?.item.song.artists[0]?.name)}
					onPodcast={openPodcast}
				/>
			{/if}
		</div>

		{#if player}
			<PlayerBar {room} {player} {positionMs} {expanded} onExpand={(open) => (open ? openPlayer() : closePlayer())} onToast={toast} onSongMenu={currentMenu} />
		{/if}
		{#if youtube.available}
			<!-- Like YTM's bottom bar. -->
			<nav class="flex shrink-0 bg-surface">
				{#each [['home', 'Home'], ['library', 'Library']] as const as [value, label] (value)}
					{@const active = section === value && !browsing}
					<button class="flex flex-1 flex-col items-center gap-0.5 py-2 text-xs {active ? 'text-white' : 'text-muted'}" aria-current={active ? 'page' : undefined} onclick={() => pickSection(value)}>
						<Icon name={value} size={24} />
						{label}
					</button>
				{/each}
			</nav>
		{/if}
	</div>

	{#if menu}
		<SongMenu
			{room}
			target={menu}
			onClose={() => (menu = null)}
			onToast={toast}
			onFindSimilar={(song) => show({ kind: 'similar', song })}
			onArtist={openArtist}
			onAlbum={openAlbum}
			onPodcast={openPodcast}
		/>
	{/if}

	{#if settings && room.state}
		<RoomSettings {room} start={settings} onClose={() => (settings = null)} />
	{/if}

	{#if accountOpen}
		<YoutubeSheet onClose={() => (accountOpen = false)} onToast={toast} />
	{/if}

	{#if sharing}
		<ShareSheet {room} {host} onClose={() => (sharing = false)} onToast={toast} />
	{/if}

	<div class="pointer-events-none fixed inset-x-0 bottom-28 z-50 flex flex-col items-center gap-2 px-4">
		{#each [...room.notices, ...toasts] as message (message.id + message.text)}
			<button
				class="pointer-events-auto rounded-lg bg-white px-4 py-2 text-sm text-black shadow-lg"
				onclick={() => room?.dismissNotice(message.id)}
			>
				{message.text}
			</button>
		{/each}
	</div>
{/if}

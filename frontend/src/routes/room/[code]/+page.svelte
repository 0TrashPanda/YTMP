<script lang="ts">
	import { page } from '$app/state';
	import { onDestroy, untrack } from 'svelte';
	import Art from '../../../lib/components/Art.svelte';
	import Icon from '../../../lib/components/Icon.svelte';
	import PlayerBar from '../../../lib/components/PlayerBar.svelte';
	import QueuePanel from '../../../lib/components/QueuePanel.svelte';
	import SearchResults from '../../../lib/components/SearchResults.svelte';
	import ArtistView from '../../../lib/components/ArtistView.svelte';
	import AlbumView from '../../../lib/components/AlbumView.svelte';
	import SongMenu, { type MenuTarget } from '../../../lib/components/SongMenu.svelte';
	import type { AlbumRef, ArtistRef, Song } from '../../../lib/protocol.gen';
	import ShareSheet from '../../../lib/components/ShareSheet.svelte';
	import RoomSettings from '../../../lib/components/settings/RoomSettings.svelte';
	import { getHost, prefetch } from '../../../lib/api';
	import type { HostInfo } from '../../../lib/protocol.gen';
	import { artistNames } from '../../../lib/format';
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
	let query = $state('');
	let menu = $state<MenuTarget | null>(null);

	// What the main area shows besides the album art: search, find similar, artist and album
	// pages, as a stack (the back arrow pops it). The logo switches between the album art
	// and the last one, which stays as it was.
	type View =
		| { kind: 'search' }
		| { kind: 'similar'; song: Song }
		| { kind: 'artist'; id: string; name: string }
		| { kind: 'album'; id: string; title: string };
	let views = $state<View[]>([]);
	let browsing = $state(false);
	const view = $derived(browsing ? (views.at(-1) ?? null) : null);

	function show(next: View) {
		const top = views.at(-1);
		if (next.kind === 'search' && top?.kind === 'search') {
			browsing = true;
			return;
		}
		views = [...views, next].slice(-20);
		browsing = true;
	}

	function back() {
		views = views.slice(0, -1);
		if (views.length === 0) browsing = false;
	}

	function toggleHome() {
		if (browsing) browsing = false;
		else if (views.length) browsing = true;
	}

	function openArtist(artist: ArtistRef) {
		if (artist.id) show({ kind: 'artist', id: artist.id, name: artist.name });
		else searchFor(artist.name);
	}

	function openAlbum(album: AlbumRef, artist = '') {
		if (album.id) show({ kind: 'album', id: album.id, title: album.name });
		else searchFor(`${album.name} ${artist}`.trim());
	}

	function searchFor(text: string) {
		query = text;
		show({ kind: 'search' });
	}

	/** Right-click (or ⋮) on the current song outside the queue: the album art and the player bar. */
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
	let settings = $state<'overview' | 'roles' | 'members' | 'bans' | null>(null);
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
		<!-- Phones: one scrolling page whose header slides away while scrolling down.
		     Desktop: header on top, song and queue scroll separately. -->
		<div
			bind:this={scroller}
			onscroll={onScroll}
			class="min-h-0 flex-1 overflow-y-auto lg:flex lg:flex-col lg:overflow-hidden"
		>
		<header
			class="sticky top-0 z-20 flex items-center gap-3 border-b border-line bg-bg/95 px-3 py-2 backdrop-blur transition-transform duration-200 sm:px-4 lg:translate-y-0
				{headerHidden ? '-translate-y-full' : ''}"
		>
			<button class="text-xl font-black tracking-tight" title={browsing ? 'Now playing' : 'Back to where you were'} onclick={toggleHome}>
				YT<span class="text-accent">MP</span>
			</button>
			<label class="flex min-w-0 flex-1 items-center gap-2 rounded-lg bg-raised px-3 py-2 sm:max-w-xl">
				<Icon name="search" size={20} class="shrink-0 text-muted" />
				<input
					class="min-w-0 flex-1 bg-transparent outline-none"
					placeholder="Search songs"
					bind:value={query}
					onfocus={() => ((headerHidden = false), query.trim() && show({ kind: 'search' }))}
					oninput={() => show({ kind: 'search' })}
				/>
				{#if query}
					<button aria-label="Clear search" onclick={() => (query = '')}><Icon name="close" size={20} /></button>
				{/if}
			</label>
			<div class="ml-auto flex items-center gap-2">
				<span class="hidden truncate text-sm font-medium md:inline">{room.state?.room.name}</span>
				<button
					class="flex items-center gap-2 rounded-full bg-raised px-3 py-1.5 font-mono tracking-widest hover:bg-line"
					onclick={() => (sharing = true)}
					title={room.state?.room.visibility === 'private' ? 'Solo room' : 'Share this room'}
				>
					{#if room.state?.room.visibility === 'private'}
						<Icon name="headphones" size={16} class="text-muted" />
					{/if}
					{code}
					<Icon name="share" size={16} class="text-muted" />
				</button>
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
						onclick={() => (settings = room?.can('change_settings') ? 'overview' : 'roles')}
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

		<div class="lg:grid lg:min-h-0 lg:flex-1 lg:grid-cols-[minmax(0,1fr)_420px]">
			<main class="p-3 sm:p-6 lg:min-h-0 lg:overflow-y-auto">
				{#if view}
					{#if views.length > 1}
						<button class="mb-2 flex items-center gap-1 rounded-full px-2 py-1 text-sm text-muted hover:bg-raised hover:text-white" onclick={back}>
							<svg viewBox="0 0 24 24" class="h-5 w-5"><path fill="currentColor" d="M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20z" /></svg>
							Back
						</button>
					{/if}
					{#if view.kind === 'search'}
						<SearchResults {room} {query} onToast={toast} onMenu={(target) => (menu = target)} />
					{:else if view.kind === 'similar'}
						<SearchResults {room} query="" similarTo={view.song} onToast={toast} onMenu={(target) => (menu = target)} />
					{:else if view.kind === 'artist'}
						<ArtistView {room} id={view.id} name={view.name} onToast={toast} onMenu={(target) => (menu = target)} onAlbum={(a) => openAlbum({ id: a.id, name: a.title })} />
					{:else if view.kind === 'album'}
						<AlbumView {room} id={view.id} title={view.title} onToast={toast} onMenu={(target) => (menu = target)} onArtist={openArtist} />
					{/if}
				{:else if room.state?.nowPlaying}
					{@const song = room.state.nowPlaying.item.song}
					<div class="flex min-w-0 flex-col items-center justify-center gap-4 py-4 text-center sm:gap-6 lg:h-full lg:py-0">
						<div class="contents" role="presentation" oncontextmenu={currentMenu}>
							<Art {song} size={544} class="aspect-square w-[min(100%,28rem,38vh)] shadow-2xl" />
						</div>
						<div class="w-full min-w-0" role="presentation" oncontextmenu={currentMenu}>
							<h1 class="text-xl font-bold break-words sm:text-2xl">{song.title}</h1>
							<p class="text-muted">
								{#each song.artists as artist, i (i)}
									{#if i > 0}{', '}{/if}<button class="hover:text-white hover:underline" onclick={() => openArtist(artist)} onpointerenter={() => artist.id && prefetch('artist', artist.id)}>{artist.name}</button>
								{/each}
								{#if song.album}
									• <button class="hover:text-white hover:underline" onclick={() => openAlbum(song.album!, song.artists[0]?.name)} onpointerenter={() => song.album?.id && prefetch('album', song.album.id)}>{song.album.name}</button>
								{/if}
							</p>
							<p class="mt-1 text-sm text-muted">Added by {room.state.nowPlaying.item.addedByName}</p>
						</div>
					</div>
				{:else}
					<div class="flex flex-col items-center justify-center gap-2 py-16 text-center text-muted lg:h-full lg:py-0">
						<Icon name="search" size={48} />
						<p>Search for a song to start the music.</p>
					</div>
				{/if}
			</main>
			<!-- On phones the queue makes room for search results, like YTM. -->
			<div class="border-t border-line lg:min-h-0 lg:border-t-0 lg:border-l {view ? 'hidden lg:block' : ''}">
				<QueuePanel {room} onMenu={(target) => (menu = target)} />
			</div>
		</div>
		</div>

		{#if player}
			<PlayerBar {room} {player} {positionMs} onToast={toast} onSongMenu={currentMenu} />
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
		/>
	{/if}

	{#if settings && room.state}
		<RoomSettings {room} start={settings} onClose={() => (settings = null)} />
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

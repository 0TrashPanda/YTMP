<script lang="ts">
	import { page } from '$app/state';
	import { goto, pushState, replaceState } from '$app/navigation';
	import { onDestroy, onMount, tick, untrack } from 'svelte';
	import Icon from '../../../lib/components/Icon.svelte';
	import PlayerBar from '../../../lib/components/PlayerBar.svelte';
	import NowPlaying from '../../../lib/components/NowPlaying.svelte';
	import HomeView from '../../../lib/components/HomeView.svelte';
	import LibraryView from '../../../lib/components/LibraryView.svelte';
	import LocalView from '../../../lib/components/LocalView.svelte';
	import LocalGroupView from '../../../lib/components/LocalGroupView.svelte';
	import { local, loadLocal, localPlace, forgetLocalPlace } from '../../../lib/local.svelte';
	import Sidebar from '../../../lib/components/Sidebar.svelte';
	import AvatarMenu from '../../../lib/components/AvatarMenu.svelte';
	import SettingsScreen, { type SettingsSection } from '../../../lib/components/settings/SettingsScreen.svelte';
	import LeaveRoomDialog from '../../../lib/components/LeaveRoomDialog.svelte';
	import PartyScreen from '../../../lib/components/PartyScreen.svelte';
	import TvLinkDialog from '../../../lib/components/TvLinkDialog.svelte';
	import OutputsSheet from '../../../lib/components/OutputsSheet.svelte';
	import SaveSheet from '../../../lib/components/SaveSheet.svelte';
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
	import CloneSheet from '../../../lib/components/CloneSheet.svelte';
	import RoomSettings from '../../../lib/components/settings/RoomSettings.svelte';
	import { forgetHome, getHost, getRoom } from '../../../lib/api';
	import type { HostInfo } from '../../../lib/protocol.gen';
	import { createPlayer, type RoomPlayer } from '../../../lib/player.svelte';
	import { RoomConnection } from '../../../lib/room.svelte';
	import { saved } from '../../../lib/storage';
	import { nativeBridge } from '../../../lib/native';
	import { identity, logInOnce, session } from '../../../lib/account';
	import type { Participant } from '../../../lib/protocol.gen';

	/** The code in the link this page was opened with. */
	const linkCode = page.params.code!.toUpperCase();
	/** The room's code: the link's, then the room's own (it can change, see SetRoomCode). */
	let code = $state(linkCode);

	// A link from cloning or a move to this room: #owner=… (I made it: the proof), #migrate=…
	// (my key from the old room, to get my role back) and #name=… (my name there, for a page that
	// doesn't know me yet). Read once, then gone from the address bar.
	let arrivingWithKey: string | null = null;
	if (location.hash.length > 1) {
		const fragment = new URLSearchParams(location.hash.slice(1));
		const owner = fragment.get('owner');
		if (owner) saved.setOwnerToken(linkCode, owner);
		arrivingWithKey = fragment.get('migrate');
		const nameThere = fragment.get('name')?.trim();
		if (nameThere && !saved.displayName.trim()) saved.displayName = nameThere.slice(0, 32);
		if (owner || arrivingWithKey || nameThere) history.replaceState(history.state, '', location.pathname + location.search);
	}

	// Logged in: the host takes the name from the account (also when logged in on this server's own pages).
	const initialName = identity.stored?.account.displayName ?? session.current?.account.displayName ?? saved.displayName;
	let name = $state(initialName);
	let room = $state<RoomConnection | null>(null);
	// The room got another code (or was opened by an earlier one): the address follows.
	$effect(() => {
		const now = room?.roomCode;
		if (!now || now === code) return;
		code = now;
		replaceState(`/room/${now}${location.search}`, page.state);
	});
	let player = $state<RoomPlayer | null>(null);
	// The search chip: stays as picked for the next search.
	let searchType = $state<SearchType>('all');
	let menu = $state<MenuTarget | null>(null);

	// The pages you opened, in order: Home at the bottom, then Library or Local (the bottom bar
	// starts over from there), then search, find similar, artist, album, playlist, podcast, genre
	// and folder pages. Back (the phone's, or the back arrow) goes to the one before, scrolled as
	// you left it.
	type Section = 'home' | 'library' | 'local';
	type View =
		| { kind: 'search'; query: string }
		| { kind: 'similar'; song: Song }
		| { kind: 'artist'; id: string; name: string }
		| { kind: 'album'; id: string; title: string }
		| { kind: 'playlist'; id: string; title: string; personal?: boolean }
		| { kind: 'podcast'; id: string; title: string }
		| { kind: 'genre'; library: string; name: string }
		| { kind: 'folder'; library: string; path: string };
	type Page = ({ kind: Section } | View) & { scrollTop?: number };
	const MAX_PAGES = 50;
	let pages = $state<Page[]>([{ kind: 'home' }]);
	const top = $derived(pages.at(-1)!);
	const isSection = (p: Page): p is Page & { kind: Section } => p.kind === 'home' || p.kind === 'library' || p.kind === 'local';
	const view = $derived(isSection(top) ? null : top);
	/** The bottom bar's page under the pages opened from it. */
	const section = $derived((pages.findLast(isSection)?.kind ?? 'home') as Section);
	/** Counts page changes: a scroll waiting for a page that's no longer shown stops. */
	let pageChanges = 0;

	/** Opens [next] on top of the page shown now. */
	function show(next: Page) {
		closePlayer();
		leavePage();
		// A new search replaces the results on top instead of piling up.
		const below = next.kind === 'search' && top.kind === 'search' ? pages.slice(0, -1) : pages;
		const kept = [...below, next].slice(-MAX_PAGES);
		pages = kept[0].kind === 'home' ? kept : [{ kind: 'home' }, ...kept.slice(1)];
		scrollPageTo(next.scrollTop ?? 0);
	}

	/** Back to the page before, scrolled as you left it. */
	function back() {
		if (pages.length < 2) return;
		closePlayer();
		leavePage();
		pages = pages.slice(0, -1);
		scrollPageTo(top.scrollTop ?? 0);
	}

	/** Remembers how far the page shown now is scrolled, for coming back to it. */
	function leavePage() {
		const y = scroller?.scrollTop ?? 0;
		top.scrollTop = y;
		if (top.kind === 'local') localPlace.scrollTop = y;
	}

	/** Scrolls the new page to [y] once it's that long (its content may still be loading). */
	async function scrollPageTo(y: number) {
		const mine = ++pageChanges;
		headerHidden = false;
		await tick();
		const until = Date.now() + 3000;
		const attempt = () => {
			if (!scroller || mine !== pageChanges) return;
			scroller.scrollTo({ top: y });
			if (scroller.scrollTop < y - 1 && Date.now() < until) requestAnimationFrame(attempt);
		};
		attempt();
	}

	/** The search shown in the search box: the last one. */
	const query = $derived(pages.findLast((v): v is Extract<View, { kind: 'search' }> => v.kind === 'search')?.query ?? '');

	/** The logo: always Home; on Home already, it loads new suggestions (like pulling down). */
	function goHome() {
		if (top.kind === 'home' && !expanded) {
			scroller?.scrollTo({ top: 0 });
			forgetHome();
			homeVersion++;
		} else pickSection('home');
	}

	// Home, Library (in the app with YouTube Music sign-in) and Local (when there's local
	// music): the bottom bar, under the pages above.
	const sections = $derived(
		([
			['home', 'Home'],
			['library', 'Library'],
			['local', 'Local']
		] as const).filter(([value]) => value === 'home' || (value === 'library' ? youtube.available : !!local.libraries?.length))
	);
	$effect(() => {
		void loadLocal();
	});
	let accountOpen = $state(false);
	/** Settings, at this section (null: the list). */
	let settingsAt = $state<SettingsSection | null | undefined>(undefined);
	let settingsScreen = $state<SettingsScreen>();
	let roomSettings = $state<RoomSettings>();
	let leaving = $state(false);
	/** The party screen on this device, and linking a TV as one. */
	let partyScreen = $state(false);
	let linkingTv = $state(false);
	let playingOn = $state(false);
	/** Saving this song to one of your YouTube Music playlists. */
	let saving = $state<Song | null>(null);

	/**
	 * A page of the bottom bar: the pages start over from it, Home under Library and Local (so
	 * Back from those goes Home). The one shown already goes back to its top.
	 */
	function pickSection(next: Section) {
		closePlayer();
		if (top.kind === next) {
			scroller?.scrollTo({ top: 0, behavior: 'smooth' });
			return;
		}
		leavePage();
		// Local opens where you were, or (Remember where you were off) at the start.
		if (next === 'local' && !localPlace.remember) forgetLocalPlace();
		const scrollTop = next === 'local' ? localPlace.scrollTop : 0;
		pages = next === 'home' ? [{ kind: 'home' }] : [{ kind: 'home' }, { kind: next, scrollTop }];
		scrollPageTo(scrollTop);
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

	// The room's password, when it has one (the join form asks; not needed by its owner).
	let password = $state('');
	let needsPassword = $state(false);
	getRoom(linkCode)
		.then((info) => (needsPassword = info.hasPassword && !saved.ownerToken(linkCode)))
		.catch(() => {});
	let retryPassword = $state('');

	function start() {
		if (!identity.stored) saved.displayName = name.trim();
		room = new RoomConnection(code, name.trim(), arrivingWithKey, password || null);
		player = createPlayer(room);
		room.connect();
		youtube.load();
	}
	if (initialName.trim()) {
		// Logged in on this server's pages, but not yet for its rooms: that first (log in once).
		if (session.current) getHost().then(logInOnce).catch(() => {}).finally(start);
		else start();
	}

	// Tick for the progress bar, only while it moves and can be seen: a hidden page that keeps
	// redrawing costs battery on phones.
	let visibility = $state<DocumentVisibilityState>(document.visibilityState);
	$effect(() => {
		if (!room?.state?.playback.playing || visibility !== 'visible') return;
		now = Date.now();
		const clock = setInterval(() => (now = Date.now()), 250);
		return () => clearInterval(clock);
	});
	onDestroy(() => {
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

	// Solo rooms play on this device by default (unless you turned it off in that room); in
	// other rooms "Play here" stays as you left it. The app can start right away; a browser
	// only allows audio after a tap or key press, so it starts at the first one.
	// Only once, when the room opens: after that, on or off is up to you (or another app taking the audio).
	let autoStarted = false;
	$effect(() => {
		const visibility = room?.state?.room.visibility;
		if (room?.status !== 'connected' || !player || !visibility || autoStarted) return;
		autoStarted = true;
		const wanted = visibility === 'private' ? !saved.soloOff(code) : player.canStartWithoutGesture && saved.listening;
		if (!wanted) return;
		untrack(() => {
			if (player!.enabled) return;
			if (player!.canStartWithoutGesture) player!.enable();
			else startOnFirstGesture();
		});
	});

	let waitingForGesture = false;
	function startOnFirstGesture() {
		if (waitingForGesture) return;
		waitingForGesture = true;
		const start = (event: Event) => {
			// A tap on the on/off switch itself decides for itself.
			if ((event.target as HTMLElement | null)?.closest('[data-listen-toggle]')) return;
			window.removeEventListener('pointerup', start, true);
			window.removeEventListener('keydown', start, true);
			waitingForGesture = false;
			if (player && !player.enabled && !saved.soloOff(code)) player.enable();
		};
		window.addEventListener('pointerup', start, true);
		window.addEventListener('keydown', start, true);
	}

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

	const solo = $derived(room?.state?.room.visibility === 'private');

	// The phone's Back button (the app asks the page first, see MainActivity): close what's
	// open, then go back through the pages you opened, then to Home. On Home it stops, or goes
	// on to the room list, as set in Settings → Playback (separately for solo and party rooms).
	function onBack(): 'handled' | 'exit' | 'default' {
		if (!room || room.status === 'rejected') return 'default';
		// Settings go back a step first (a section to the list, a role to the roles).
		if (settings && roomSettings?.back()) return 'handled';
		if (settingsAt !== undefined && settingsScreen?.back()) return 'handled';
		if (menu || saving || settings || accountOpen || sharing || cloning || leaving || playingOn || partyScreen || linkingTv || settingsAt !== undefined) {
			menu = saving = settings = null;
			accountOpen = sharing = cloning = leaving = playingOn = partyScreen = linkingTv = false;
			settingsAt = undefined;
			return 'handled';
		}
		// The full player and the phone's search page are history entries.
		if (Object.keys(page.state).length) {
			history.back();
			return 'handled';
		}
		if (pages.length > 1) {
			back();
			return 'handled';
		}
		if (scroller && scroller.scrollTop > 0) {
			scroller.scrollTo({ top: 0, behavior: 'smooth' });
			return 'handled';
		}
		if (saved.backLimit(solo) === 'home') return 'exit';
		goto('/');
		return 'handled';
	}

	/** Space plays or pauses the room, like YTM; not while typing, or on a control reached with Tab. */
	function onKeydown(event: KeyboardEvent) {
		if (event.key !== ' ' || event.ctrlKey || event.metaKey || event.altKey || !room?.state?.nowPlaying) return;
		const target = event.target as HTMLElement;
		if (target.closest('input:not([type="checkbox"], [type="radio"], [type="range"]), textarea, select, [contenteditable]')) return;
		if (target !== document.body && target.matches(':focus-visible')) return;
		event.preventDefault();
		if (!event.repeat) room.run({ kind: room.state.playback.playing || room.state.playback.starting ? 'Pause' : 'Play' });
	}

	// A changed setting (e.g. what unplugging headphones does) goes to the app's player.
	const onSettings = () => player?.sync();
	onMount(() => {
		// Speakers near the phone that play this room (the app drives them) tell about problems here.
		const onNearbyNotice = (text: string) => toast(text);
		if (nativeBridge) window.__ytmpNative = { ...window.__ytmpNative, onBack, onNearbyNotice };
		window.addEventListener('ytmp:settings', onSettings);
		return () => {
			if (window.__ytmpNative?.onBack === onBack) window.__ytmpNative = { ...window.__ytmpNative, onBack: undefined };
			if (window.__ytmpNative?.onNearbyNotice === onNearbyNotice) window.__ytmpNative = { ...window.__ytmpNative, onNearbyNotice: undefined };
			window.removeEventListener('ytmp:settings', onSettings);
		};
	});

	// Phones: pull Home down to load new suggestions.
	let homeVersion = $state(0);
	let pullStart: { x: number; y: number } | null = null;
	/** Null until the finger has moved enough to tell a pull down from a sideways swipe (a row of songs). */
	let pullDown: boolean | null = null;
	let pull = $state(0);
	const PULL_TO_REFRESH = 72;
	function pullStartAt(event: TouchEvent) {
		const t = event.touches[0];
		pullStart = top.kind === 'home' && (scroller?.scrollTop ?? 1) <= 0 ? { x: t.clientX, y: t.clientY } : null;
		pullDown = null;
	}
	function pullMove(event: TouchEvent) {
		if (pullStart === null) return;
		const t = event.touches[0];
		const distance = t.clientY - pullStart.y;
		if (pullDown === null) {
			const sideways = Math.abs(t.clientX - pullStart.x);
			if (sideways < 10 && Math.abs(distance) < 10) return;
			pullDown = distance > sideways;
		}
		if (!pullDown || distance <= 0 || (scroller?.scrollTop ?? 0) > 0) {
			pull = 0;
			return;
		}
		// Harder to pull the further it goes.
		pull = Math.min(PULL_TO_REFRESH * 1.5, distance * 0.5);
	}
	function pullEnd() {
		if (pull >= PULL_TO_REFRESH) {
			forgetHome();
			homeVersion++;
		}
		pullStart = null;
		pull = 0;
	}

	let sharing = $state(false);
	let cloning = $state(false);
	/** The invite to another room was closed here (it stays for the others). */
	let migrationDismissed = $state<string | null>(null);

	// Everyone was moved to another room: follow, with my role.
	$effect(() => {
		if (room?.status === 'rejected' && room.rejectReason === 'migrated' && room.movedTo) {
			location.href = room.migrationLink(room.movedTo);
		}
	});
	let settings = $state<'list' | 'members' | null>(null);
	let host = $state<HostInfo | null>(null);
	getHost().then((h) => (host = h)).catch(() => {});
</script>

<svelte:document bind:visibilityState={visibility} />
<svelte:window onkeydown={onKeydown} />

<svelte:head>
	<title>{room?.state ? `${room.state.room.name} · YTMP` : 'YTMP'}</title>
</svelte:head>

{#if !room}
	<main class="mx-auto flex min-h-full max-w-sm flex-col justify-center gap-4 px-4">
		<h1 class="text-2xl font-bold">Join room <span class="font-mono">{code}</span></h1>
		{#if host?.accountsOnly}
			<p class="text-muted">This server is for accounts only.</p>
			{#each host.authServers as server (server.issuer)}
				<a href={identity.loginUrl(server)} class="rounded-full bg-white py-3 text-center font-medium text-black">Log in to join</a>
			{/each}
		{:else}
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
			{#if needsPassword}
				<input
					class="rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2"
					type="password"
					bind:value={password}
					maxlength="64"
					placeholder="The room's password"
					autocomplete="off"
				/>
			{/if}
			{#if host?.authServers.length}
				<label class="flex items-center gap-2 text-sm text-muted">
					<input type="checkbox" class="accent-accent" checked={saved.hideFromHistory} onchange={(e) => (saved.hideFromHistory = e.currentTarget.checked)} />
					Keep me out of others' listening history
				</label>
			{/if}
			<button class="rounded-full bg-white py-3 font-medium text-black disabled:opacity-40" disabled={!name.trim() || (needsPassword && !password)}>
				Join
			</button>
		</form>
		{#if host && !identity.get(host.authServers)}
			{#each host.authServers as server (server.issuer)}
				<a href={identity.loginUrl(server)} class="rounded-full bg-raised py-3 text-center font-medium hover:bg-line">
					Log in with {server.url ? new URL(server.url).host : server.issuer}
				</a>
			{/each}
		{/if}
		{/if}
	</main>
{:else if room.status === 'rejected'}
	<main class="mx-auto flex min-h-full max-w-sm flex-col items-center justify-center gap-4 px-4 text-center">
		<p class="text-lg">{room.rejectMessage}</p>
		{#if room.rejectReason === 'password_required' || room.rejectReason === 'wrong_password'}
			<form
				class="flex w-full flex-col gap-3"
				onsubmit={(e) => {
					e.preventDefault();
					if (retryPassword) room?.retryWithPassword(retryPassword);
				}}
			>
				<!-- svelte-ignore a11y_autofocus -->
				<input
					class="rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2"
					type="password"
					bind:value={retryPassword}
					maxlength="64"
					placeholder="The room's password"
					autocomplete="off"
					autofocus
				/>
				<button class="rounded-full bg-white py-3 font-medium text-black disabled:opacity-40" disabled={!retryPassword}>Join</button>
			</form>
		{/if}
		{#if (host?.accountsOnly || room.rejectReason === 'private_room') && host && !identity.get(host.authServers)}
			{#if room.rejectReason === 'private_room'}
				<p class="text-sm text-muted">Is it yours? Log in with your account to open it.</p>
			{/if}
			{#each host.authServers as server (server.issuer)}
				<a href={identity.loginUrl(server)} class="rounded-full bg-white px-6 py-3 font-medium text-black">
					Log in{host.authServers.length > 1 ? ` with ${server.url ? new URL(server.url).host : server.issuer}` : ''}
				</a>
			{/each}
		{/if}
		{#if room.movedTo}
			<a href={room.movedTo} class="rounded-full bg-white px-6 py-3 font-medium text-black">Open it there</a>
			<a href="/" class="text-sm text-muted underline">Back</a>
		{:else}
			<a href="/" class="rounded-full bg-white px-6 py-3 font-medium text-black">Back</a>
		{/if}
	</main>
{:else}
	<div class="flex h-full flex-col">
		<div class="relative flex min-h-0 flex-1">
			<Sidebar
				section={view ? null : section}
				onLogo={goHome}
				onSection={pickSection}
				onPlaylist={(p) => openPlaylist(p, true)}
			/>
			<!-- The page; desktop: the full player opens over it, below the top bar (search stays), and the left side stays, like YTM. -->
			<div class="relative min-w-0 flex-1">
				<!-- One scrolling page whose header slides away while scrolling down (phones). -->
				<div
					bind:this={scroller}
					onscroll={onScroll}
					ontouchstart={pullStartAt}
					ontouchmove={pullMove}
					ontouchend={pullEnd}
					ontouchcancel={pullEnd}
					role="presentation"
					class="h-full overflow-y-auto overscroll-y-contain"
				>
					<header
						class="sticky top-0 z-20 flex items-center gap-3 border-b lg:z-40 border-line bg-bg/95 px-3 py-2 backdrop-blur transition-transform duration-200 sm:h-14 sm:px-6 lg:translate-y-0
							{headerHidden ? '-translate-y-full' : ''}"
					>
						<!-- Desktop: the logo is on the left side (Sidebar), so search starts where the page does, like YTM. -->
						<button class="text-xl font-black tracking-tight sm:hidden" title="Home" onclick={goHome}>
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
							{#if !solo}
								<button
									class="flex items-center gap-1 rounded-full px-2 py-1.5 text-sm text-muted hover:bg-raised hover:text-white"
									aria-label="People in this room"
									onclick={() => (settings = 'members')}
									title={room.state?.participants
										.filter((p: Participant) => p.online && !p.screen)
										.map((p: Participant) => p.name + (p.accountId ? ` (${p.accountId})` : '') + (p.listening ? ' 🎧' : '') + (p.speakers.length ? ` 🔊 ${p.speakers.join(', ')}` : ''))
										.join(', ')}
								>
									<Icon name="people" size={20} />
									<span class="hidden sm:inline">{room.state?.participants.filter((p: Participant) => p.online && !p.screen).length ?? 0}</span>
								</button>
							{/if}
							<!-- Your account and the settings (YTM keeps them behind the avatar too). -->
							<button class="shrink-0 rounded-full p-1" aria-label="Account and settings" title="Account and settings" onclick={() => (accountOpen = true)}>
								{#if youtube.account?.photoUrl}
									<img src={youtube.account.photoUrl} alt="" referrerpolicy="no-referrer" class="h-7 w-7 rounded-full" />
								{:else}
									<span class="grid h-7 w-7 place-items-center rounded-full bg-raised"><Icon name="person" size={20} class="text-muted" /></span>
								{/if}
							</button>
						</div>
					</header>

					{#if room.status !== 'connected'}
						<div class="bg-raised px-4 py-1 text-center text-sm text-muted">
							{room.status === 'connecting' ? 'Connecting…' : 'Connection lost, reconnecting…'}
							<!-- The host may be gone (a phone that left): this page still has the queue. -->
							{#if room.status === 'reconnecting' && room.state && room.can('clone_room')}
								<button class="ml-1 text-white underline" onclick={() => (cloning = true)}>Keep the queue: clone it</button>
							{/if}
						</div>
					{/if}
					{#if room.state?.migration && room.state.migration.target.code !== code && migrationDismissed !== room.state.migration.target.url}
						{@const offer = room.state.migration}
						<div class="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 bg-accent/90 px-4 py-2 text-center text-sm">
							<span>{offer.fromName} moved the party to <strong>{offer.target.name}</strong>.</span>
							<a class="rounded-full bg-white px-3 py-1 font-medium text-black" href={room.migrationLink(offer.target.url)}>Go there</a>
							<button class="underline" onclick={() => (migrationDismissed = offer.target.url)}>Stay here</button>
							{#if room.can('send_migration')}
								<button class="underline" onclick={() => room?.run({ kind: 'CancelMigration' })}>Take back</button>
							{/if}
						</div>
					{:else if room.state?.adminless && !room.me?.isOwner}
						<div class="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 bg-raised px-4 py-2 text-center text-sm">
							<span>Nobody who runs this room is here.</span>
							{#if room.can('clone_room')}
								<button class="underline" onclick={() => (cloning = true)}>Clone it to keep the queue going</button>
							{/if}
						</div>
					{/if}

					{#if pull > 0}
						<div class="flex justify-center overflow-hidden" style:height="{pull}px">
							<span class="mt-auto mb-2 grid h-9 w-9 place-items-center rounded-full bg-raised shadow-lg" style:transform="rotate({pull * 4}deg)" style:opacity={Math.min(1, pull / PULL_TO_REFRESH)}>
								<Icon name="replay" size={20} class={pull >= PULL_TO_REFRESH ? 'text-white' : 'text-muted'} />
							</span>
						</div>
					{/if}
					<main class="mx-auto w-full max-w-7xl p-3 sm:p-6">
						{#key top}
						{#if view}
							{#if pages.length > 1 && !isSection(pages.at(-2)!)}
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
								<ArtistView
									{room}
									id={view.id}
									name={view.name}
									onToast={toast}
									onMenu={(target) => (menu = target)}
									onAlbum={(a) => openAlbum({ id: a.id, name: a.title })}
									onPlaylist={openPlaylist}
								/>
							{:else if view.kind === 'album'}
								<AlbumView {room} id={view.id} title={view.title} onToast={toast} onMenu={(target) => (menu = target)} onArtist={openArtist} />
							{:else if view.kind === 'playlist'}
								<PlaylistView {room} id={view.id} title={view.title} personal={view.personal} onToast={toast} onMenu={(target) => (menu = target)} onGone={back} />
							{:else if view.kind === 'podcast'}
								<PodcastView {room} id={view.id} title={view.title} onToast={toast} onMenu={(target) => (menu = target)} />
							{:else if view.kind === 'genre' || view.kind === 'folder'}
								{@const library = view.library}
								<LocalGroupView
									{room}
									{library}
									genre={view.kind === 'genre' ? view.name : undefined}
									folder={view.kind === 'folder' ? view.path : undefined}
									onFolder={(path) => show({ kind: 'folder', library, path })}
									onToast={toast}
									onMenu={(target) => (menu = target)}
								/>
							{/if}
						{:else if top.kind === 'local'}
							<LocalView
								{room}
								onToast={toast}
								onMenu={(target) => (menu = target)}
								onArtist={openArtist}
								onAlbum={(a) => openAlbum(a)}
								onPlaylist={openPlaylist}
								onPodcast={openPodcast}
								onGenre={(library, name) => show({ kind: 'genre', library, name })}
								onFolder={(library, path) => show({ kind: 'folder', library, path })}
							/>
						{:else if top.kind === 'library'}
							<LibraryView
								onToast={toast}
								onArtist={openArtist}
								onAlbum={(a) => openAlbum(a)}
								onPlaylist={(p) => openPlaylist(p, true)}
								onPodcast={openPodcast}
							/>
						{:else}
							<!-- Signing in or out changes the suggestions. -->
							{#key `${youtube.version}:${homeVersion}`}
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
						{/key}
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
						onSave={(song) => (saving = song)}
						onArtist={openArtist}
						onAlbum={(a) => openAlbum(a, room?.state?.nowPlaying?.item.song.artists[0]?.name)}
						onPodcast={openPodcast}
					/>
				{/if}
			</div>
		</div>

		{#if player}
			<PlayerBar
				{room}
				{player}
				{positionMs}
				{expanded}
				onExpand={(open) => (open ? openPlayer() : closePlayer())}
				onToast={toast}
				onSongMenu={currentMenu}
				onArtist={openArtist}
				onAlbum={(a) => openAlbum(a, room?.state?.nowPlaying?.item.song.artists[0]?.name)}
				onPodcast={openPodcast}
			/>
		{/if}
		{#if sections.length > 1}
			<!-- Like YTM's bottom bar, on phones (desktop: the Sidebar). -->
			<nav class="flex shrink-0 bg-surface sm:hidden">
				{#each sections as [value, label] (value)}
					{@const active = section === value && !view}
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
			onSave={youtube.account ? (song) => (saving = song) : undefined}
		/>
	{/if}

	{#if saving}
		<SaveSheet song={saving} onClose={() => (saving = null)} onToast={toast} />
	{/if}

	{#if settings && room.state}
		<RoomSettings
			bind:this={roomSettings}
			{room}
			{host}
			start={settings}
			onClose={() => (settings = null)}
			onClone={() => {
				settings = null;
				cloning = true;
			}}
		/>
	{/if}

	{#if cloning && room.state}
		<CloneSheet {room} {host} onClose={() => (cloning = false)} onToast={toast} />
	{/if}

	{#if accountOpen}
		<AvatarMenu
			{host}
			{room}
			onClose={() => (accountOpen = false)}
			onSettings={(section) => (settingsAt = section ?? null)}
			onShare={() => (sharing = true)}
			onPlayOn={player ? () => (playingOn = true) : undefined}
			onClone={() => (cloning = true)}
			onRoomSettings={room.can('change_settings') || room.can('edit_roles') || room.can('kick') || room.can('ban') ? () => (settings = 'list') : undefined}
			onPartyScreen={() => (partyScreen = true)}
			onShowOnTv={() => (linkingTv = true)}
			onLeave={() => (leaving = true)}
		/>
	{/if}

	{#if settingsAt !== undefined}
		<SettingsScreen bind:this={settingsScreen} start={settingsAt} onClose={() => (settingsAt = undefined)} onToast={toast} />
	{/if}

	{#if leaving && room.state}
		<LeaveRoomDialog {room} onClose={() => (leaving = false)} />
	{/if}
	{#if linkingTv}
		<TvLinkDialog {room} {host} onClose={() => (linkingTv = false)} onToast={toast} />
	{/if}
	{#if partyScreen && room.state}
		<PartyScreen {room} {host} onClose={() => (partyScreen = false)} />
	{/if}

	{#if playingOn && player}
		<OutputsSheet {room} {player} onClose={() => (playingOn = false)} onToast={toast} />
	{/if}

	{#if sharing}
		<ShareSheet
			{room}
			{host}
			onClose={() => (sharing = false)}
			onToast={toast}
			onClone={room.can('clone_room')
				? () => {
						sharing = false;
						cloning = true;
					}
				: undefined}
		/>
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

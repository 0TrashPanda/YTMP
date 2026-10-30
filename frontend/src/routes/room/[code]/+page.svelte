<script lang="ts">
	import { positionNow } from '../../../lib/sync';
	import { page } from '$app/state';
	import { goto, pushState, replaceState } from '$app/navigation';
	import { onDestroy, onMount, tick, untrack } from 'svelte';
	import Icon from '../../../lib/components/Icon.svelte';
	import PlayerBar from '../../../lib/components/PlayerBar.svelte';
	import NowPlaying from '../../../lib/components/NowPlaying.svelte';
	import HomeView from '../../../lib/components/HomeView.svelte';
	import LibraryView from '../../../lib/components/LibraryView.svelte';
	import HistoryView from '../../../lib/components/HistoryView.svelte';
	import ImportView from '../../../lib/components/ImportView.svelte';
	import { listening } from '../../../lib/listening.svelte';
	import LocalView from '../../../lib/components/LocalView.svelte';
	import LocalGroupView from '../../../lib/components/LocalGroupView.svelte';
	import { local, loadLocal } from '../../../lib/local.svelte';
	import { PageStack, type Page, type Section } from '../../../lib/pageStack.svelte';
	import Sidebar from '../../../lib/components/Sidebar.svelte';
	import AvatarMenu from '../../../lib/components/AvatarMenu.svelte';
	import SettingsScreen, { type SettingsSection } from '../../../lib/components/settings/SettingsScreen.svelte';
	import LeaveRoomDialog from '../../../lib/components/LeaveRoomDialog.svelte';
	import InterruptDialog from '../../../lib/components/InterruptDialog.svelte';
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
	import { preferences } from '../../../lib/preferences.svelte';
	import type { AlbumRef, ArtistRef, PodcastRef, SearchType, Song } from '../../../lib/protocol.gen';
	import type { ToastAction } from '../../../lib/songActions';
	import GraphView from '../../../lib/components/GraphView.svelte';
	import type { Link } from '../../../lib/links';
	import ShareSheet from '../../../lib/components/ShareSheet.svelte';
	import CloneSheet from '../../../lib/components/CloneSheet.svelte';
	import { forgetHome, forgetLibrary, getHost, getRoom } from '../../../lib/api';
	import type { HostInfo } from '../../../lib/protocol.gen';
	import { createPlayer, pause, type RoomPlayer } from '../../../lib/player.svelte';
	import { sleepTimer } from '../../../lib/sleepTimer.svelte';
	import SleepTimerSheet from '../../../lib/components/SleepTimerSheet.svelte';
	import { RoomConnection } from '../../../lib/room.svelte';
	import { shows } from '../../../lib/soloLook.svelte';
	import { load as loadSettings, settings } from '../../../lib/settings.svelte';
	import { saved } from '../../../lib/storage';
	import { moduleStatus, switchOffOwnModule } from '../../../lib/ownModule.svelte';
	import { appUpdate } from '../../../lib/appUpdate.svelte';
	import { ListenRequests } from '../../../lib/listenRequests';
	import { nativeBridge } from '../../../lib/native';
	import { identity, logInOnce, session } from '../../../lib/account';
	import type { Participant } from '../../../lib/protocol.gen';
	import { busy } from '../../../lib/busy.svelte';

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
	/** What's open over the page: one sheet, menu or dialog at a time. Back closes it. */
	type Sheet =
		| { kind: 'menu'; target: MenuTarget }
		| { kind: 'save'; song: Song }
		| { kind: 'graph'; song: Song | null }
		| { kind: 'settings'; start: SettingsSection | null }
		| { kind: 'avatar' | 'share' | 'clone' | 'leave' | 'playOn' | 'partyScreen' | 'linkTv' | 'sleep' };
	let sheet = $state<Sheet | null>(null);
	/** Closes [kind] if it's what's open (it may have opened something else on its way out, like the song menu opening Save). */
	function close(kind: Sheet['kind']) {
		if (sheet?.kind === kind) sheet = null;
	}
	const openMenu = (target: MenuTarget) => (sheet = { kind: 'menu', target });

	// The pages you opened, in order, and Back through them (lib/pageStack.svelte.ts).
	const stack = new PageStack({
		scrollTop: () => scroller?.scrollTop ?? 0,
		scrollTo: scrollPageTo,
		toTop: () => scroller?.scrollTo({ top: 0, behavior: 'smooth' }),
		leaving: closePlayer
	});
	const top = $derived(stack.top);
	const view = $derived(stack.view);
	const section = $derived(stack.section);
	const query = $derived(stack.query);
	const show = (next: Page) => stack.show(next);
	const back = () => stack.back();
	const pickSection = (next: Section) => stack.pick(next);
	/** Counts page changes: a scroll waiting for a page that's no longer shown stops. */
	let pageChanges = 0;

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

	/** The logo: always Home; on Home already, it loads new suggestions (like pulling down). */
	function goHome() {
		if (top.kind === 'home' && !expanded) {
			scroller?.scrollTo({ top: 0 });
			forgetHome();
			homeVersion++;
		} else pickSection('home');
	}

	// Home, Library (in the app with YouTube Music sign-in, or with your YTMP history) and Local
	// (when there's local music): the bottom bar, under the pages above.
	const sections = $derived(
		([
			['home', 'Home'],
			['library', 'Library'],
			['local', 'Local']
		] as const).filter(([value]) => value === 'home' || (value === 'library' ? youtube.available || listening.available : !!local.libraries?.length))
	);
	$effect(() => {
		void loadLocal();
	});
	let settingsScreen = $state<SettingsScreen>();

	// The full player (album art, controls, Up next) over everything. A history entry, so
	// the phone's Back button closes it.
	const expanded = $derived(!!page.state.player);

	/**
	 * Each time it opens, a new full player: one that's still sliding away (swiped down a
	 * moment ago) finishes on its own, instead of coming back from halfway, stuck there.
	 */
	let playerOpened = $state(0);

	function openPlayer() {
		if (page.state.player) return;
		playerOpened++;
		pushState('', { player: true });
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

	/** An episode whose podcast YouTube Music didn't say has no page to open. */
	function openPodcast(podcast: PodcastRef) {
		if (podcast.id) show({ kind: 'podcast', id: podcast.id, title: podcast.name });
	}

	function searchFor(text: string) {
		show({ kind: 'search', query: text });
	}

	/** A link pasted in the search box: its page, or a song as the search's result (SearchResults), or another room. */
	function openLink(link: Link, text: string) {
		if (link.kind === 'song') searchFor(text);
		else if (link.kind === 'album') show({ kind: 'album', id: link.id, title: '' });
		else if (link.kind === 'playlist') openPlaylist({ id: link.id, title: '' });
		else if (link.kind === 'artist') show({ kind: 'artist', id: link.id, name: '' });
		else if (link.kind === 'podcast') show({ kind: 'podcast', id: link.id, title: '' });
		else if (link.code === room?.roomCode && new URL(link.url).origin === location.origin) toast("You're in this room");
		else location.href = link.url;
	}

	/** Right-click (or ⋮) on the current song outside the queue: the full player and the player bar. */
	function currentMenu(event: MouseEvent) {
		const item = room?.state?.nowPlaying?.item;
		if (!item) return;
		event.preventDefault();
		openMenu({ song: item.song, item, place: 'current', x: event.clientX, y: event.clientY });
	}
	let toasts = $state<{ id: number; text: string; action?: ToastAction }[]>([]);
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
		sleepTimer.attach(room, player);
		room.connect();
		youtube.load();
		void listening.load();
		void loadSettings();
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
	// The room you're in, to open again when the app starts (Settings → App). Leaving it for
	// the room list forgets it; closing the app doesn't.
	$effect(() => {
		const info = room?.state?.room;
		if (info) saved.lastRoom = { code: info.code, solo: info.visibility === 'private' };
	});
	onDestroy(() => {
		saved.lastRoom = null;
		sleepTimer.attach(null, null);
		player?.destroy();
		room?.close();
	});

	// Follow the room: whenever the song or playback changes, re-sync the audio.
	$effect(() => {
		const s = room?.state;
		void [s?.nowPlaying?.item.itemId, s?.nowPlaying?.streamUrl, s?.playback.playing, s?.playback.positionMs, s?.playback.hostTimeMs, s?.playback.speed];
		void room?.clockOffset;
		void room?.listeningAlone;
		void room?.participantId;
		// The lock screen's buttons follow what you may do.
		void [room?.can('skip'), room?.can('seek')];
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
		// Reloaded between two songs for a new version: it played here, so it plays on.
		const wanted =
			appUpdate.resumes(code) || (visibility === 'private' ? !saved.soloOff(code) : player.canStartWithoutGesture && saved.listening);
		if (!wanted) return;
		untrack(() => {
			if (player!.enabled) return;
			if (player!.canStartWithoutGesture) player!.enable();
			else startOnFirstGesture();
		});
	});

	// Back after the connection dropped (a network hiccup): the host forgets this device plays
	// the music unless it hears so again within a minute.
	let reconnecting = false;
	$effect(() => {
		const status = room?.status;
		if (status === 'reconnecting') reconnecting = true;
		else if (status === 'connected' && reconnecting) {
			reconnecting = false;
			untrack(() => player?.reconnected());
		}
	});

	// Your other devices may switch "Play here" on this one (Settings → Playback): the host hears
	// whether, and whether sound can start here now (a browser only after a click on its page).
	let remotePlay = $state(settings.remotePlay.value);
	let clicked = $state(false);
	$effect(() => {
		const click = () => (clicked = true);
		addEventListener('pointerdown', click, { capture: true, once: true });
		addEventListener('keydown', click, { capture: true, once: true });
		return () => {
			removeEventListener('pointerdown', click, { capture: true });
			removeEventListener('keydown', click, { capture: true });
		};
	});
	$effect(() => {
		const me = room?.me;
		void [clicked, player?.enabled, player?.buffering];
		if (!room || !player || room.status !== 'connected' || !me?.accountId || me.screen) return;
		const allowed = remotePlay;
		const canStart = untrack(() => player!.canStartSound());
		if (me.remotePlay !== allowed || me.canStartSound !== canStart) untrack(() => room!.run({ kind: 'SetRemotePlay', allowed, canStart }));
	});
	// And another device of yours switched it.
	const listenRequests = new ListenRequests();
	$effect(() => {
		const want = listenRequests.update(room?.me ?? null);
		if (!want || !player || !remotePlay) return;
		untrack(() => {
			if (want === 'on' && !player!.enabled) player!.enable();
			else if (want === 'off' && player!.enabled) player!.disable();
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
		return positionNow(playback, room.hostNow());
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

	function toast(text: string, action?: ToastAction) {
		// Negative, so they never clash with the host's notice IDs.
		const id = -++toastId;
		toasts.push({ id, text, action });
		// One with a button stays a bit longer, to reach it.
		setTimeout(() => (toasts = toasts.filter((t) => t.id !== id)), action ? 6000 : 3000);
	}

	const solo = $derived(room?.state?.room.visibility === 'private');

	// The phone's Back button (the app asks the page first, see MainActivity): close what's
	// open, then go back through the pages you opened, then to Home. On Home it stops, or goes
	// on to the room list, as set in Settings → Playback (separately for solo and party rooms).
	function onBack(): 'handled' | 'exit' | 'default' {
		if (!room || room.status === 'rejected') return 'default';
		// Settings go back a step first (a section to the list, a role to the roles).
		if (sheet?.kind === 'settings' && settingsScreen?.back()) return 'handled';
		if (sheet) {
			sheet = null;
			return 'handled';
		}
		// The full player and the phone's search page are history entries.
		if (Object.keys(page.state).length) {
			history.back();
			return 'handled';
		}
		if (back()) return 'handled';
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
		if (!event.repeat) {
			if (room.state.playback.playing || room.state.playback.starting || room.state.playback.loading) pause(room, player);
			else room.run({ kind: 'Play' });
		}
	}

	// A changed setting (e.g. what unplugging headphones does) goes to the app's player.
	const onSettings = () => {
		remotePlay = settings.remotePlay.value;
		player?.sync();
	};
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

	// Phones: pull Home down to load new suggestions (Library: your playlists and podcasts again).
	let homeVersion = $state(0);
	let libraryVersion = $state(0);
	let pullStart: { x: number; y: number } | null = null;
	/** Null until the finger has moved enough to tell a pull down from a sideways swipe (a row of songs). */
	let pullDown: boolean | null = null;
	let pull = $state(0);
	const PULL_TO_REFRESH = 72;
	function pullStartAt(event: TouchEvent) {
		const t = event.touches[0];
		pullStart = (top.kind === 'home' || top.kind === 'library') && (scroller?.scrollTop ?? 1) <= 0 ? { x: t.clientX, y: t.clientY } : null;
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
		if (pull >= PULL_TO_REFRESH && top.kind === 'library') {
			forgetLibrary();
			libraryVersion++;
		} else if (pull >= PULL_TO_REFRESH) {
			forgetHome();
			homeVersion++;
		}
		pullStart = null;
		pull = 0;
	}

	/** The invite to another room was closed here (it stays for the others). */
	let migrationDismissed = $state<string | null>(null);

	// Everyone was moved to another room: follow, with my role.
	$effect(() => {
		if (room?.status === 'rejected' && room.rejectReason === 'migrated' && room.movedTo) {
			location.href = room.migrationLink(room.movedTo);
		}
	});
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
		{#if room.rejectReason === 'password_required' || room.rejectReason === 'wrong_password' || room.rejectReason === 'too_many_attempts'}
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
						<SearchBox {query} onopen={() => (headerHidden = false)} onsearch={searchFor} onlink={openLink} />
						<div class="ml-auto flex items-center gap-2">
							<span class="hidden truncate text-sm font-medium md:inline">{room.state?.room.name}</span>
							<!-- Phones: centered in the header. -->
							{#if shows(room, 'code')}
								<button
									class="flex items-center gap-2 rounded-full bg-raised px-3 py-1.5 font-mono tracking-widest hover:bg-line max-sm:absolute max-sm:left-1/2 max-sm:-translate-x-1/2"
									onclick={() => (sheet = { kind: 'share' })}
									title={room.state?.room.visibility === 'private' ? 'Solo room' : 'Share this room'}
								>
									{#if room.state?.room.visibility === 'private'}
										<Icon name="headphones" size={16} class="text-muted" />
									{/if}
									{code}
									<Icon name="share" size={16} class="text-muted" />
								</button>
							{/if}
							{#if !solo}
								<button
									class="flex items-center gap-1 rounded-full px-2 py-1.5 text-sm text-muted hover:bg-raised hover:text-white"
									aria-label="People in this room"
									onclick={() => (sheet = { kind: 'settings', start: 'room-people' })}
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
							<button class="shrink-0 rounded-full p-1" aria-label="Account and settings" title="Account and settings" onclick={() => (sheet = { kind: 'avatar' })}>
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
								<button class="ml-1 text-white underline" onclick={() => (sheet = { kind: 'clone' })}>Keep the queue: clone it</button>
							{/if}
						</div>
					{/if}
					{#if moduleStatus.state === 'down'}
						<!-- Until it answers again (asked every minute) or you switch it off. -->
						<div class="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 bg-raised px-4 py-1 text-center text-sm text-muted">
							<span>Your own module doesn’t answer ({moduleStatus.text}). Songs play another way meanwhile.</span>
							<button class="text-white underline" onclick={switchOffOwnModule}>Switch it off</button>
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
								<button class="underline" onclick={() => (sheet = { kind: 'clone' })}>Clone it to keep the queue going</button>
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
							{#if stack.hasBackLink}
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
									onMenu={openMenu}
									onArtist={openArtist}
									onAlbum={(a) => openAlbum(a)}
									onPlaylist={openPlaylist}
									onPodcast={openPodcast}
								/>
							{:else if view.kind === 'similar'}
								<SearchResults {room} query="" similarTo={view.song} similarFrom={view.from} onToast={toast} onMenu={openMenu} />
							{:else if view.kind === 'artist'}
								<ArtistView
									{room}
									id={view.id}
									name={view.name}
									onToast={toast}
									onMenu={openMenu}
									onAlbum={(a) => openAlbum({ id: a.id, name: a.title })}
									onPlaylist={openPlaylist}
								/>
							{:else if view.kind === 'album'}
								<AlbumView {room} id={view.id} title={view.title} onToast={toast} onMenu={openMenu} onArtist={openArtist} />
							{:else if view.kind === 'playlist'}
								<PlaylistView {room} id={view.id} title={view.title} personal={view.personal} onToast={toast} onMenu={openMenu} onGone={back} />
							{:else if view.kind === 'podcast'}
								<PodcastView {room} id={view.id} title={view.title} onToast={toast} onMenu={openMenu} />
							{:else if view.kind === 'history'}
								<HistoryView {room} onToast={toast} onMenu={openMenu} />
							{:else if view.kind === 'import'}
								{#key view.jobId}
									<ImportView jobId={view.jobId} onToast={toast} onJob={(jobId) => show({ kind: 'import', jobId })} onPlaylist={(p) => openPlaylist(p, true)} />
								{/key}
							{:else if view.kind === 'genre' || view.kind === 'folder'}
								{@const library = view.library}
								<LocalGroupView
									{room}
									{library}
									genre={view.kind === 'genre' ? view.name : undefined}
									folder={view.kind === 'folder' ? view.path : undefined}
									onFolder={(path) => show({ kind: 'folder', library, path })}
									onToast={toast}
									onMenu={openMenu}
								/>
							{/if}
						{:else if top.kind === 'local'}
							<LocalView
								{room}
								onToast={toast}
								onMenu={openMenu}
								onArtist={openArtist}
								onAlbum={(a) => openAlbum(a)}
								onPlaylist={openPlaylist}
								onPodcast={openPodcast}
								onGenre={(library, name) => show({ kind: 'genre', library, name })}
								onFolder={(library, path) => show({ kind: 'folder', library, path })}
							/>
						{:else if top.kind === 'library'}
							{#key libraryVersion}
							<LibraryView
								onToast={toast}
								onHistory={() => show({ kind: 'history' })}
								onImport={() => show({ kind: 'import' })}
								onArtist={openArtist}
								onAlbum={(a) => openAlbum(a)}
								onPlaylist={(p) => openPlaylist(p, true)}
								onPodcast={openPodcast}
							/>
							{/key}
						{:else}
							<!-- Signing in or out changes the suggestions. -->
							{#key `${youtube.version}:${homeVersion}`}
								<HomeView
									{room}
									onToast={toast}
									onMenu={openMenu}
									onArtist={openArtist}
									onAlbum={(a) => openAlbum(a)}
									onPlaylist={openPlaylist}
									onPodcast={openPodcast}
									onSettings={() => (sheet = { kind: 'settings', start: 'home' })}
								/>
							{/key}
						{/if}
						{/key}
					</main>
				</div>

				{#if expanded && player}
					{#key playerOpened}
					<NowPlaying
						{room}
						{player}
						{positionMs}
						onClose={closePlayer}
						onToast={toast}
						onMenu={openMenu}
						onSongMenu={currentMenu}
						onSave={(song) => (sheet = { kind: 'save', song })}
						onSleepTimer={() => (sheet = { kind: 'sleep' })}
						onArtist={openArtist}
						onAlbum={(a) => openAlbum(a, room?.state?.nowPlaying?.item.song.artists[0]?.name)}
						onPodcast={openPodcast}
					/>
					{/key}
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
				onSleepTimer={() => (sheet = { kind: 'sleep' })}
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

	{#if sheet?.kind === 'menu'}
		<SongMenu
			{room}
			target={sheet.target}
			onClose={() => close('menu')}
			onToast={toast}
			onFindSimilar={(song, from) => show({ kind: 'similar', song, from })}
			onArtist={openArtist}
			onAlbum={openAlbum}
			onPodcast={openPodcast}
			onSave={youtube.account ? (song) => (sheet = { kind: 'save', song }) : undefined}
			onSleepTimer={() => (sheet = { kind: 'sleep' })}
			onGraph={(song) => (sheet = { kind: 'graph', song })}
		/>
	{:else if sheet?.kind === 'graph'}
		<GraphView seed={sheet.song} onClose={() => close('graph')} onToast={toast} />
	{:else if sheet?.kind === 'save'}
		<SaveSheet song={sheet.song} onClose={() => close('save')} onToast={toast} />
	{:else if sheet?.kind === 'clone' && room.state}
		<CloneSheet {room} {host} onClose={() => close('clone')} onToast={toast} />
	{:else if sheet?.kind === 'avatar'}
		<AvatarMenu
			{host}
			{room}
			onClose={() => close('avatar')}
			onSettings={(section) => (sheet = { kind: 'settings', start: section ?? null })}
			onShare={() => (sheet = { kind: 'share' })}
			onPlayOn={player ? () => (sheet = { kind: 'playOn' }) : undefined}
			onClone={() => (sheet = { kind: 'clone' })}
			onRoomSettings={room.can('change_settings') || room.can('edit_roles') || room.can('kick') || room.can('ban') ? () => (sheet = { kind: 'settings', start: null }) : undefined}
			onPartyScreen={() => (sheet = { kind: 'partyScreen' })}
			onShowOnTv={() => (sheet = { kind: 'linkTv' })}
			onLeave={() => (sheet = { kind: 'leave' })}
		/>
	{:else if sheet?.kind === 'settings'}
		<SettingsScreen bind:this={settingsScreen} start={sheet.start} {room} roomHost={host} onClone={() => (sheet = { kind: 'clone' })} onGraph={() => (sheet = { kind: 'graph', song: null })} onClose={() => close('settings')} onToast={toast} />
	{:else if sheet?.kind === 'leave' && room.state}
		<LeaveRoomDialog {room} onClose={() => close('leave')} />
	{:else if sheet?.kind === 'linkTv'}
		<TvLinkDialog {room} {host} onClose={() => close('linkTv')} onToast={toast} />
	{:else if sheet?.kind === 'partyScreen' && room.state}
		<PartyScreen {room} {host} onClose={() => close('partyScreen')} />
	{:else if sheet?.kind === 'sleep' && room.state}
		<SleepTimerSheet {room} {player} onClose={() => close('sleep')} onToast={toast} />
	{:else if sheet?.kind === 'playOn' && player}
		<OutputsSheet {room} {player} onClose={() => close('playOn')} onToast={toast} />
	{:else if sheet?.kind === 'share'}
		<ShareSheet {room} {host} onClose={() => close('share')} onToast={toast} onClone={room.can('clone_room') ? () => (sheet = { kind: 'clone' }) : undefined} />
	{/if}

	{#if room.asking}
		<InterruptDialog {room} />
	{/if}

	<div class="pointer-events-none fixed inset-x-0 bottom-28 z-[80] flex flex-col items-center gap-2 px-4">
		{#if room.undo}
			<div class="pointer-events-auto flex items-center gap-3 rounded-lg bg-white px-4 py-2 text-sm text-black shadow-lg">
				<span class="min-w-0 truncate">{room.undo.text}</span>
				<button class="shrink-0 font-bold" onclick={() => room?.undoRemove()}>Undo</button>
			</div>
		{/if}
		{#each busy as b (b.id)}
			<div class="flex items-center gap-2.5 rounded-lg bg-white px-4 py-2 text-sm text-black shadow-lg" role="status">
				<span class="h-3.5 w-3.5 shrink-0 animate-spin rounded-full border-2 border-black/20 border-t-black"></span>
				<span class="line-clamp-2">{b.text}</span>
			</div>
		{/each}
		{#each [...room.notices, ...toasts] as message (message.id + message.text)}
			{@const action = (message as { action?: ToastAction }).action}
			{#if action}
				<div class="pointer-events-auto flex items-center gap-3 rounded-lg bg-white px-4 py-2 text-sm text-black shadow-lg">
					<span class="min-w-0 truncate">{message.text}</span>
					<button class="shrink-0 font-bold" onclick={() => ((toasts = toasts.filter((t) => t.id !== message.id)), action.run())}>{action.label}</button>
				</div>
			{:else}
				<button
					class="pointer-events-auto rounded-lg bg-white px-4 py-2 text-sm text-black shadow-lg"
					onclick={() => room?.dismissNotice(message.id)}
				>
					{message.text}
				</button>
			{/if}
		{/each}
	</div>
{/if}

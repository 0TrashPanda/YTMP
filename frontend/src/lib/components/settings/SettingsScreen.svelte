<script lang="ts" module>
	export type SettingsSection =
		| 'room-overview'
		| 'room-queue'
		| 'room-people'
		| 'account'
		| 'friends'
		| 'youtube'
		| 'playback'
		| 'radio'
		| 'adding'
		| 'sound'
		| 'look'
		| 'player'
		| 'home'
		| 'search'
		| 'artist'
		| 'menu'
		| 'gestures'
		| 'solo'
		| 'local'
		| 'storage'
		| 'module'
		| 'app'
		| 'sharing'
		| 'server';
</script>

<script lang="ts">
	import { modal } from '../../modal';
	// Settings: one full screen, the same everywhere (in a room, on the room list, and your
	// server's /account). A list of pages under a few headings (phones: the list first; wide
	// screens: next to the page), like YouTube Music's. In a room it starts with the room's own
	// (This room). Most settings follow your YTMP account (settings.svelte.ts): the phone icon
	// next to one keeps it different on this device. Search (settingsSearch.ts) shows every page
	// at once with only the matching settings, which work right there.
	import { flushSync, onDestroy, onMount, tick } from 'svelte';
	import { authServer } from '../../account';
	import { equalizer } from '../../equalizer.svelte';
	import { clearSongCache, getHost, getSongCache, setSongCache } from '../../api';
	import { nativeBridge } from '../../native';
	import { desktopApp } from '../../ownModule.svelte';
	import { preferences } from '../../preferences.svelte';
	import type { AccountInfo, AuthServerInfo, HostInfo } from '../../protocol.gen';
	import type { RoomConnection } from '../../room.svelte';
	import { ADD_LIMITS, BUSY_DELAYS, MAX_ASK_COUNTS, load as loadSettings, onAccount, settings, type CloseRoom, type PlaylistPick, type ReduceMotion, type ToastPlace, type UpNextMenu, type SharedSongLook, type Setting } from '../../settings.svelte';
	import { canTapShare } from '../../share.svelte';
	import { PAGE_SIZES, saved, type BackLimit, type DislikeButton, type HeadphonesAction, type ImageQuality, type SmallFirst, type SwipeOpens, type TabOrder, type TallPlayer, type VideoArt, type ClearRoomBy } from '../../storage';
	import { fold, LABELS, mark, matches, rowsOf, searchWords } from '../../settingsSearch';
	import { youtube } from '../../youtube.svelte';
	import Icon, { type IconName } from '../Icon.svelte';
	import LeaveRoomDialog from '../LeaveRoomDialog.svelte';
	import NumberField from '../NumberField.svelte';
	import AccountSection from './AccountSection.svelte';
	import CacheSection from './CacheSection.svelte';
	import Choice from './Choice.svelte';
	import CountList from './CountList.svelte';
	import DeviceNote from './DeviceNote.svelte';
	import DeviceToggle from './DeviceToggle.svelte';
	import DownloadsSection from './DownloadsSection.svelte';
	import EqualizerSection from './EqualizerSection.svelte';
	import GesturesSection from './GesturesSection.svelte';
	import HomeSection from './HomeSection.svelte';
	import LocalSection from './LocalSection.svelte';
	import OwnModuleSection from './OwnModuleSection.svelte';
	import RoomSections from './RoomSections.svelte';
	import ServerSection, { type Tab as ServerTab } from './ServerSection.svelte';
	import SoloSection from './SoloSection.svelte';
	import SongMenuSection from './SongMenuSection.svelte';
	import Toggle from './Toggle.svelte';
	import YoutubeSection from './YoutubeSection.svelte';
	import FriendsView from '../FriendsView.svelte';
	import FriendPage from '../FriendPage.svelte';
	import type { FriendTarget } from '../../friends.svelte';
	import RadioSection from './RadioSection.svelte';
	import SearchSection from './SearchSection.svelte';
	import ComputerCacheSection from './ComputerCacheSection.svelte';
	import ArtistSection from './ArtistSection.svelte';

	let {
		start = null,
		invite = null,
		room = null,
		roomHost = null,
		onClone,
		onGraph,
		onFriend,
		onClose,
		onToast
	}: {
		/** The page to open; null: the list (wide screens: the first page). */
		start?: SettingsSection | null;
		/** An invite code for signing up (from /account?invite=…). */
		invite?: string | null;
		/** In a room: its settings come first. */
		room?: RoomConnection | null;
		/** Where [room] is hosted. */
		roomHost?: HostInfo | null;
		/** Opens "Clone room". */
		onClone?: () => void;
		/** Opens the song graph (your most played songs). */
		onGraph?: () => void;
		/** In a room: opens a friend's or group's page there (playable songs). Without it the page shows here. */
		onFriend?: (target: FriendTarget) => void;
		onClose: () => void;
		onToast: (text: string) => void;
	} = $props();

	let host = $state<HostInfo | null>(null);
	// This server's accounts (only on a server's own pages).
	let info = $state<AuthServerInfo | null>(null);
	let account = $state<AccountInfo | null>(null);

	onMount(async () => {
		void loadSettings();
		try {
			host = await getHost();
			if (host.kind === 'server') {
				info = await authServer.info();
				account = await authServer.refresh();
			}
		} catch {
			// Offline: what works without the host.
		}
		youtube.load();
	});

	const inApp = !!nativeBridge;
	/** The desktop app (desktop/), or null. */
	const desktop = desktopApp();
	/** *Check for updates* in the desktop app: busy, and what it found. */
	let checkingUpdates = $state(false);
	let updateCheck = $state('');
	async function checkDesktopUpdates() {
		checkingUpdates = true;
		updateCheck = '';
		try {
			const found = await desktop?.checkUpdates?.();
			if (found === false) updateCheck = 'You have the newest version.';
			// Newer apps offer it in a dialog right away; 0.49 up to these showed only an arrow at the top.
			else if (found && desktop?.onQuit && !desktop.askBeforeUpdating) updateCheck = "A new version is downloading. When it's ready, the green arrow at the top installs it.";
		} catch (e) {
			updateCheck = `Couldn't look for updates. ${e instanceof Error ? e.message : String(e)}`;
		} finally {
			checkingUpdates = false;
		}
	}
	/** *Ask before updating* in the desktop app (null: this app can't). */
	let askBeforeUpdating = $state<boolean | null>(null);
	if (desktop?.askBeforeUpdating) {
		desktop
			.askBeforeUpdating()
			.then((on) => (askBeforeUpdating = on))
			.catch(() => {});
	}
	function setAskBeforeUpdating(on: boolean) {
		askBeforeUpdating = on;
		desktop?.askBeforeUpdating?.(on).catch(() => (askBeforeUpdating = !on));
	}
	/** *Copy logs* in the desktop app: this start's logs on the clipboard, to paste where you need them. */
	async function copyDesktopLogs() {
		try {
			const text = await desktop!.readLogs!();
			await navigator.clipboard.writeText(text);
			onToast('Logs copied');
		} catch (e) {
			onToast(`Couldn't copy the logs: ${e instanceof Error ? e.message : String(e)}`);
		}
	}
	const here = inApp ? 'phone' : 'device';
	/** On the phone's own pages (its room list and rooms), which keep the phone's song cache. */
	const onThisPhone = $derived(inApp && host?.kind === 'phone' && ['127.0.0.1', 'localhost'].includes(location.hostname));
	const inRoom = $derived(!!room?.state);
	let openSharedSongs = $state(nativeBridge?.openSharedSongs?.() ?? true);

	type Page = [SettingsSection, string, IconName, boolean];
	const groups = $derived(
		(
			[
				[
					inRoom ? `This room · ${room!.state!.room.name}` : '',
					[
						['room-overview', 'Overview', 'settings', inRoom && room!.can('change_settings')],
						['room-queue', 'Queue', 'queue', inRoom && room!.can('change_settings')],
						['room-people', 'People', 'people', inRoom]
					]
				],
				[
					'You',
					[
						['account', 'Account', 'person', true],
						['friends', 'Friends', 'people', true],
						['youtube', 'YouTube Music', 'library', true]
					]
				],
				[
					'Playing',
					[
						['playback', 'Playback', 'headphones', true],
						['radio', 'Radio', 'radio', true],
						['adding', 'Adding songs', 'playlistAdd', true],
						['sound', 'Sound', 'volume', true]
					]
				],
				[
					'How it looks',
					[
						['look', 'Look', 'palette', true],
						['player', 'Player', 'play', true],
						['home', 'Home', 'home', true],
						['search', 'Search', 'search', true],
						['artist', 'Artist pages', 'artist', true],
						['menu', 'Song menu', 'more', true],
						['gestures', 'Taps and swipes', 'tune', true],
						['solo', 'Solo rooms', 'person', true]
					]
				],
				[
					'Music and storage',
					[
						['local', 'Local music', 'local', true],
						['storage', desktop?.host ? 'Song cache' : 'Song cache and downloads', 'download', inApp || !!desktop?.host],
						['module', 'Where songs play from', 'server', !inApp]
					]
				],
				[
					'App',
					[
						['app', 'App', 'phone', !!nativeBridge?.appVersion || !!desktop],
						['sharing', 'Sharing', 'share', !!nativeBridge?.takeShared],
						['server', 'Server admin', 'server', !!account?.isAdmin && !!info]
					]
				]
			] as [string, Page[]][]
		)
			.map(([title, pages]) => [title, pages.filter(([, , , shown]) => shown)] as [string, Page[]])
			.filter(([, pages]) => pages.length)
	);
	const pages = $derived(groups.flatMap(([, p]) => p));
	/** The heading each page is under. */
	const groupOf = $derived(new Map(groups.flatMap(([heading, list]) => list.map(([id]) => [id, heading] as [SettingsSection, string]))));

	// svelte-ignore state_referenced_locally
	let section = $state<SettingsSection>(start ?? (room?.state ? (room.can('change_settings') ? 'room-overview' : 'room-people') : 'account'));
	/** Phones: a page (true) or the list. */
	// svelte-ignore state_referenced_locally
	let showingSection = $state(start !== null);
	/** Settings → Friends outside a room: a friend's or group's page shown here. */
	let friendPage = $state<FriendTarget | null>(null);
	const title = $derived(pages.find(([id]) => id === section)?.[1] ?? 'Settings');
	/** Pages that are one setting as a whole: the phone icon is next to their title. */
	const pageSettings: Partial<Record<SettingsSection, Setting<unknown>>> = { home: settings.home, menu: settings.songMenu, solo: settings.soloHidden };
	const pageSetting = $derived(pageSettings[section]);
	/** Pages with settings that follow your account: a word on that at the bottom. */
	const followsAccount = $derived(['playback', 'adding', 'look', 'player', 'home', 'search', 'artist', 'menu', 'gestures', 'solo', 'local'].includes(section));

	/** Phones: the list was shown, so Back goes there from a page. */
	// svelte-ignore state_referenced_locally
	let cameFromList = $state(start === null);
	let roomSections = $state<RoomSections>();
	let leaving = $state(false);

	/** The phone's Back button: a step back in the room's pages, or from a page to the list. False: close the settings. */
	export function back(): boolean {
		if (leaving) {
			leaving = false;
			return true;
		}
		if (searching) {
			closeSearch();
			return true;
		}
		if (section.startsWith('room-') && roomSections?.back()) return true;
		if (section === 'friends' && friendPage) {
			friendPage = null;
			return true;
		}
		// Wide screens show the list next to the page.
		if (!showingSection || !cameFromList || matchMedia('(min-width: 768px)').matches) return false;
		showingSection = false;
		return true;
	}

	/** Opens a page; a search that's open closes (on wide screens the list next to it stays clickable). */
	function open(id: SettingsSection) {
		if (searching) closeSearch();
		section = id;
		friendPage = null;
		showingSection = true;
	}

	// Search
	let searching = $state(false);
	/** The Server admin tab a result there opens. */
	let serverTab = $state<ServerTab>('accounts');
	let query = $state('');
	let searchInput = $state<HTMLInputElement>();
	let results = $state<HTMLElement>();
	const words = $derived(searchWords(query));
	/** Pages whose name matches (to open them). */
	const pageHits = $derived(pages.filter(([id, label]) => matches(`${label} ${groupOf.get(id) ?? ''}`, words)));
	/** Matching settings on each page (filled in by [filter]). */
	let hitCounts = $state<Partial<Record<SettingsSection, number>>>({});
	/** Filtered at least once for these words (until then the results stay invisible, so the whole of every page doesn't flash). */
	let filtered = $state(false);
	const settingHits = $derived(Object.values(hitCounts).reduce((a, b) => a + (b ?? 0), 0));

	function openSearch() {
		// Right away, so the field can take the focus while it's still the tap (phones then show the keyboard).
		flushSync(() => (searching = true));
		searchInput?.focus();
	}

	function closeSearch() {
		searching = false;
		query = '';
	}

	/** Marks the matching settings on every page of the results (settingsSearch.ts). */
	function filter() {
		if (!results) return;
		const counts: Partial<Record<SettingsSection, number>> = {};
		for (const page of Array.from(results.querySelectorAll<HTMLElement>('[data-search-page]'))) {
			const body = page.querySelector<HTMLElement>('[data-search-body]');
			const n = body ? mark(body, words) : 0;
			counts[page.dataset.searchPage as SettingsSection] = n;
			page.toggleAttribute('data-found', n > 0);
		}
		hitCounts = counts;
		filtered = true;
	}

	// Again for other words, and when a page changes (one that loads something shows it later).
	$effect(() => {
		void words;
		if (!results) return;
		filter();
		let queued = false;
		const observer = new MutationObserver(() => {
			if (queued) return;
			queued = true;
			requestAnimationFrame(() => {
				queued = false;
				filter();
			});
		});
		observer.observe(results, { childList: true, subtree: true, characterData: true });
		return () => observer.disconnect();
	});
	$effect(() => {
		if (!words.length) filtered = false;
	});

	/** Tapping a setting's name in the results: its page, scrolled to it. */
	async function jump(event: MouseEvent) {
		const target = event.target as HTMLElement;
		const label = target.closest<HTMLElement>(LABELS);
		const page = target.closest<HTMLElement>('[data-search-page]');
		if (!label || !page || label.tagName === 'LABEL' || target.closest('button, a, input, select')) return;
		const text = fold(label.textContent ?? '').trim();
		serverTab = (label.closest<HTMLElement>('[data-admin-tab]')?.dataset.adminTab as ServerTab | undefined) ?? 'accounts';
		closeSearch();
		open(page.dataset.searchPage as SettingsSection);
		cameFromList = true;
		await tick();
		const body = document.querySelector<HTMLElement>('[data-settings-page]');
		if (!body) return;
		const labels = Array.from(body.querySelectorAll<HTMLElement>(LABELS));
		const found = labels.find((e) => fold(e.textContent ?? '').trim() === text);
		if (!found) return;
		const row = rowsOf(body, labels).get(found) ?? found;
		row.scrollIntoView({ block: 'center' });
		row.removeAttribute('data-flash');
		void row.offsetWidth;
		row.setAttribute('data-flash', '');
		setTimeout(() => row.removeAttribute('data-flash'), 1600);
	}

	const backOptions: [BackLimit, string][] = [
		['home', 'Home page'],
		['rooms', 'Room list']
	];
	let soloBack = $state(saved.backLimit(true));
	let partyBack = $state(saved.backLimit(false));
	let headphones = $state(saved.headphonesAction);

	function onKey(event: KeyboardEvent) {
		if (event.key !== 'Escape') return;
		if (leaving) leaving = false;
		else if (searching) closeSearch();
		else if (section === 'friends' && friendPage) friendPage = null;
		else if (!(section.startsWith('room-') && roomSections?.back())) onClose();
	}
	onMount(() => window.addEventListener('keydown', onKey));
	onDestroy(() => window.removeEventListener('keydown', onKey));
</script>

<div class="fixed inset-0 z-[60] flex bg-bg pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]" role="dialog" aria-modal="true" aria-label="Settings" use:modal>
	<!-- The pages -->
	<nav class="{showingSection || searching ? 'hidden md:flex' : 'flex'} w-full shrink-0 flex-col overflow-y-auto border-line px-2 pt-4 pb-8 md:w-72 md:border-r md:pt-6">
		<div class="mb-2 flex items-center gap-2 px-2">
			<button class="rounded-full p-1.5 hover:bg-raised" aria-label="Close settings" onclick={onClose}><Icon name="back" /></button>
			<h1 class="text-xl font-bold">Settings</h1>
		</div>
		<button
			class="mx-2 mt-2 mb-1 flex shrink-0 items-center gap-3 rounded-full bg-raised px-4 py-2.5 text-left text-[15px] text-muted hover:bg-line {searching ? 'md:ring-2 md:ring-white/40' : ''}"
			onclick={openSearch}
		>
			<Icon name="search" size={20} />
			Search settings
		</button>
		{#each groups as [heading, list] (heading)}
			<h2 class="mt-4 mb-1 shrink-0 truncate px-4 text-xs font-medium tracking-wide text-muted uppercase">{heading}</h2>
			{#each list as [id, label, icon] (id)}
				<button
					class="flex shrink-0 items-center gap-4 rounded-lg px-4 py-2.5 text-left {section === id && !searching ? 'md:bg-raised' : ''} hover:bg-raised/60"
					onclick={() => open(id)}
				>
					<Icon name={icon} size={20} class="text-muted" />
					<span class="flex-1 text-[15px]">{label}</span>
					<Icon name="chevron" size={18} class="text-muted md:hidden" />
				</button>
			{/each}
			{#if heading === groups[0][0] && inRoom}
				{#if onClone && room!.can('clone_room')}
					<button class="flex shrink-0 items-center gap-4 rounded-lg px-4 py-2.5 text-left hover:bg-raised/60" onclick={onClone}>
						<Icon name="copy" size={20} class="text-muted" />
						<span class="flex-1 text-[15px]">Clone room</span>
					</button>
				{/if}
				<button class="flex shrink-0 items-center gap-4 rounded-lg px-4 py-2.5 text-left text-accent hover:bg-raised/60" onclick={() => (leaving = true)}>
					<Icon name="logout" size={20} />
					<span class="flex-1 text-[15px]">Leave room</span>
				</button>
			{/if}
		{/each}
	</nav>

	{#if searching}
		<!-- The search: every page with only what matches -->
		<div class="flex min-w-0 flex-1 flex-col">
			<div class="mx-auto flex w-full max-w-2xl shrink-0 items-center gap-2 px-4 pt-4 pb-2 md:px-8 md:pt-8">
				<button class="rounded-full p-1.5 hover:bg-raised" aria-label="Close search" onclick={closeSearch}><Icon name="back" /></button>
				<div class="flex min-w-0 flex-1 items-center gap-2 rounded-full bg-raised pr-1 pl-4 focus-within:ring-2 focus-within:ring-white/40">
					<input
						bind:this={searchInput}
						bind:value={query}
						class="min-w-0 flex-1 bg-transparent py-2.5 text-[15px] outline-none placeholder:text-muted [&::-webkit-search-cancel-button]:appearance-none"
						type="search"
						placeholder="Search settings"
						aria-label="Search settings"
						enterkeyhint="search"
						autocomplete="off"
						spellcheck="false"
					/>
					{#if query}
						<button class="rounded-full p-1.5 text-muted hover:text-white" aria-label="Clear search" onclick={() => ((query = ''), searchInput?.focus())}><Icon name="close" size={20} /></button>
					{/if}
				</div>
			</div>
			<div class="min-h-0 flex-1 overflow-y-auto">
				<div class="mx-auto flex w-full max-w-2xl flex-col gap-6 p-4 pb-12 md:px-8">
					{#if !words.length}
						<p class="px-2 text-sm text-muted">Find a setting by its name or what it does.</p>
					{:else}
						{#if pageHits.length}
							<div class="-mx-2 flex flex-col">
								{#each pageHits as [id, label, icon] (id)}
									<button class="flex items-center gap-4 rounded-lg px-4 py-2.5 text-left hover:bg-raised/60" onclick={() => (closeSearch(), open(id), (cameFromList = true))}>
										<Icon name={icon} size={20} class="text-muted" />
										<span class="min-w-0 flex-1">
											<span class="block text-[15px]">{label}</span>
											{#if groupOf.get(id)}<span class="block truncate text-xs text-muted">{groupOf.get(id)}</span>{/if}
										</span>
										<Icon name="chevron" size={18} class="text-muted" />
									</button>
								{/each}
							</div>
						{/if}
						{#if filtered && !pageHits.length && !settingHits}
							<div class="flex flex-col items-center gap-2 px-4 py-12 text-center">
								<Icon name="search" size={40} class="text-muted" />
								<p class="font-medium">No settings found</p>
								<p class="text-sm text-muted">Nothing matches “{query.trim()}”. Try another word.</p>
							</div>
						{/if}
						<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions (tapping a name is a shortcut; the controls work with keys) -->
						<div bind:this={results} class="search-results flex flex-col gap-8 {filtered ? '' : 'invisible'}" onclick={jump}>
							{#each pages as [id, label] (id)}
								<section data-search-page={id} class="flex flex-col gap-4">
									<button class="-mx-2 flex items-center gap-1 self-start rounded-lg px-2 py-1 text-left text-xs font-medium tracking-wide text-muted uppercase hover:bg-raised hover:text-white" onclick={() => (closeSearch(), (serverTab = 'accounts'), open(id), (cameFromList = true))}>
										{groupOf.get(id) ? `${groupOf.get(id)} · ` : ''}{label}
										<Icon name="chevron" size={14} />
									</button>
									<div data-search-body class="flex flex-col gap-6">
										{@render body(id)}
									</div>
								</section>
							{/each}
						</div>
					{/if}
				</div>
			</div>
		</div>
	{:else}
	<!-- The page -->
	<div class="{showingSection ? 'flex' : 'hidden md:flex'} min-w-0 flex-1 flex-col overflow-y-auto">
		<div data-settings-page class="mx-auto flex w-full max-w-2xl flex-col gap-6 p-4 pb-12 md:p-8">
			<div class="flex items-center gap-2">
				{#if section === 'friends' && friendPage}
					<button class="rounded-full p-1.5 hover:bg-raised" aria-label="Back to friends" onclick={() => (friendPage = null)}><Icon name="back" /></button>
				{:else}
					<button class="rounded-full p-1.5 hover:bg-raised md:hidden" aria-label="Back to settings" onclick={() => ((showingSection = false), (cameFromList = true))}><Icon name="back" /></button>
				{/if}
				<h2 class="min-w-0 flex-1 truncate text-xl font-bold">{title}</h2>
				{#if pageSetting}<DeviceToggle setting={pageSetting} label={title} />{/if}
				<button class="hidden rounded-full p-1.5 text-muted hover:bg-raised hover:text-white md:block" aria-label="Close settings" onclick={onClose}>
					<Icon name="close" />
				</button>
			</div>
			{#if pageSetting}<div class="-mt-4"><DeviceNote setting={pageSetting} /></div>{/if}

			{@render body(section)}

			{#if followsAccount}
				<p class="text-xs {preferences.error ? 'text-accent' : 'text-muted'}">
					{#if preferences.error}
						Couldn't save to your account just now; it's saved on this {here} and goes up later.
					{:else if onAccount()}
						On your YTMP account: the same on all your devices. Tap <Icon name="phone" size={12} class="inline align-[-1px]" /> next to a setting to keep it different on this {here}.
					{:else}
						Saved on this {here}. Log in with your YTMP account to have them on all your devices.
					{/if}
				</p>
			{/if}
		</div>
	</div>
	{/if}
</div>

{#if leaving && room}
	<LeaveRoomDialog {room} onClose={() => (leaving = false)} />
{/if}

<!-- A settings page's settings: the open page, or every page at once in the search. -->
{#snippet body(id: SettingsSection)}
	{#if id === 'room-overview' && inRoom}
		<RoomSections bind:this={roomSections} room={room!} host={roomHost} section="overview" />
	{:else if id === 'room-queue' && inRoom}
		<RoomSections bind:this={roomSections} room={room!} host={roomHost} section="queue" />
	{:else if id === 'room-people' && inRoom}
		<RoomSections bind:this={roomSections} room={room!} host={roomHost} section="people" />
	{:else if id === 'account'}
		<AccountSection {host} {info} {account} {invite} onAccount={(a) => (account = a)} {onToast} />
	{:else if id === 'friends'}
		{#if friendPage && !searching}
			<FriendPage target={friendPage} {onToast} />
		{:else}
			<FriendsView {onToast} onOpen={(target) => (onFriend ? onFriend(target) : (friendPage = target))} />
		{/if}
	{:else if id === 'youtube'}
		<YoutubeSection {onToast} />
	{:else if id === 'playback'}
		<Choice
			label="Next while paused"
			hint="Like YouTube Music, the next song plays. Or it loads and the room stays paused."
			setting={settings.nextPlays}
			options={[
				[true, 'Plays the next song'],
				[false, 'Stays paused']
			]}
		/>
		<Choice
			label="Clear room"
			hint="Stops the song and empties Up next and autoplay at once. Undo puts it all back. Swiping down works on the mini player of a phone."
			setting={settings.clearRoomBy}
			options={[
				['button', 'Button in Up next'],
				['swipe', 'Swipe the player down'],
				['both', 'Both']
			] as [ClearRoomBy, string][]}
		/>
		<Toggle
			label="Start radio clears played songs"
			hint="Start radio always replaces what's up next. It can also clear the songs that played before, above the current song, like YouTube Music. In a party room that needs permission to remove other people's songs."
			setting={settings.radioClearsHistory}
		/>
		<Toggle
			label="Tap a song to play an album or playlist from there"
			hint="Like YouTube Music: when nothing is queued or playing, tapping a song on an album or playlist page plays it from that song (the songs after it come next). Otherwise a tap does what Taps and swipes says. Play from here in the song menu always does it."
			setting={settings.tapPlaysFromHere}
		/>
		<Toggle
			label="Ask before interrupting a party"
			hint="In a party room, while others listen along: asks before you pause, skip, seek, play something else, change an episode's speed or switch off a speaker for everyone (not when the others are only your own devices). Headphone and lock screen buttons don't ask: their pause stops the music only on your phone."
			setting={settings.askInParty}
		/>
		<Toggle
			label="Pause only stops it here"
			hint="In a party room, while others listen along, Pause stops the music on this device and it goes on for everyone else. When it isn't playing here, Pause pauses the room (asked first)."
			setting={settings.pauseStopsHere}
		/>
		<Toggle
			label="Press play to play here"
			hint="In a room of your own (not a party), when the music isn't playing on any device, speaker or TV, Play starts it on this device."
			setting={settings.playStartsHere}
		/>
		<Toggle
			label="Ask before leaving a room"
			hint="Off: Leave room leaves right away. As the owner of a party room with others in it, it still asks who runs the room after you."
			setting={settings.askLeave}
		/>
		<Choice
			label="Close a solo room"
			hint="With the ✕ in the room list. Right away: it's gone from the list, with Undo for a few seconds."
			setting={settings.closeSolo}
			options={[
				['undo', 'Right away, with Undo'],
				['ask', 'Ask first']
			] as [CloseRoom, string][]}
		/>
		<Choice
			label="Close a party room"
			hint="With the ✕ in the room list. Closing removes everyone in it and deletes its queue."
			setting={settings.closeParty}
			options={[
				['ask', 'Ask first'],
				['undo', 'Right away, with Undo']
			] as [CloseRoom, string][]}
		/>
		<Toggle
			label="Your other devices can switch Play here on"
			hint="Logged in with the same account and in the same room, your other devices can switch Play here on and off on this one, from Play on. A browser plays only after a click on its page."
			setting={settings.remotePlay}
		/>
		{#if inApp}
			<h3 class="-mb-3 text-xs font-medium tracking-wide text-muted uppercase">Podcasts</h3>
			<Toggle
				label="Skip silence"
				hint="Skips the quiet parts of an episode, so it takes less time. Only when you're the only one listening: the room follows you."
				setting={settings.skipSilence}
			/>
		{:else}
			<p class="text-xs text-muted">Keyboard (<kbd>?</kbd> shows them all): <kbd>Space</kbd> or <kbd>K</kbd> play/pause · <kbd>N</kbd> next · <kbd>P</kbd> previous · <kbd>←</kbd> <kbd>→</kbd> 10 seconds back or ahead · <kbd>↑</kbd> <kbd>↓</kbd> volume · <kbd>/</kbd> search.</p>
		{/if}
	{:else if id === 'adding'}
		<Choice
			label="Most songs from one playlist"
			hint="Play, Play next and Add on a playlist or album add at most this many songs when they don't ask. Long playlists (like Liked music) then only load the part that's needed."
			setting={settings.playlistMax}
			options={[
				[100, '100'],
				[200, '200'],
				[500, '500'],
				[1000, '1000']
			]}
		>
			<NumberField value={settings.playlistMax.value} min={ADD_LIMITS.min} max={ADD_LIMITS.max} unit="songs" label="Most songs from one playlist" onchange={(v) => settings.playlistMax.set(v)} />
		</Choice>
		<Choice
			label="Which songs"
			hint="When a playlist has more: the first ones, or picked at random from all of it (that loads the whole playlist first, so it takes longer)."
			setting={settings.playlistPick}
			options={[
				['first', 'The first'],
				['random', 'At random']
			] as [PlaylistPick, string][]}
		/>
		<Choice
			label="Ask first above"
			hint="A playlist with more songs than this asks how many, with the choices below."
			setting={settings.playlistAskOver}
			options={[
				[0, 'Never ask'],
				[50, '50'],
				[100, '100'],
				[200, '200']
			]}
		>
			<NumberField value={settings.playlistAskOver.value} min={0} max={ADD_LIMITS.max} unit="songs" label="Ask first above" onchange={(v) => settings.playlistAskOver.set(v)} />
		</Choice>
		<CountList
			label="Choices when it asks"
			hint="A button for each number (up to {MAX_ASK_COUNTS}). A number that's as long as the playlist or longer doesn't show."
			setting={settings.playlistAskCounts}
			min={1}
			max={ADD_LIMITS.max}
			most={MAX_ASK_COUNTS}
			unit="songs"
		/>
		<Toggle
			label="Also: add all of it"
			hint="When it asks, a button for the whole playlist, however long (Liked music can take a while to load)."
			setting={settings.playlistAskAll}
		/>
		<Toggle
			label="Also: type a number"
			hint="When it asks, a box to type how many songs, for a number that isn't among the choices."
			setting={settings.playlistAskCustom}
		/>
		<Toggle
			label="Also: first or at random"
			hint="When it asks, a switch between the first songs and songs picked at random, for that time. It starts as set in Which songs."
			setting={settings.playlistAskPick}
		/>
		<Choice
			label="Say what's going on after"
			hint="Something that takes a moment (adding a long playlist, saving to a playlist) says so (“Adding Liked music…”) when it isn't done after this long, and what it did when it's done."
			setting={settings.busyDelay}
			options={[
				[0, 'Right away'],
				[150, '0.15 s'],
				[500, '0.5 s'],
				[1000, '1 s']
			]}
		>
			<NumberField value={settings.busyDelay.value} min={0} max={BUSY_DELAYS.max} step={50} unit="ms" label="Say what's going on after" onchange={(v) => settings.busyDelay.set(v)} />
		</Choice>
	{:else if id === 'sound'}
		{#if equalizer.available}
			<EqualizerSection />
		{/if}
		{#if inApp}
			<Choice
				label="Headphones disconnected"
				hint="Pause only pauses the room when you're the only one listening; otherwise it stops playing here."
				options={[
					['pause', 'Pause'],
					['stop', 'Stop here'],
					['keep', 'Keep playing']
				] as [HeadphonesAction, string][]}
				value={headphones}
				onchange={(v) => {
					headphones = v;
					saved.headphonesAction = v;
					window.dispatchEvent(new Event('ytmp:settings'));
				}}
			/>
			<Toggle
				label="Volume buttons control speakers"
				hint="When the room plays on speakers or TVs and not on this phone, the volume buttons change their volume while the app is open. Several speakers go up and down together and keep their balance."
				setting={settings.volumeKeys}
			/>
			<Toggle
				label="Keep playing with other apps"
				hint="Music keeps playing while another app plays sound, like an audiobook or a video, and starting music doesn't stop that app. Calls still make it quiet on this phone until they're over."
				setting={settings.mixWithOtherApps}
			/>
		{/if}
		<p class="text-xs text-muted">The sync adjustment for Bluetooth is under <em>Play on</em> in a room. Sound settings are only for this {here}.</p>
	{:else if id === 'look'}
		<Choice
			label="Image quality"
			hint="Low loads smaller pictures (album art, artists, playlists): less data, a bit less sharp."
			setting={settings.imageQuality}
			options={[
				['high', 'High'],
				['low', 'Low']
			] as [ImageQuality, string][]}
		/>
		<Choice
			label="Small pictures first"
			hint="Big pictures (cards on Home, the player's album art, the top of album and playlist pages) show a small version at once, then the sharp one when it's in. Tiny is quickest but blurrier at first. Not with Low image quality."
			setting={settings.smallFirst}
			options={[
				['small', 'Small'],
				['tiny', 'Tiny'],
				['off', 'Off: wait for the sharp one']
			] as [SmallFirst, string][]}
		/>
		<Choice
			label="Upright screen"
			hint="On a big screen that is taller than wide, like a monitor turned on its side: the full player shows the album art above Up next, or next to it."
			setting={settings.tallPlayer}
			options={[
				['stacked', 'Art above'],
				['side', 'Side by side']
			] as [TallPlayer, string][]}
		/>
		<Choice
			label="Video pictures"
			hint="Songs from a music video only have a wide picture. Crop fills the square (like YouTube Music); Fit shows all of it, with bars."
			setting={settings.videoArt}
			options={[
				['crop', 'Crop'],
				['fit', 'Fit']
			] as [VideoArt, string][]}
		/>
		<Choice
			label="Songs per page"
			hint="Long lists (Liked music, your playlists, songs in Local) show this many songs at first, and this many more when you get to the end."
			setting={settings.pageSize}
			options={[
				[100, '100'],
				[300, '300'],
				[1000, '1000']
			]}
		>
			<NumberField value={settings.pageSize.value} min={PAGE_SIZES.min} max={PAGE_SIZES.max} unit="songs" label="Songs per page" onchange={(v) => settings.pageSize.set(v)} />
		</Choice>
		<Choice
			label="Reduce motion"
			hint="Less moving on screen: the player and sheets appear without sliding, nothing pops or scrolls smoothly, and the bars on the song playing stand still. By default as your phone or computer is set (its accessibility settings)."
			setting={settings.reduceMotion}
			options={[
				['system', 'Like this device'],
				['on', 'On'],
				['off', 'Off']
			] as [ReduceMotion, string][]}
		/>
		<Toggle
			label="Combine messages"
			hint="Messages at the bottom show one at a time, and ones that follow each other become one: three songs added quickly say 'Added 3 songs to the queue', with one Undo for all three. Off: each shows, stacked."
			setting={settings.toastMerge}
		/>
		<Choice
			label="Where messages show"
			hint="Just above the player bar: in the middle, or on a computer at the bottom left like YouTube Music. Swipe one down to hide it."
			setting={settings.toastPlace}
			options={[
				['center', 'In the middle'],
				['left', 'Bottom left (computers)']
			] as [ToastPlace, string][]}
		/>
	{:else if id === 'player'}
		<Choice
			label="Order of the tabs"
			hint="Up next first, like YouTube Music, or Related first (they swap places)."
			setting={settings.tabOrder}
			options={[
				['upnext', 'Up next first'],
				['related', 'Related first']
			] as [TabOrder, string][]}
		/>
		<Choice
			label="Swiping up on the tabs"
			hint="On a phone, swiping up on Up next, Lyrics or Related at the bottom of the player opens that tab, or always Up next."
			setting={settings.swipeOpens}
			options={[
				['tab', 'That tab'],
				['upnext', 'Always Up next']
			] as [SwipeOpens, string][]}
		/>
		<Choice
			label="Up next's ⋮ with nothing queued"
			hint="The ⋮ above Up next (Remove played songs, Clear queue, Clear room) sits next to the song count. With nothing queued: on a line saying what plays instead ('Playing from', like YouTube Music), or at the end of the Radio from chips."
			setting={settings.upNextMenu}
			options={[
				['line', 'On a Playing from line'],
				['chips', 'Next to the chips']
			] as [UpNextMenu, string][]}
		/>
		<Toggle
			label="Slide long titles"
			hint="A song title too long for the player slides along slowly to show the rest, like YouTube Music. Off: it's cut off with '…'. Never with Reduce motion."
			setting={settings.titleScroll}
		/>
		<Toggle
			label="Song playing above Up next"
			hint="On a phone, with Up next, Lyrics or Related open: the song playing stays at the top, with play/pause and next, like YouTube Music. Tap it to go back to the player."
			setting={settings.upNextSongRow}
		/>
		<Choice
			label="Dislike button in the player bar"
			hint="Thumbs down next to thumbs up at the bottom (computers). Dimmed: still there, but darker, so you don't hit it by accident."
			setting={settings.dislikeButton}
			options={[
				['normal', 'Normal'],
				['dim', 'Dimmed'],
				['hidden', 'Hidden']
			] as [DislikeButton, string][]}
		/>
	{:else if id === 'home'}
		<Choice
			label="New suggestions after"
			hint="Home and Library show what they had right away, and only load new ones from YouTube Music once they're this old. Pulling down (or the logo on Home) always loads new ones."
			setting={settings.homeRefresh}
			options={[
				[30, '30 min'],
				[6 * 60, '6 h'],
				[24 * 60, '1 day'],
				[0, 'Only by hand']
			]}
		>
			<NumberField value={settings.homeRefresh.value} min={0} max={7 * 24 * 60} unit="min" label="New suggestions after" onchange={(v) => settings.homeRefresh.set(v)} />
		</Choice>
		<HomeSection />
	{:else if id === 'search'}
		<SearchSection />
	{:else if id === 'artist'}
		<ArtistSection />
	{:else if id === 'radio'}
		<RadioSection {onToast} {onThisPhone} {onGraph} />
	{:else if id === 'menu'}
		<SongMenuSection />
	{:else if id === 'gestures'}
		<GesturesSection />
	{:else if id === 'solo'}
		<SoloSection />
	{:else if id === 'local'}
		<LocalSection {onToast} />
	{:else if id === 'storage'}
		{#if desktop?.host}
			<ComputerCacheSection {onToast} />
		{:else if onThisPhone}
			<DownloadsSection {onToast} />
			<CacheSection load={getSongCache} clear={clearSongCache} change={setSongCache} {onToast} />
		{:else}
			<p class="text-sm text-muted">The song cache's settings are on this phone's own pages (the room list, rooms on this phone).</p>
		{/if}
	{:else if id === 'module'}
		<OwnModuleSection {onToast} />
	{:else if id === 'sharing' && nativeBridge?.takeShared}
		{#if canTapShare()}
			<Toggle
				label="Share by holding phones together"
				hint="While a room's or song's Share sheet is open, holding another phone against this one hands over the link (NFC). Their phone opens it in YTMP, or else in the browser or YouTube Music. iPhones (XS and newer) can read it too."
				setting={settings.tapToShare}
			/>
		{:else}
			<div>
				<p class="text-sm font-medium">Share by holding phones together</p>
				<p class="text-xs text-muted">This phone has no NFC, so it can't share this way. It can still get rooms and songs from other phones.</p>
			</div>
		{/if}
		<Toggle
			label="Open shared songs in YTMP"
			hint="A song another phone hands over (or that you share to YTMP) shows here, with Play now, Add to queue and more. Off: songs from another phone open in YouTube Music instead."
			checked={openSharedSongs}
			onchange={(on) => {
				openSharedSongs = on;
				nativeBridge?.setOpenSharedSongs?.(on);
			}}
		/>
		<Choice
			label="Shared songs show as"
			hint="A full page like YouTube Music's song page, or a sheet over the room like the song menu."
			setting={settings.sharedSongLook}
			options={[
				['page', 'Full page'],
				['sheet', 'Sheet']
			] as [SharedSongLook, string][]}
		/>
	{:else if id === 'app' && desktop}
		<div class="flex items-center gap-3">
			<div class="flex-1">
				<p class="text-sm font-medium">Version</p>
				<p class="text-xs text-muted">YTMP {desktop.version} for {desktop.platform === 'windows' ? 'Windows' : 'Linux'}.{desktop.updates ? (askBeforeUpdating !== null ? '' : desktop.onQuit ? ' New versions install when the app starts.' : ' New versions are offered when the app starts.') : ''}</p>
				{#if updateCheck}<p class="text-xs text-muted" aria-live="polite">{updateCheck}</p>{/if}
			</div>
			{#if desktop.updates && desktop.checkUpdates}
				<button class="shrink-0 rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-50" disabled={checkingUpdates} onclick={checkDesktopUpdates}>{checkingUpdates ? 'Looking…' : 'Check for updates'}</button>
			{/if}
		</div>
		{#if desktop.updates && askBeforeUpdating !== null}
			<Toggle
				label="Ask before updating"
				hint={askBeforeUpdating ? 'A new version is offered when the app starts: restart now or later.' : 'A new version installs when the app starts.'}
				checked={askBeforeUpdating}
				onchange={setAskBeforeUpdating}
			/>
		{/if}
		<div class="flex items-center gap-3">
			<div class="flex-1">
				<p class="text-sm font-medium">Server</p>
				<p class="text-xs text-muted">{location.host}</p>
			</div>
			<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={() => desktop?.changeServer?.()}>Change server</button>
		</div>
		{#if desktop.openLogs || desktop.readLogs}
			<div class="flex flex-col gap-2">
				<div>
					<p class="text-sm font-medium">Logs</p>
					<p class="text-xs text-muted">What the app did since it started, to send when something goes wrong. Song names and requests, no passwords.</p>
				</div>
				<div class="flex flex-wrap gap-2">
					{#if desktop.openLogs}
						<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={() => desktop?.openLogs?.().catch((e) => onToast(`Couldn't open the folder: ${e}`))}
							>Open logs folder</button
						>
					{/if}
					{#if desktop.readLogs}
						<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={copyDesktopLogs}>Copy logs</button>
					{/if}
				</div>
			</div>
		{/if}
		<Toggle
			label="Open the solo room you were in"
			hint="Opening the app goes straight back into the solo room you were in when you closed it, instead of the room list."
			setting={settings.reopenSolo}
		/>
		<Toggle
			label="Open the party room you were in"
			hint="The same for party rooms (if the room is still there)."
			setting={settings.reopenParty}
		/>
	{:else if id === 'app' && nativeBridge?.appVersion}
		<div class="flex items-center gap-3">
			<div class="flex-1">
				<p class="text-sm font-medium">Version</p>
				<p class="text-xs text-muted">YTMP {nativeBridge.appVersion()}</p>
			</div>
			<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={() => nativeBridge?.checkForUpdates?.()}>Check for updates</button>
		</div>
		<div class="flex items-center gap-3">
			<div class="flex-1">
				<p class="text-sm font-medium">Logs</p>
				<p class="text-xs text-muted">The last 7 days of what the app did, to send when something goes wrong. Song names and rooms, no passwords.</p>
			</div>
			<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={() => nativeBridge?.shareLogs?.()}>Send logs</button>
		</div>
		<Toggle
			label="Open the solo room you were in"
			hint="Opening the app goes straight back into the solo room you were in when you closed it, instead of the room list."
			setting={settings.reopenSolo}
		/>
		<Toggle
			label="Open the party room you were in"
			hint="The same for party rooms (if the room is still there)."
			setting={settings.reopenParty}
		/>
		<Choice
			label="Back button in a solo room"
			hint="Where the phone's Back button stops once you're on Home."
			options={backOptions}
			value={soloBack}
			onchange={(v) => {
				soloBack = v;
				saved.setBackLimit(true, v);
			}}
		/>
		<Choice
			label="Back button in a party room"
			hint="Where the phone's Back button stops once you're on Home."
			options={backOptions}
			value={partyBack}
			onchange={(v) => {
				partyBack = v;
				saved.setBackLimit(false, v);
			}}
		/>
	{:else if id === 'server' && info && account}
		<ServerSection {info} me={account} all={searching} start={serverTab} {onToast} />
	{/if}
{/snippet}

<style>
	/* The search: only the matching rows (settingsSearch.ts), with the headings of the cards they're in. */
	.search-results :global([data-search-page]:not([data-found])) {
		display: none;
	}
	.search-results :global([data-search-body] *:not([data-path]):not([data-hit]):not([data-hit] *):not([data-path] > h3)) {
		display: none !important;
	}
	.search-results :global([data-search-body] [data-hit] p.font-medium),
	.search-results :global([data-search-body] [data-hit] h3) {
		cursor: pointer;
	}
	/* Where a tapped result landed. */
	:global([data-flash]) {
		animation: search-flash 1.6s ease-out;
		border-radius: 0.75rem;
	}
	@keyframes search-flash {
		0%,
		40% {
			box-shadow: 0 0 0 8px rgb(255 255 255 / 0.12);
			background: rgb(255 255 255 / 0.12);
		}
		100% {
			box-shadow: 0 0 0 8px transparent;
			background: transparent;
		}
	}
</style>

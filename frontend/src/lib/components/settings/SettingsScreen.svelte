<script lang="ts" module>
	export type SettingsSection =
		| 'room-overview'
		| 'room-queue'
		| 'room-people'
		| 'account'
		| 'youtube'
		| 'playback'
		| 'adding'
		| 'sound'
		| 'look'
		| 'player'
		| 'home'
		| 'menu'
		| 'gestures'
		| 'solo'
		| 'local'
		| 'storage'
		| 'module'
		| 'app'
		| 'server';
</script>

<script lang="ts">
	// Settings: one full screen, the same everywhere (in a room, on the room list, and your
	// server's /account). A list of pages under a few headings (phones: the list first; wide
	// screens: next to the page), like YouTube Music's. In a room it starts with the room's own
	// (This room). Most settings follow your YTMP account (settings.svelte.ts): the phone icon
	// next to one keeps it different on this device.
	import { onDestroy, onMount } from 'svelte';
	import { authServer } from '../../account';
	import { equalizer } from '../../equalizer.svelte';
	import { clearSongCache, getHost, getSongCache, setSongCache } from '../../api';
	import { nativeBridge } from '../../native';
	import { preferences } from '../../preferences.svelte';
	import type { AccountInfo, AuthServerInfo, HostInfo } from '../../protocol.gen';
	import type { RoomConnection } from '../../room.svelte';
	import { ADD_LIMITS, BUSY_DELAYS, MAX_ASK_COUNTS, load as loadSettings, onAccount, settings, type PlaylistPick, type Setting } from '../../settings.svelte';
	import { PAGE_SIZES, saved, type BackLimit, type DislikeButton, type HeadphonesAction, type ImageQuality, type SwipeOpens, type TabOrder, type VideoArt } from '../../storage';
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
	import ServerSection from './ServerSection.svelte';
	import SoloSection from './SoloSection.svelte';
	import SongMenuSection from './SongMenuSection.svelte';
	import Toggle from './Toggle.svelte';
	import YoutubeSection from './YoutubeSection.svelte';

	let {
		start = null,
		invite = null,
		room = null,
		roomHost = null,
		onClone,
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
	const here = inApp ? 'phone' : 'device';
	/** On the phone's own pages (its room list and rooms), which keep the phone's song cache. */
	const onThisPhone = $derived(inApp && host?.kind === 'phone' && ['127.0.0.1', 'localhost'].includes(location.hostname));
	const inRoom = $derived(!!room?.state);

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
						['youtube', 'YouTube Music', 'library', true]
					]
				],
				[
					'Playing',
					[
						['playback', 'Playback', 'headphones', true],
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
						['menu', 'Song menu', 'more', true],
						['gestures', 'Taps and swipes', 'tune', true],
						['solo', 'Solo rooms', 'person', true]
					]
				],
				[
					'Music and storage',
					[
						['local', 'Local music', 'local', true],
						['storage', 'Song cache and downloads', 'download', inApp],
						['module', 'Your own module', 'server', !inApp]
					]
				],
				[
					'App',
					[
						['app', 'App', 'phone', !!nativeBridge?.appVersion],
						['server', 'Server admin', 'server', !!account?.isAdmin && !!info]
					]
				]
			] as [string, Page[]][]
		)
			.map(([title, pages]) => [title, pages.filter(([, , , shown]) => shown)] as [string, Page[]])
			.filter(([, pages]) => pages.length)
	);
	const pages = $derived(groups.flatMap(([, p]) => p));

	// svelte-ignore state_referenced_locally
	let section = $state<SettingsSection>(start ?? (room?.state ? (room.can('change_settings') ? 'room-overview' : 'room-people') : 'account'));
	/** Phones: a page (true) or the list. */
	// svelte-ignore state_referenced_locally
	let showingSection = $state(start !== null);
	const title = $derived(pages.find(([id]) => id === section)?.[1] ?? 'Settings');
	/** Pages that are one setting as a whole: the phone icon is next to their title. */
	const pageSettings: Partial<Record<SettingsSection, Setting<unknown>>> = { home: settings.home, menu: settings.songMenu, solo: settings.soloHidden };
	const pageSetting = $derived(pageSettings[section]);
	/** Pages with settings that follow your account: a word on that at the bottom. */
	const followsAccount = $derived(['playback', 'adding', 'look', 'player', 'home', 'menu', 'gestures', 'solo', 'local'].includes(section));

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
		if (section.startsWith('room-') && roomSections?.back()) return true;
		// Wide screens show the list next to the page.
		if (!showingSection || !cameFromList || matchMedia('(min-width: 768px)').matches) return false;
		showingSection = false;
		return true;
	}

	function open(id: SettingsSection) {
		section = id;
		showingSection = true;
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
		else if (!(section.startsWith('room-') && roomSections?.back())) onClose();
	}
	onMount(() => window.addEventListener('keydown', onKey));
	onDestroy(() => window.removeEventListener('keydown', onKey));
</script>

<div class="fixed inset-0 z-[60] flex bg-bg" role="dialog" aria-modal="true" aria-label="Settings">
	<!-- The pages -->
	<nav class="{showingSection ? 'hidden md:flex' : 'flex'} w-full shrink-0 flex-col overflow-y-auto border-line px-2 pt-4 pb-8 md:w-72 md:border-r md:pt-6">
		<div class="mb-2 flex items-center gap-2 px-2">
			<button class="rounded-full p-1.5 hover:bg-raised" aria-label="Close settings" onclick={onClose}><Icon name="back" /></button>
			<h1 class="text-xl font-bold">Settings</h1>
		</div>
		{#each groups as [heading, list] (heading)}
			<h2 class="mt-4 mb-1 shrink-0 truncate px-4 text-xs font-medium tracking-wide text-muted uppercase">{heading}</h2>
			{#each list as [id, label, icon] (id)}
				<button
					class="flex shrink-0 items-center gap-4 rounded-lg px-4 py-2.5 text-left {section === id ? 'md:bg-raised' : ''} hover:bg-raised/60"
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

	<!-- The page -->
	<div class="{showingSection ? 'flex' : 'hidden md:flex'} min-w-0 flex-1 flex-col overflow-y-auto">
		<div class="mx-auto flex w-full max-w-2xl flex-col gap-6 p-4 pb-12 md:p-8">
			<div class="flex items-center gap-2">
				<button class="rounded-full p-1.5 hover:bg-raised md:hidden" aria-label="Back to settings" onclick={() => ((showingSection = false), (cameFromList = true))}><Icon name="back" /></button>
				<h2 class="min-w-0 flex-1 truncate text-xl font-bold">{title}</h2>
				{#if pageSetting}<DeviceToggle setting={pageSetting} label={title} />{/if}
				<button class="hidden rounded-full p-1.5 text-muted hover:bg-raised hover:text-white md:block" aria-label="Close settings" onclick={onClose}>
					<Icon name="close" />
				</button>
			</div>
			{#if pageSetting}<div class="-mt-4"><DeviceNote setting={pageSetting} /></div>{/if}

			{#if section === 'room-overview' && inRoom}
				<RoomSections bind:this={roomSections} room={room!} host={roomHost} section="overview" />
			{:else if section === 'room-queue' && inRoom}
				<RoomSections bind:this={roomSections} room={room!} host={roomHost} section="queue" />
			{:else if section === 'room-people' && inRoom}
				<RoomSections bind:this={roomSections} room={room!} host={roomHost} section="people" />
			{:else if section === 'account'}
				<AccountSection {host} {info} {account} {invite} onAccount={(a) => (account = a)} {onToast} />
			{:else if section === 'youtube'}
				<YoutubeSection {onToast} />
			{:else if section === 'playback'}
				<Choice
					label="Next while paused"
					hint="Like YouTube Music, the next song plays. Or it loads and the room stays paused."
					setting={settings.nextPlays}
					options={[
						[true, 'Plays the next song'],
						[false, 'Stays paused']
					]}
				/>
				<Toggle
					label="Start radio clears played songs"
					hint="Start radio always replaces what's up next. It can also clear the songs that played before, above the current song, like YouTube Music. In a party room that needs permission to remove other people's songs."
					setting={settings.radioClearsHistory}
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
					<p class="text-xs text-muted">Keyboard: <kbd>Space</kbd> or <kbd>K</kbd> play/pause · <kbd>N</kbd> next · <kbd>P</kbd> previous · <kbd>←</kbd> <kbd>→</kbd> 10 seconds back or ahead · <kbd>↑</kbd> <kbd>↓</kbd> volume · <kbd>/</kbd> search.</p>
				{/if}
			{:else if section === 'adding'}
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
			{:else if section === 'sound'}
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
				{/if}
				<p class="text-xs text-muted">The sync adjustment for Bluetooth is under <em>Play on</em> in a room. Sound settings are only for this {here}.</p>
			{:else if section === 'look'}
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
			{:else if section === 'player'}
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
					label="Dislike button in the player bar"
					hint="Thumbs down next to thumbs up at the bottom (computers). Dimmed: still there, but darker, so you don't hit it by accident."
					setting={settings.dislikeButton}
					options={[
						['normal', 'Normal'],
						['dim', 'Dimmed'],
						['hidden', 'Hidden']
					] as [DislikeButton, string][]}
				/>
			{:else if section === 'home'}
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
			{:else if section === 'menu'}
				<SongMenuSection />
			{:else if section === 'gestures'}
				<GesturesSection />
			{:else if section === 'solo'}
				<SoloSection />
			{:else if section === 'local'}
				<LocalSection {onToast} />
			{:else if section === 'storage'}
				{#if onThisPhone}
					<DownloadsSection {onToast} />
					<CacheSection load={getSongCache} clear={clearSongCache} change={setSongCache} {onToast} />
				{:else}
					<p class="text-sm text-muted">The song cache's settings are on this phone's own pages (the room list, rooms on this phone).</p>
				{/if}
			{:else if section === 'module'}
				<OwnModuleSection {onToast} />
			{:else if section === 'app' && nativeBridge?.appVersion}
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
			{:else if section === 'server' && info && account}
				<ServerSection {info} me={account} {onToast} />
			{/if}

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
</div>

{#if leaving && room}
	<LeaveRoomDialog {room} onClose={() => (leaving = false)} />
{/if}

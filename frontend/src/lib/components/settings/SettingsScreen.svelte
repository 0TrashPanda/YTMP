<script lang="ts" module>
	export type SettingsSection = 'account' | 'youtube' | 'playback' | 'solo' | 'gestures' | 'menu' | 'home' | 'local' | 'look' | 'app' | 'server';
</script>

<script lang="ts">
	// Settings: one full screen, the same everywhere (in a room, on the room list, and your
	// server's /account). Sections on the left (phones: a list first), the section on the right.
	import { onDestroy, onMount } from 'svelte';
	import { authServer } from '../../account';
	import { equalizer } from '../../equalizer.svelte';
	import { clearSongCache, getHost, getSongCache, setSongCache } from '../../api';
	import { images, setDislikeButton, setImageQuality, setSwipeOpens, setTabOrder, setVideoArt } from '../../images.svelte';
	import { nativeBridge } from '../../native';
	import type { AccountInfo, AuthServerInfo, HostInfo } from '../../protocol.gen';
	import { PAGE_SIZES, saved, type BackLimit, type DislikeButton, type HeadphonesAction, type ImageQuality, type SwipeOpens, type TabOrder, type VideoArt } from '../../storage';
	import { youtube } from '../../youtube.svelte';
	import Icon, { type IconName } from '../Icon.svelte';
	import NumberField from '../NumberField.svelte';
	import AccountSection from './AccountSection.svelte';
	import CacheSection from './CacheSection.svelte';
	import DownloadsSection from './DownloadsSection.svelte';
	import Choice from './Choice.svelte';
	import EqualizerSection from './EqualizerSection.svelte';
	import GesturesSection from './GesturesSection.svelte';
	import HomeSection from './HomeSection.svelte';
	import LocalSection from './LocalSection.svelte';
	import OwnModuleSection from './OwnModuleSection.svelte';
	import ServerSection from './ServerSection.svelte';
	import SoloSection from './SoloSection.svelte';
	import SongMenuSection from './SongMenuSection.svelte';
	import YoutubeSection from './YoutubeSection.svelte';

	let {
		start = null,
		invite = null,
		onClose,
		onToast
	}: {
		/** The section to open; null: the list (desktop: the first section). */
		start?: SettingsSection | null;
		/** An invite code for signing up (from /account?invite=…). */
		invite?: string | null;
		onClose: () => void;
		onToast: (text: string) => void;
	} = $props();

	let host = $state<HostInfo | null>(null);
	// This server's accounts (only on a server's own pages).
	let info = $state<AuthServerInfo | null>(null);
	let account = $state<AccountInfo | null>(null);

	onMount(async () => {
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
	/** On the phone's own pages (its room list and rooms), which keep the phone's song cache. */
	const onThisPhone = $derived(inApp && host?.kind === 'phone' && ['127.0.0.1', 'localhost'].includes(location.hostname));
	const sections = $derived(
		(
			[
				['account', 'Account', 'person', true],
				['youtube', 'YouTube Music', 'library', true],
				['playback', 'Playback', 'headphones', true],
				['solo', 'Solo rooms', 'person', true],
				['gestures', 'Taps and swipes', 'tune', true],
				['menu', 'Song menu', 'more', true],
				['home', 'Home', 'home', true],
				['local', 'Local music', 'local', true],
				['look', 'Look', 'palette', true],
				['app', 'App', 'phone', !!nativeBridge?.appVersion],
				['server', 'Server admin', 'server', !!account?.isAdmin && !!info]
			] as [SettingsSection, string, IconName, boolean][]
		).filter(([, , , shown]) => shown)
	);
	// svelte-ignore state_referenced_locally
	let section = $state<SettingsSection>(start ?? 'account');
	/** Phones: a section (true) or the list of sections. */
	// svelte-ignore state_referenced_locally
	let showingSection = $state(start !== null);
	const title = $derived(sections.find(([id]) => id === section)?.[1] ?? 'Settings');

	/** Phones: the list was shown, so Back goes there from a section. */
	// svelte-ignore state_referenced_locally
	let cameFromList = $state(start === null);

	/** The phone's Back button: from a section to the list. False: close the settings. */
	export function back(): boolean {
		// Wide screens show the list next to the section.
		if (!showingSection || !cameFromList || matchMedia('(min-width: 768px)').matches) return false;
		showingSection = false;
		return true;
	}

	function open(id: SettingsSection) {
		section = id;
		showingSection = true;
	}

	// Playback (the app) and look.
	let soloBack = $state(saved.backLimit(true));
	let partyBack = $state(saved.backLimit(false));
	let headphones = $state(saved.headphonesAction);
	let pageSize = $state(saved.pageSize);
	function setPageSize(size: number) {
		pageSize = size;
		saved.pageSize = size;
	}
	let nextPlays = $state(saved.nextPlays);
	let radioClearsHistory = $state(saved.radioClearsHistory);
	let remotePlay = $state(saved.remotePlay);
	let skipSilence = $state(saved.skipSilence);
	const qualityOptions: [ImageQuality, string][] = [
		['high', 'High'],
		['low', 'Low']
	];
	const videoArtOptions: [VideoArt, string][] = [
		['crop', 'Crop'],
		['fit', 'Fit']
	];
	const backOptions: [BackLimit, string][] = [
		['home', 'Home page'],
		['rooms', 'Room list']
	];
	const headphoneOptions: [HeadphonesAction, string][] = [
		['pause', 'Pause'],
		['stop', 'Stop here'],
		['keep', 'Keep playing']
	];

	function onKey(event: KeyboardEvent) {
		if (event.key === 'Escape') onClose();
	}
	onMount(() => window.addEventListener('keydown', onKey));
	onDestroy(() => window.removeEventListener('keydown', onKey));
</script>

<div class="fixed inset-0 z-[60] flex bg-bg" role="dialog" aria-modal="true" aria-label="Settings">
	<!-- The sections -->
	<nav class="{showingSection ? 'hidden md:flex' : 'flex'} w-full shrink-0 flex-col gap-1 border-line p-4 md:w-64 md:border-r md:pt-6">
		<div class="mb-3 flex items-center gap-2">
			<button class="rounded-full p-1.5 hover:bg-raised" aria-label="Close settings" onclick={onClose}><Icon name="back" /></button>
			<h1 class="text-xl font-bold">Settings</h1>
		</div>
		{#each sections as [id, label, icon] (id)}
			<button
				class="flex items-center gap-3 rounded-lg px-3 py-2.5 text-left {section === id ? 'md:bg-raised' : ''} hover:bg-raised/60"
				onclick={() => open(id)}
			>
				<Icon name={icon} size={20} class="text-muted" />
				<span class="flex-1 font-medium">{label}</span>
				<Icon name="chevron" size={18} class="text-muted md:hidden" />
			</button>
		{/each}
	</nav>

	<!-- The section -->
	<div class="{showingSection ? 'flex' : 'hidden md:flex'} min-w-0 flex-1 flex-col overflow-y-auto">
		<div class="mx-auto flex w-full max-w-xl flex-col gap-5 p-4 md:p-8">
			<div class="flex items-center gap-2">
				<button class="rounded-full p-1.5 hover:bg-raised md:hidden" aria-label="Back to settings" onclick={() => ((showingSection = false), (cameFromList = true))}><Icon name="back" /></button>
				<h2 class="text-xl font-bold">{title}</h2>
				<button class="ml-auto hidden rounded-full p-1.5 text-muted hover:bg-raised hover:text-white md:block" aria-label="Close settings" onclick={onClose}>
					<Icon name="close" />
				</button>
			</div>

			{#if section === 'account'}
				<AccountSection {host} {info} {account} {invite} onAccount={(a) => (account = a)} {onToast} />
			{:else if section === 'youtube'}
				<YoutubeSection {onToast} />
			{:else if section === 'playback'}
				<Choice
					label="Next while paused"
					hint="Like YouTube Music, the next song plays. Or it loads and the room stays paused."
					options={[
						['play', 'Plays the next song'],
						['pause', 'Stays paused']
					] as ['play' | 'pause', string][]}
					value={nextPlays ? 'play' : 'pause'}
					onchange={(v) => {
						nextPlays = v === 'play';
						saved.nextPlays = nextPlays;
						window.dispatchEvent(new Event('ytmp:settings'));
					}}
				/>
				<Choice
					label="Start radio"
					hint="Start radio always replaces what's up next. It can also clear the songs that played before, above the current song, like YouTube Music. In a party room that needs permission to remove other people's songs."
					options={[
						['keep', 'Keep played songs'],
						['clear', 'Clear played songs']
					] as ['keep' | 'clear', string][]}
					value={radioClearsHistory ? 'clear' : 'keep'}
					onchange={(v) => {
						radioClearsHistory = v === 'clear';
						saved.radioClearsHistory = radioClearsHistory;
					}}
				/>
				<Choice
					label="Your other devices"
					hint="Logged in with the same account and in the same room, your other devices can switch Play here on and off on this one, from Play on. A browser plays only after a click on its page."
					options={[
						['on', 'Can switch it on'],
						['off', 'Only from here']
					] as ['on' | 'off', string][]}
					value={remotePlay ? 'on' : 'off'}
					onchange={(v) => {
						remotePlay = v === 'on';
						saved.remotePlay = remotePlay;
						window.dispatchEvent(new Event('ytmp:settings'));
					}}
				/>
				{#if equalizer.available}
					<EqualizerSection />
				{/if}
				{#if !inApp}
					<OwnModuleSection {onToast} />
				{:else}
				<Choice
					label="Skip silence in podcasts"
					hint="Skips the quiet parts of an episode, so it takes less time. Only when you're the only one listening: the room follows you."
					options={[
						['off', 'Off'],
						['on', 'On']
					] as ['off' | 'on', string][]}
					value={skipSilence ? 'on' : 'off'}
					onchange={(v) => {
						skipSilence = v === 'on';
						saved.skipSilence = skipSilence;
						window.dispatchEvent(new Event('ytmp:settings'));
					}}
				/>
				<Choice
					label="Headphones disconnected"
					hint="Pause only pauses the room when you're the only one listening; otherwise it stops playing here."
					options={headphoneOptions}
					value={headphones}
					onchange={(v) => {
						headphones = v;
						saved.headphonesAction = v;
						window.dispatchEvent(new Event('ytmp:settings'));
					}}
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
				<p class="text-xs text-muted">The sync adjustment for Bluetooth is under <em>Play on</em> in a room.</p>
				{#if !nativeBridge}
					<p class="text-xs text-muted">Keyboard: <kbd>Space</kbd> or <kbd>K</kbd> play/pause · <kbd>N</kbd> next · <kbd>P</kbd> previous · <kbd>←</kbd> <kbd>→</kbd> 10 seconds back or ahead · <kbd>↑</kbd> <kbd>↓</kbd> volume · <kbd>/</kbd> search.</p>
				{/if}
				{#if onThisPhone}
					<DownloadsSection {onToast} />
					<CacheSection load={getSongCache} clear={clearSongCache} change={setSongCache} {onToast} />
				{:else}
					<p class="text-xs text-muted">The song cache's settings are on this phone's own pages (the room list, rooms on this phone).</p>
				{/if}
				{/if}
			{:else if section === 'solo'}
				<SoloSection />
			{:else if section === 'gestures'}
				<GesturesSection />
			{:else if section === 'menu'}
				<SongMenuSection />
			{:else if section === 'local'}
				<LocalSection {onToast} />
			{:else if section === 'home'}
				<HomeSection />
			{:else if section === 'look'}
				<Choice
					label="Image quality"
					hint="Low loads smaller pictures (album art, artists, playlists): less data, a bit less sharp."
					options={qualityOptions}
					value={images.quality}
					onchange={setImageQuality}
				/>
				<Choice
					label="Video pictures"
					hint="Songs from a music video only have a wide picture. Crop fills the square (like YouTube Music); Fit shows all of it, with bars."
					options={videoArtOptions}
					value={images.videoArt}
					onchange={setVideoArt}
				/>
				<Choice
					label="Songs per page"
					hint="Long lists (Liked music, your playlists, songs in Local) show this many songs at first, and this many more when you get to the end."
					options={[
						['100', '100'],
						['300', '300'],
						['1000', '1000']
					]}
					value={String(pageSize)}
					onchange={(v) => setPageSize(Number(v))}
				>
					<NumberField value={pageSize} min={PAGE_SIZES.min} max={PAGE_SIZES.max} unit="songs" label="Songs per page" onchange={setPageSize} />
				</Choice>
				<Choice
					label="Dislike button in the player bar"
					hint="Thumbs down next to thumbs up at the bottom (computers). Dimmed: still there, but darker, so you don't hit it by accident."
					options={[
						['normal', 'Normal'],
						['dim', 'Dimmed'],
						['hidden', 'Hidden']
					] as [DislikeButton, string][]}
					value={images.dislikeButton}
					onchange={setDislikeButton}
				/>
				<Choice
					label="Swiping up on the player's tabs"
					hint="On a phone, swiping up on Up next, Lyrics or Related at the bottom of the player opens that tab, or always Up next."
					options={[
						['tab', 'That tab'],
						['upnext', 'Always Up next']
					] as [SwipeOpens, string][]}
					value={images.swipeOpens}
					onchange={setSwipeOpens}
				/>
				<Choice
					label="Order of the player's tabs"
					hint="Up next first, like YouTube Music, or Related first (they swap places)."
					options={[
						['upnext', 'Up next first'],
						['related', 'Related first']
					] as [TabOrder, string][]}
					value={images.tabOrder}
					onchange={setTabOrder}
				/>
				<p class="text-xs text-muted">Saved on this {inApp ? 'phone' : 'device'}.</p>
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
			{:else if section === 'server' && info && account}
				<ServerSection {info} me={account} {onToast} />
			{/if}
		</div>
	</div>
</div>

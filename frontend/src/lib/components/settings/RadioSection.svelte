<script lang="ts">
	// Settings → Radio (docs/features/history-and-recommendations.md#radio--mix): how every radio
	// is made unless you hold Start radio to choose for one; what radios leave out; and on the
	// phone, learning YouTube Music's radios of your music for the song graph.
	import { onMount } from 'svelte';
	import { getGraphCopy, getRadioCrawl, refreshGraphCopy, setGraphCopy, setRadioCrawl, type GraphCopyStatus } from '../../api';
	import { listening } from '../../listening.svelte';
	import type { RadioOptions } from '../../protocol.gen';
	import { blockArtist, blockSong, DEFAULT_RADIO, RADIO_FROM, radioDefaults, setRadioDefaults } from '../../radio.svelte';
	import { settings } from '../../settings.svelte';
	import { youtube } from '../../youtube.svelte';
	import RadioChoices from '../RadioChoices.svelte';
	import Choice from './Choice.svelte';
	import CrawlSection from './CrawlSection.svelte';
	import Toggle from './Toggle.svelte';

	let { onToast, onThisPhone = false, onGraph }: { onToast: (text: string) => void; onThisPhone?: boolean; onGraph?: () => void } = $props();

	const r = $derived(radioDefaults());
	// The choices are your saved defaults themselves: a change is saved, and defaults that
	// arrive from your account later show as they are (nothing old is written over them).
	const picked = {
		get value() {
			return radioDefaults();
		},
		set value(p: RadioOptions) {
			setRadioDefaults({ from: p.from, discover: p.discover, withAccount: p.withAccount, myTaste: p.myTaste });
		}
	};

	let copy = $state<GraphCopyStatus | null>(null);
	let refreshing = $state(false);
	onMount(() => {
		void listening.load();
		if (onThisPhone) getGraphCopy().then((c) => (copy = c)).catch(() => {});
	});

	async function copyOn(on: boolean) {
		try {
			copy = await setGraphCopy(on);
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't change it");
		}
	}

	async function refreshCopy() {
		refreshing = true;
		try {
			copy = await refreshGraphCopy();
			if (!copy.madeAt) onToast("Couldn't reach your YTMP server");
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't refresh it");
		} finally {
			refreshing = false;
		}
	}

	const ago = (ms: number) => {
		const h = Math.round((Date.now() - ms) / 3_600_000);
		return h < 1 ? 'less than an hour ago' : h < 48 ? `${h} h ago` : `${Math.round(h / 24)} days ago`;
	};

	const card = 'flex flex-col gap-3 rounded-xl bg-raised/50 p-4';
</script>

<section class={card}>
	<h3 class="font-bold">Every radio</h3>
	<p class="text-xs text-muted">Start radio, Autoplay this and autoplay. Hold Start radio (or right-click it) to choose differently for one radio.</p>
	<RadioChoices bind:value={() => picked.value, (v) => (picked.value = v)} hasAccount={!!youtube.account} party />
</section>

<section class={card}>
	<h3 class="font-bold">Chips above Up next</h3>
	<p class="text-xs text-muted">Change the radio that's playing. Hide the ones you don't use; the one playing always shows.</p>
	{#each RADIO_FROM as f (f.value)}
		<Toggle
			label={f.label}
			hint={f.hint}
			checked={!settings.radioChipsHidden.value.includes(f.value)}
			onchange={(on) =>
				settings.radioChipsHidden.set(on ? settings.radioChipsHidden.value.filter((v) => v !== f.value) : [...settings.radioChipsHidden.value, f.value])}
		/>
	{/each}
</section>

<section class={card}>
	<h3 class="font-bold">Mix</h3>
	<Choice
		label="Songs from your listening"
		hint="In Mix: one song from the song graph after every few of YouTube Music's."
		value={r.mixEvery}
		onchange={(v) => setRadioDefaults({ mixEvery: v })}
		options={[
			[1, 'Every other'],
			[2, '1 in 3'],
			[3, '1 in 4'],
			[5, '1 in 6']
		]}
	/>
	<Choice
		label="Without internet"
		hint="When YouTube Music doesn't answer, a radio from YouTube Music or Mix becomes:"
		value={r.offline}
		onchange={(v) => setRadioDefaults({ offline: v })}
		options={[
			['graph', 'Song graph'],
			['device', 'Only what’s on this device']
		]}
	/>
</section>

<section class={card}>
	<h3 class="font-bold">Leave out</h3>
	<Choice
		label="Songs per artist"
		hint="At most this many songs by one artist in a radio."
		value={r.perArtist}
		onchange={(v) => setRadioDefaults({ perArtist: v })}
		options={[
			[0, 'Any'],
			[2, '2'],
			[3, '3'],
			[5, '5']
		]}
	/>
	<Choice
		label="Songs you played lately"
		hint="Besides what the room just played. Needs your YTMP history."
		value={r.recentHours}
		onchange={(v) => setRadioDefaults({ recentHours: v })}
		options={[
			[0, 'Keep them'],
			[3, '3 h'],
			[24, '1 day'],
			[72, '3 days']
		]}
	/>
	<Toggle
		label="Songs you skip a lot"
		hint="The ones you skip more often than you hear. Needs your YTMP history."
		checked={r.leaveOutSkipped}
		onchange={(on) => setRadioDefaults({ leaveOutSkipped: on })}
	/>
	{#if r.blockedArtists.length || r.blockedSongs.length}
		<div class="flex flex-col gap-1">
			<p class="text-sm">Never in radios</p>
			<p class="text-xs text-muted">From holding Dislike. Tap one to allow it again.</p>
			<div class="mt-1 flex flex-wrap gap-2">
				{#each r.blockedArtists as artist (artist)}
					<button class="rounded-lg bg-raised px-3 py-1.5 text-sm hover:bg-line" onclick={() => blockArtist(artist, false)}>{artist} ✕</button>
				{/each}
				{#if r.blockedSongs.length}
					<button class="rounded-lg bg-raised px-3 py-1.5 text-sm hover:bg-line" onclick={() => r.blockedSongs.forEach((id) => blockSong(id, false))}>
						{r.blockedSongs.length} {r.blockedSongs.length === 1 ? 'song' : 'songs'} ✕
					</button>
				{/if}
			</div>
		</div>
	{/if}
</section>

<section class={card}>
	<h3 class="font-bold">Song graph</h3>
	<p class="text-xs text-muted">
		What YTMP learned: which songs you play after each other (also from a Google Takeout import), what everyone on your server plays, and YouTube Music's radios.
		The graph shows it, with sliders for how much each part counts in your radios. What of your listening counts at all is under Account.
	</p>
	{#if listening.available && onGraph}
		<button class="self-start rounded-full bg-white px-4 py-2 text-sm font-medium text-black" onclick={onGraph}>Open the song graph</button>
	{:else}
		<p class="text-xs text-muted">Log in with your YTMP account to see it.</p>
	{/if}
	<button class="self-start text-sm text-muted underline" onclick={() => setRadioDefaults({ ...DEFAULT_RADIO, blockedArtists: r.blockedArtists, blockedSongs: r.blockedSongs })}>
		Radio choices back to how they were
	</button>
</section>

{#if onThisPhone && copy}
	<section class={card}>
		<h3 class="font-bold">The song graph on this phone</h3>
		<p class="text-xs text-muted">
			A copy of your song graph, so radios still bring the songs you play together when your YTMP server can't be reached. Your music files and saved songs are found in it
			by title, artist and length. Made again once a day.
		</p>
		<Toggle label="Keep a copy on this phone" hint="Off: it's deleted, and radios without your server use only your music files." checked={copy.enabled} onchange={copyOn} />
		{#if copy.enabled}
			<p class="text-sm">
				{#if copy.madeAt}
					{copy.songs.toLocaleString()} songs, {copy.links.toLocaleString()} links · {(copy.bytes / 1e6).toFixed(1)} MB · made {ago(copy.madeAt)}
				{:else}
					Not made yet: it needs your YTMP server.
				{/if}
			</p>
			<button class="self-start rounded-full bg-raised px-4 py-2 text-sm hover:bg-line disabled:opacity-50" disabled={refreshing} onclick={refreshCopy}>
				{refreshing ? 'Refreshing…' : 'Refresh now'}
			</button>
		{/if}
	</section>
{/if}

{#if onThisPhone}
	<CrawlSection
		load={getRadioCrawl}
		save={setRadioCrawl}
		{onToast}
		hint="Slowly fetches YouTube Music's radio of each song on this phone that is a YouTube Music song, of the saved songs, and of the songs below, without your account. Your server keeps them in the song graph, so radios from your music and On this device get better. Lookups follow the Wi-Fi and charging rules of Music on this phone."
		historyHint="Your listening history (in YTMP and imported from Takeout), most played first."
	/>
{/if}

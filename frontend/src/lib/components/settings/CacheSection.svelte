<script lang="ts">
	// The song cache in the settings: how full it is and Clear cache. On a phone also its size
	// and where it's kept (Settings → Playback); a server's admin sees it under Server admin →
	// Settings, with its size next to it.
	import { onDestroy } from 'svelte';
	import type { SongCacheSettings, SongCacheStatus } from '../../protocol.gen';
	import { showToComputer } from '../../api';
	import NumberField from '../NumberField.svelte';
	import Choice from './Choice.svelte';
	import LocalSongsSection from './LocalSongsSection.svelte';
	import TakeoutSection from './TakeoutSection.svelte';
	import PlaylistsOfflineSection from './PlaylistsOfflineSection.svelte';

	let {
		load,
		clear,
		change,
		card = false,
		device = 'phone',
		onToast
	}: {
		load: () => Promise<SongCacheStatus>;
		clear: () => Promise<SongCacheStatus>;
		change?: (settings: SongCacheSettings) => Promise<SongCacheStatus>;
		/** In a card of its own (Settings → Server admin): a heading like the other cards'. */
		card?: boolean;
		/** Whose cache: the phone's, or the desktop app's computer. */
		device?: 'phone' | 'computer';
		onToast: (text: string) => void;
	} = $props();
	const here = $derived(device === 'computer' ? 'this computer' : 'this phone');

	let status = $state<SongCacheStatus | null>(null);
	let busy = $state(false);
	let timer: ReturnType<typeof setTimeout> | undefined;

	const GB = 1024 ** 3;
	const sizes: [string, string][] = [
		['0', 'Off'],
		[String(GB / 2), '500 MB'],
		[String(GB), '1 GB'],
		[String(2 * GB), '2 GB'],
		[String(5 * GB), '5 GB'],
		[String(10 * GB), '10 GB']
	];

	async function refresh() {
		try {
			status = await load();
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't load the song cache");
			return;
		}
		schedule();
	}

	/** While it moves to another place, or songs come in, look again in a moment. */
	function schedule() {
		clearTimeout(timer);
		if (!status) return;
		if (status.moving) timer = setTimeout(refresh, 2000);
		else if (status.checking || filling(status)) timer = setTimeout(refresh, 10000);
	}
	refresh();
	onDestroy(() => clearTimeout(timer));

	const setSize = (maxBytes: number) => attempt(() => patch({ maxBytes }));

	async function attempt(action: () => Promise<SongCacheStatus>, done?: string) {
		busy = true;
		try {
			status = await action();
			if (done) onToast(done);
			schedule();
		} catch (e) {
			onToast(e instanceof Error ? e.message : 'Something went wrong');
		} finally {
			busy = false;
		}
	}

	/** The settings as they are, with [changes]. */
	function patch(changes: Partial<SongCacheSettings>): Promise<SongCacheStatus> {
		return change!({
			maxBytes: status!.maxBytes,
			place: status?.place ?? null,
			localSongs: null,
			localParallel: null,
			fetchWifiOnly: null,
			downloadsWifiOnly: null,
			localWifiOnly: null,
			localCharging: null,
			keepLiked: null,
			fromHistory: null,
			fromTakeout: null,
			takeoutDays: null,
			takeoutMinPlays: null,
			takeoutRules: null,
			mostPlayedPlaylists: null,
			keepPlays: null,
			keepDays: null,
			...changes
		});
	}

	/** 1536 MB -> "1.5 GB". */
	function size(bytes: number): string {
		if (bytes >= GB) return `${(bytes / GB).toFixed(bytes >= 10 * GB ? 0 : 1).replace(/\.0$/, '')} GB`;
		if (bytes < 1024 ** 2) return `${Math.ceil(bytes / 1024)} KB`;
		return `${Math.round(bytes / 1024 ** 2)} MB`;
	}

	const keepPlays: [string, string][] = [
		['1', 'Every song'],
		['2', 'From the 2nd play'],
		['3', 'From the 3rd play'],
		['5', 'From the 5th play']
	];
	const keepDays: [string, string][] = [
		['7', 'Last week'],
		['30', 'Last month'],
		['90', 'Last 3 months']
	];

	const likedSizes: [string, string][] = [
		['0', 'Off'],
		['50', '50'],
		['100', '100'],
		['250', '250'],
		['500', '500']
	];

	/** Liked songs or songs from Takeout are still coming in. */
	const filling = (s: SongCacheStatus) =>
		s.fromYoutubeBusy || (((!!s.keepLiked && s.likedCached < s.likedOf) || (s.fromTakeout && s.takeoutCached < s.takeoutOf)) && !s.fromYoutubeWaiting);

	/** Why songs from YouTube Music aren't coming in now, in plain words. */
	function fromYoutubeNote(s: SongCacheStatus): string | null {
		switch (s.fromYoutubeWaiting) {
			case 'Wi-Fi':
				return 'Waiting for Wi-Fi.';
			case 'full':
				return 'The cache is full: a bigger size keeps more.';
			case 'signed out':
				return 'Sign in to YouTube Music first.';
			case 'error':
				return "Couldn't reach YouTube Music; trying again later.";
			default:
				return null;
		}
	}

	const full = $derived(status && status.maxBytes > 0 ? Math.min(100, (status.bytes / status.maxBytes) * 100) : 0);
	const placeName = (id: string) => status?.places.find((p) => p.id === id)?.name ?? id;
</script>

<section class="flex flex-col gap-3">
	<div>
		<p class={card ? 'font-bold' : 'text-sm font-medium'}>Song cache</p>
		<p class="text-xs text-muted">
			{#if status?.settable}
				{#if device === 'computer'}
					Songs played on this computer are kept on it, so they start at once next time without looking them up on YouTube. Podcast episodes and anything over an hour aren't kept.
				{:else}
					Songs you play are kept on this phone, so they start at once and play without internet (in Local → Cached on this phone). Podcast episodes aren't kept.
				{/if}
			{:else}
				Songs played on this server are kept, so they start at once without asking YouTube. Podcast episodes aren't kept.
			{/if}
		</p>
	</div>
	{#if status}
		<div class="flex flex-col gap-1.5">
			<div class="h-1.5 overflow-hidden rounded-full bg-raised">
				<div class="h-full rounded-full bg-white" style="width: {full}%"></div>
			</div>
			<p class="text-xs text-muted">
				{#if status.moving}
					Moving to {placeName(status.place ?? '')}…
				{:else if status.maxBytes === 0}
					Off{status.songs ? ` (${status.songs} songs still in it)` : ''}
				{:else}
					{status.songs} {status.songs === 1 ? 'song' : 'songs'}, {size(status.bytes)} of {size(status.maxBytes)}
				{/if}
				{#if status.downloads}
					· {status.downloads} downloaded {status.downloads === 1 ? 'song' : 'songs'} too (they don't count)
				{/if}
				{#if status.local}
					· {status.local} of this phone's songs too (they don't count)
				{/if}
			</p>
		</div>
		{#if status.settable && change}
			<Choice
				label="Size"
				hint="When it's full, the songs you haven't played for the longest time go first (songs you play often stay longer)."
				options={sizes}
				value={String(status.maxBytes)}
				onchange={(v) => setSize(Number(v))}
			>
				<NumberField value={status.maxBytes / GB} min={0} max={1000} step={0.1} unit="GB" label="Size in GB (0: off)" onchange={(v) => setSize(Math.round(v * GB))} />
			</Choice>
			{#if status.keepRule}
				<Choice
					label="Songs you play are kept"
					hint={`A song you play streams as usual; it's kept on ${here} once you played it this often. The next song is fetched ahead so it starts at once, but only kept by the same rule.`}
					options={keepPlays}
					value={String(status.keepPlays)}
					onchange={(v) => attempt(() => patch({ keepPlays: Number(v) }))}
				>
					<NumberField value={status.keepPlays} min={1} max={10} step={1} unit="plays" label="Kept from this play" onchange={(v) => attempt(() => patch({ keepPlays: Math.round(v) }))} />
				</Choice>
				{#if status.keepPlays > 1}
					<Choice
						label="Plays counted in the"
						options={keepDays}
						value={String(status.keepDays)}
						onchange={(v) => attempt(() => patch({ keepDays: Number(v) }))}
					>
						<NumberField value={status.keepDays} min={1} max={90} step={1} unit="days" label="Days back" onchange={(v) => attempt(() => patch({ keepDays: Math.round(v) }))} />
					</Choice>
				{/if}
			{/if}
			{#if status.fetchWifiOnly != null}
				<Choice
					label="Keep songs in the background"
					hint="Songs you play a 2nd time are downloaded to keep. That's data nobody asked for, so by default only on Wi-Fi."
					options={[['1', 'Only on Wi-Fi'], ['0', 'Also on mobile data']]}
					value={status.fetchWifiOnly === false ? '0' : '1'}
					onchange={(v) => attempt(() => patch({ fetchWifiOnly: v === '1' }))}
				/>
			{/if}
			{#if status.downloadsWifiOnly != null}
				<Choice
					label="Downloads"
					hint="The Download in a song's menu (to the Music folder)."
					options={[['0', 'Right away'], ['1', 'Wait for Wi-Fi']]}
					value={status.downloadsWifiOnly ? '1' : '0'}
					onchange={(v) => attempt(() => patch({ downloadsWifiOnly: v === '1' }))}
				/>
			{/if}
			{#if status.keepLiked != null}
				<Choice
					label="Keep your recent likes"
					hint={`The songs you liked last on YouTube Music are downloaded to keep, one at a time, while there's room. They're cached like any other song.${status.keepLiked && status.likedOf ? ` ${status.likedCached} of ${status.likedOf} are kept.` : ''}`}
					options={likedSizes}
					value={String(status.keepLiked)}
					onchange={(v) => attempt(() => patch({ keepLiked: Number(v) }))}
				>
					<NumberField value={status.keepLiked} min={0} max={5000} step={1} unit="songs" label="Number of liked songs (0: off)" onchange={(v) => attempt(() => patch({ keepLiked: Math.round(v) }))} />
				</Choice>
				<Choice
					label="Songs from your YouTube Music history"
					hint="The songs in your YouTube Music history (also from other devices) are downloaded to keep. YouTube Music only shows about your last 200 songs, so that's all of them, also songs played once. To keep only what you play most, use Google Takeout below."
					options={[['0', 'Off'], ['1', 'On']]}
					value={status.fromHistory ? '1' : '0'}
					onchange={(v) => attempt(() => patch({ fromHistory: v === '1' }))}
				/>
				<TakeoutSection {status} {busy} {patch} {attempt} {size} {here} />
				{#if (status.keepLiked || status.fromHistory) && !status.fromTakeout && fromYoutubeNote(status)}
					<p class="-mt-1 text-xs text-muted">{fromYoutubeNote(status)}</p>
				{/if}
			{/if}
			{#if status.mostPlayedPlaylists != null}
				<PlaylistsOfflineSection {status} {busy} {patch} {attempt} {onToast} {device} />
			{/if}
			{#if status.localSongs != null}
				<LocalSongsSection {status} {busy} {patch} {attempt} {onToast} />
			{/if}
			{#if status.mostPlayedPlaylists != null && device === 'phone'}
				<!-- A phone: its songs to the owner's computer (the desktop app), also without the server. -->
				<div class="flex items-center gap-3">
					<div class="min-w-0 flex-1">
						<p class="text-sm font-medium">Send songs to my computer</p>
						<p class="text-xs text-muted">
							Shows this phone on the Wi-Fi for 10 minutes, so your computer copies its songs (with YTMP open there, logged in with the same account). With the app open, your computer can also ask
							for them itself: Copy from my phone.
						</p>
					</div>
					<button
						class="shrink-0 rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-40"
						disabled={busy}
						onclick={() => attempt(async () => (await showToComputer(), status!), 'Your computer can find this phone now')}>Send</button
					>
				</div>
			{/if}
			{#if status.places.length > 1}
				<Choice
					label="Kept on"
					hint={status.places.map((p) => (p.freeBytes != null ? `${p.name}: ${size(p.freeBytes)} free` : p.name)).join(' · ')}
					options={status.places.map((p) => [p.id, p.name] as [string, string])}
					value={status.place ?? status.places[0].id}
					onchange={(v) => attempt(() => patch({ place: v }), 'Moving the cached songs…')}
				/>
			{/if}
		{/if}
		<button
			class="self-start rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-40"
			disabled={busy || status.moving || !status.songs}
			onclick={() => attempt(clear, 'Cache cleared')}>Clear cache</button
		>
	{/if}
</section>

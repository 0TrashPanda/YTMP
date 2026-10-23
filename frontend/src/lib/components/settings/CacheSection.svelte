<script lang="ts">
	// The song cache in the settings: how full it is and Clear cache. On a phone also its size
	// and where it's kept (Settings → Playback); a server's admin sees it under Server admin →
	// Settings, with its size next to it.
	import { onDestroy } from 'svelte';
	import type { SongCacheSettings, SongCacheStatus } from '../../protocol.gen';
	import NumberField from '../NumberField.svelte';
	import Choice from './Choice.svelte';
	import LocalSongsSection from './LocalSongsSection.svelte';

	let {
		load,
		clear,
		change,
		card = false,
		onToast
	}: {
		load: () => Promise<SongCacheStatus>;
		clear: () => Promise<SongCacheStatus>;
		change?: (settings: SongCacheSettings) => Promise<SongCacheStatus>;
		/** In a card of its own (Settings → Server admin): a heading like the other cards'. */
		card?: boolean;
		onToast: (text: string) => void;
	} = $props();

	let status = $state<SongCacheStatus | null>(null);
	let busy = $state(false);
	let timer: ReturnType<typeof setTimeout> | undefined;

	const GB = 1024 ** 3;
	const sizes: [string, string][] = [
		['0', 'Off'],
		[String(GB / 2), '500 MB'],
		[String(GB), '1 GB'],
		[String(2 * GB), '2 GB'],
		[String(5 * GB), '5 GB']
	];

	async function refresh() {
		try {
			status = await load();
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't load the song cache");
			return;
		}
		// While it moves to another place, look again in a moment.
		clearTimeout(timer);
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
			if (status.moving) timer = setTimeout(refresh, 2000);
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
			...changes
		});
	}

	/** 1536 MB -> "1.5 GB". */
	function size(bytes: number): string {
		if (bytes >= GB) return `${(bytes / GB).toFixed(bytes >= 10 * GB ? 0 : 1).replace(/\.0$/, '')} GB`;
		if (bytes < 1024 ** 2) return `${Math.ceil(bytes / 1024)} KB`;
		return `${Math.round(bytes / 1024 ** 2)} MB`;
	}

	const likedSizes: [string, string][] = [
		['0', 'Off'],
		['50', '50'],
		['100', '100'],
		['250', '250'],
		['500', '500']
	];

	/** Liked songs are still coming in. */
	const filling = (s: SongCacheStatus) => !!s.keepLiked && s.likedCached < s.likedOf && !s.fromYoutubeWaiting;

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
				Songs you play are kept on this phone, so they start at once and play without internet (in Local → Cached on this phone). Podcast episodes aren't kept.
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
			<Choice
				label="Keep songs in the background"
				hint="Songs you play a 2nd time are downloaded to keep. That's data nobody asked for, so by default only on Wi-Fi."
				options={[['1', 'Only on Wi-Fi'], ['0', 'Also on mobile data']]}
				value={status.fetchWifiOnly === false ? '0' : '1'}
				onchange={(v) => attempt(() => patch({ fetchWifiOnly: v === '1' }))}
			/>
			<Choice
				label="Downloads"
				hint="The Download in a song's menu (to the Music folder)."
				options={[['0', 'Right away'], ['1', 'Wait for Wi-Fi']]}
				value={status.downloadsWifiOnly ? '1' : '0'}
				onchange={(v) => attempt(() => patch({ downloadsWifiOnly: v === '1' }))}
			/>
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
					hint="Songs YouTube Music says you played more than once (also on other devices) are downloaded to keep. It looks at your history a few times a day."
					options={[['0', 'Off'], ['1', 'On']]}
					value={status.fromHistory ? '1' : '0'}
					onchange={(v) => attempt(() => patch({ fromHistory: v === '1' }))}
				/>
				{#if (status.keepLiked || status.fromHistory) && fromYoutubeNote(status)}
					<p class="-mt-1 text-xs text-muted">{fromYoutubeNote(status)}</p>
				{/if}
			{/if}
			{#if status.localSongs != null}
				<LocalSongsSection {status} {busy} {patch} {attempt} {onToast} />
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

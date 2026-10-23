<script lang="ts">
	// The song cache in the settings: how full it is and Clear cache. On a phone also its size
	// and where it's kept (Settings → Playback); a server's admin sees it under Server (its size
	// is in ytmp.toml).
	import { onDestroy } from 'svelte';
	import type { SongCacheSettings, SongCacheStatus } from '../../protocol.gen';
	import { local } from '../../local.svelte';
	import Choice from './Choice.svelte';
	import Switch from './Switch.svelte';

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
		/** In a card of its own (Settings → Server): a heading like the other cards'. */
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
		else if (status.checking) timer = setTimeout(refresh, 10000);
	}
	refresh();
	onDestroy(() => clearTimeout(timer));

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

	/** 1536 MB -> "1.5 GB". */
	function size(bytes: number): string {
		if (bytes >= GB) return `${(bytes / GB).toFixed(bytes >= 10 * GB ? 0 : 1).replace(/\.0$/, '')} GB`;
		if (bytes < 1024 ** 2) return `${Math.ceil(bytes / 1024)} KB`;
		return `${Math.round(bytes / 1024 ** 2)} MB`;
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
				Songs played on this server are kept, so they start at once without asking YouTube. Podcast episodes aren't kept. Its size and folder are under [cache] in ytmp.toml.
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
				onchange={(v) => attempt(() => change({ maxBytes: Number(v), place: status?.place ?? null, localSongs: null, localParallel: null }))}
			/>
			{#if status.localSongs != null}
				<div class="flex items-center gap-4">
					<div class="min-w-0 flex-1">
						<p class="text-sm font-medium">Songs from this phone</p>
						<p class="text-xs text-muted">
							Lists the music stored on this phone that is also on YouTube Music (same title, artist and length) under Cached on this phone, and plays it from the file, also offline. Each song is looked up once, slowly.
							{#if !local.phone}Needs <em>Music on this phone</em> in Settings → Local music.{/if}
						</p>
					</div>
					<Switch
						checked={status.localSongs}
						label="Songs from this phone"
						disabled={busy || !local.phone}
						onchange={(on) => attempt(() => change({ maxBytes: status!.maxBytes, place: status?.place ?? null, localSongs: on, localParallel: null }))}
					/>
				</div>
				{#if status.localSongs}
					<Choice
						label="Lookups at once"
						hint="More is faster, but YouTube may block this network if you ask a lot (then it waits 10 minutes). 1 is about 15 songs a minute."
						options={[['1', '1'], ['2', '2'], ['4', '4'], ['8', '8']]}
						value={String(status.localParallel)}
						onchange={(v) => attempt(() => change({ maxBytes: status!.maxBytes, place: status?.place ?? null, localSongs: null, localParallel: Number(v) }))}
					/>
				{/if}
				{#if status.localSongs && status.checkingOf}
					<div class="flex flex-col gap-1.5">
						<div class="h-1.5 overflow-hidden rounded-full bg-raised">
							<div class="h-full rounded-full bg-white" style="width: {((status.checkingOf - status.checking) / status.checkingOf) * 100}%"></div>
						</div>
						<p class="text-xs text-muted">
							{status.checkingOf - status.checking} of {status.checkingOf} phone songs looked up · {status.local} found on YouTube Music{status.checking ? '' : ' · done'}
						</p>
					</div>
				{/if}
			{/if}
			{#if status.places.length > 1}
				<Choice
					label="Kept on"
					hint={status.places.map((p) => (p.freeBytes != null ? `${p.name}: ${size(p.freeBytes)} free` : p.name)).join(' · ')}
					options={status.places.map((p) => [p.id, p.name] as [string, string])}
					value={status.place ?? status.places[0].id}
					onchange={(v) => attempt(() => change({ maxBytes: status!.maxBytes, place: v, localSongs: null, localParallel: null }), 'Moving the cached songs…')}
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

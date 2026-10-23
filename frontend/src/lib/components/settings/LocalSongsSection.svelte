<script lang="ts">
	// Settings → Playback → Song cache → Songs from this phone: which of the phone's own music is
	// a YouTube Music song, how far that is, and what to do with the ones that aren't.
	import { forgetLocalSongs, getUnmatchedSongs, linkLocalSong, recheckLocalSongs } from '../../api';
	import { local } from '../../local.svelte';
	import type { SongCacheSettings, SongCacheStatus, UnmatchedSong } from '../../protocol.gen';
	import Choice from './Choice.svelte';
	import Switch from './Switch.svelte';

	let {
		status,
		busy,
		patch,
		attempt,
		onToast
	}: {
		status: SongCacheStatus;
		busy: boolean;
		patch: (changes: Partial<SongCacheSettings>) => Promise<SongCacheStatus>;
		attempt: (action: () => Promise<SongCacheStatus>, done?: string) => Promise<void>;
		onToast: (text: string) => void;
	} = $props();

	let open = $state(false);
	let unmatched = $state<UnmatchedSong[] | null>(null);
	let links = $state<Record<string, string>>({});

	const done = $derived(status.checkingOf - status.checking);

	async function loadUnmatched() {
		try {
			unmatched = (await getUnmatchedSongs()).songs;
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't load the list");
		}
	}

	async function toggleList() {
		open = !open;
		if (open) await loadUnmatched();
	}

	async function link(song: UnmatchedSong, text: string) {
		await attempt(() => linkLocalSong({ key: song.key, link: text }), text ? 'Linked' : 'Skipped');
		await loadUnmatched();
	}
</script>

<div class="flex flex-col gap-3">
	<div class="flex items-center gap-4">
		<div class="min-w-0 flex-1">
			<p class="text-sm font-medium">Songs from this phone</p>
			<p class="text-xs text-muted">
				Lists the music stored on this phone that is also on YouTube Music under Cached on this phone, and plays it from the file, also offline. A YouTube ID in the file's name or tags is used; else the title, artist and length must agree. Each song is looked up once, slowly.
				{#if !local.phone}Needs <em>Music on this phone</em> in Settings → Local music.{/if}
			</p>
		</div>
		<Switch checked={status.localSongs ?? false} label="Songs from this phone" disabled={busy || !local.phone} onchange={(on) => attempt(() => patch({ localSongs: on }))} />
	</div>
	{#if status.localSongs}
		<Choice
			label="Lookups at once"
			hint="More is faster, but YouTube may block this network if you ask a lot (then it waits 10 minutes). 1 is about 15 songs a minute."
			options={[['1', '1'], ['2', '2'], ['4', '4'], ['8', '8']]}
			value={String(status.localParallel)}
			onchange={(v) => attempt(() => patch({ localParallel: Number(v) }))}
		/>
		<Choice
			label="Look up"
			hint="Looking up is small in data, but it keeps the phone busy."
			options={[['any', 'Any time'], ['wifi', 'Only on Wi-Fi'], ['charge', 'Only while charging'], ['both', 'Wi-Fi and charging']]}
			value={status.localWifiOnly && status.localCharging ? 'both' : status.localWifiOnly ? 'wifi' : status.localCharging ? 'charge' : 'any'}
			onchange={(v) => attempt(() => patch({ localWifiOnly: v === 'wifi' || v === 'both', localCharging: v === 'charge' || v === 'both' }))}
		/>
		{#if status.checkingOf}
			<div class="flex flex-col gap-1.5">
				<div class="h-1.5 overflow-hidden rounded-full bg-raised">
					<div class="h-full rounded-full bg-white" style="width: {(done / status.checkingOf) * 100}%"></div>
				</div>
				<p class="text-xs text-muted">
					{done} of {status.checkingOf} phone songs looked up · {status.local} found on YouTube Music{status.checking ? '' : ' · done'}
					{#if status.localWaiting}· waiting for {status.localWaiting}{/if}
				</p>
			</div>
		{/if}
		<div class="flex flex-wrap gap-2">
			<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-40" disabled={busy} onclick={() => attempt(recheckLocalSongs, 'Looking again at the songs without a match')}>Check again</button>
			<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-40" disabled={busy} onclick={() => attempt(forgetLocalSongs, 'Forgot what was found')}>Forget matches</button>
			<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={toggleList}>{open ? 'Hide' : 'Not found'}</button>
		</div>
		{#if open}
			{#if unmatched == null}
				<p class="text-xs text-muted">Loading…</p>
			{:else if unmatched.length === 0}
				<p class="text-xs text-muted">Every song looked up has a match.</p>
			{:else}
				<p class="text-xs text-muted">{unmatched.length} without a match. Paste a YouTube Music link (or the video ID) to say which song it is, or skip it.</p>
				<ul class="flex max-h-96 flex-col gap-2 overflow-y-auto">
					{#each unmatched as song (song.key)}
						<li class="flex flex-col gap-1.5 rounded-lg bg-raised p-3">
							<p class="truncate text-sm">{song.title}{song.artist !== 'Unknown artist' ? ` · ${song.artist}` : ''}</p>
							<p class="truncate text-xs text-muted">{song.file}</p>
							<p class="text-xs text-muted">{song.why}</p>
							<div class="flex gap-2">
								<input
									class="min-w-0 flex-1 rounded-md bg-black/30 px-2 py-1 text-xs"
									placeholder="YouTube Music link or video ID"
									bind:value={links[song.key]}
								/>
								<button class="text-xs font-medium hover:text-white disabled:opacity-40" disabled={busy || !links[song.key]?.trim()} onclick={() => link(song, links[song.key])}>Use</button>
								<button class="text-xs font-medium text-muted hover:text-white disabled:opacity-40" disabled={busy} onclick={() => link(song, '')}>Skip</button>
							</div>
						</li>
					{/each}
				</ul>
			{/if}
		{/if}
	{/if}
</div>

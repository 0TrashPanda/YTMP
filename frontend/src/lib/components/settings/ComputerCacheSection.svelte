<script lang="ts">
	// Settings → Song cache (the desktop app): songs this computer plays, kept on its disk so they
	// start at once next time, without a lookup. Off by default. The same settings as the phone's
	// (CacheSection: size, which songs are kept, likes, history, Takeout, playlists offline), and
	// copying the phone's songs here.
	import { onMount } from 'svelte';
	import { computer, loadComputer } from '../../computer.svelte';
	import { clearSongCache, getSongCache, setSongCache } from '../../api';
	import type { SongCacheSettings, SongCacheStatus } from '../../protocol.gen';
	import CacheSection from './CacheSection.svelte';
	import Toggle from './Toggle.svelte';
	import { askMyPhones, chooseWhich, copyFrom, phonesNow, stopCopying, type CopyWhich, type PhoneCopyStatus, type PhonesNow } from '../../computerPhones';

	let { onToast }: { onToast: (text: string) => void } = $props();

	let busy = $state(false);

	// The cache's settings, and this computer's numbers again after (whether songs play from here).
	async function changeCache(settings: SongCacheSettings): Promise<SongCacheStatus> {
		const status = await setSongCache(settings);
		void loadComputer();
		return status;
	}

	async function clearCache(): Promise<SongCacheStatus> {
		const status = await clearSongCache();
		void loadComputer();
		return status;
	}

	onMount(() => void loadComputer());

	// Songs from your phone (computerPhones.ts): your phones and the copy's progress, looked at every
	// second while this shows; the cache's numbers again once a copy finishes.
	let now = $state<PhonesNow | null>(null);
	/** When you pressed Copy from my phone (the server's clock): the phones that answer after that. */
	let askedAt = $state<number | null>(null);
	/** The same on this computer's clock: the answers of the first seconds are waited for, so you can pick when more phones answer. */
	let askedHere = 0;
	/** More than one phone answered: you pick which one (below). */
	let pick = $state<string[]>([]);
	/** How long phones with YTMP open get to answer before one is copied from by itself. */
	const ANSWER_MS = 3_000;
	onMount(() => {
		let wasRunning = false;
		const look = () =>
			phonesNow()
				.then((state) => {
					now = state;
					if (wasRunning && !state.copy?.running) void loadComputer();
					wasRunning = !!state.copy?.running;
					const answered = askedAt === null ? [] : state.phones.filter((p) => p.seenAt >= askedAt!);
					// One phone answered: copy from it. More: you pick which below.
					if (answered.length > 1) {
						askedAt = null;
						pick = answered.map((p) => p.id);
					} else if (answered.length === 1 && Date.now() - askedHere >= ANSWER_MS && !state.copy?.running) {
						askedAt = null;
						void run(() => copyFrom(answered[0]), "Copying didn't start");
					}
					if (askedAt !== null && !state.askedUntil) askedAt = null;
				})
				.catch(() => {});
		void look();
		const timer = setInterval(look, 1_000);
		return () => clearInterval(timer);
	});
	const copy = $derived(now?.copy ?? null);

	async function ask() {
		pick = [];
		askedHere = Date.now();
		askedAt = await askMyPhones();
	}

	function copyPhone(phone: { id: string; name: string; url: string }) {
		pick = [];
		void run(() => copyFrom(phone), "Copying didn't start");
	}

	// The phone's songs in three groups that don't overlap; the copy takes the ones turned on.
	// Local: the phone's downloads and its own music files.
	const groups: [CopyWhich, string, string][] = [
		['cache', 'Cached only', 'Songs your phone kept as it played them, that aren’t a download or music file on it.'],
		['both', 'Cached and local', 'Songs in the cache that are also a download or music file on your phone.'],
		['local', 'Local only', 'Downloads and music files on your phone that aren’t in its cache.']
	];
	/** Turned on or off here before the host answered: shown at once. */
	let whichPicked = $state<CopyWhich[] | null>(null);
	const which = $derived(whichPicked ?? now?.which ?? ['cache', 'both']);

	function turn(group: CopyWhich, on: boolean) {
		const next = groups.map(([g]) => g).filter((g) => (g === group ? on : which.includes(g)));
		whichPicked = next;
		void run(async () => void (await chooseWhich(next)), "The choice wasn't kept");
	}

	function copyText(c: PhoneCopyStatus): string {
		const left = [c.here ? `${c.here} were here already` : '', c.noRoom ? `${c.noRoom} didn't fit (make the cache bigger for them)` : ''].filter(Boolean).join(', ');
		if (c.running) return c.total ? `Copying ${c.copied + 1} of ${c.total}${c.current ? `: ${c.current}` : ''}` : `Asking ${c.phone}…`;
		if (c.error) return `Stopped after ${c.copied} ${c.copied === 1 ? 'song' : 'songs'}: ${c.error}`;
		return `Copied ${c.copied} ${c.copied === 1 ? 'song' : 'songs'} (${formatSize(c.bytes)}) from ${c.phone}${left ? `. ${left}.` : '.'}`;
	}

	const status = $derived(computer.status);

	function formatSize(bytes: number): string {
		return bytes >= 1024 ** 3 ? `${(bytes / 1024 ** 3).toFixed(1)} GB` : `${Math.round(bytes / 1024 ** 2)} MB`;
	}

	async function run(work: () => Promise<void>, problem: string) {
		busy = true;
		try {
			await work();
		} catch (e) {
			onToast(`${problem}: ${(e as Error).message}`);
		} finally {
			busy = false;
		}
	}
</script>

<section class="flex flex-col gap-5">
	{#if !status}
		<p class="text-sm text-muted">Asking this app's YouTube Music module…</p>
	{:else}
		<CacheSection load={getSongCache} clear={clearCache} change={changeCache} device="computer" {onToast} />
		<div class="flex flex-col gap-3">
			<div class="flex items-center gap-3">
				<div class="min-w-0 flex-1">
					<p class="text-sm font-medium">Songs from your phone</p>
					<p class="text-xs text-muted">
						Copies the songs your phone keeps to this computer over Wi-Fi, so they play from here. Have YTMP open on your phone, logged in with the same account: it answers at once. With
						more than one phone, you pick which. Without your server: press Send songs to my computer on the phone (Settings → Song cache and downloads); this computer copies by itself
						once it has copied from that phone before.
					</p>
				</div>
				<button
					class="shrink-0 rounded-full bg-white px-4 py-2 text-sm font-medium text-black hover:bg-white/90 disabled:opacity-50"
					disabled={busy || !!copy?.running || askedAt !== null || !status.cacheMb || which.length === 0}
					title={status.cacheMb ? undefined : 'Turn the song cache on first'}
					onclick={() => run(ask, "Your phone couldn't be asked")}>Copy from my phone</button
				>
			</div>
			<div class="flex flex-col gap-3">
				<p class="text-xs text-muted">Which songs: each song is in one of these, so turn on the ones you want (all three: everything).</p>
				{#each groups as [group, label, hint] (group)}
					<Toggle {label} {hint} checked={which.includes(group)} onchange={(on) => turn(group, on)} />
				{/each}
				{#if which.length === 0}
					<p class="text-xs text-red-400">Turn on at least one to copy.</p>
				{/if}
			</div>
			{#if askedAt !== null}
				<p class="text-xs text-muted">Waiting for your phone… open YTMP on it, on the same Wi-Fi.</p>
			{/if}
			{#if pick.length > 1}
				<p class="text-xs font-medium">{pick.length} phones answered: pick the one to copy from.</p>
			{/if}
			{#if copy}
				<div class="flex items-center gap-3">
					<p class="min-w-0 flex-1 text-xs {copy.error ? 'text-red-400' : 'text-muted'}">{copyText(copy)}</p>
					{#if copy.running}
						<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={() => run(async () => void (await stopCopying()), "Copying didn't stop")}
							>Stop</button
						>
					{/if}
				</div>
			{/if}
			{#each now?.phones ?? [] as phone (phone.id)}
				<div class="flex items-center gap-3">
					<p class="min-w-0 flex-1 truncate text-sm {pick.includes(phone.id) ? 'font-medium' : ''}">{phone.name}</p>
					<button
						class="rounded-full px-4 py-2 text-sm font-medium disabled:opacity-50 {pick.includes(phone.id) ? 'bg-white text-black hover:bg-white/90' : 'bg-raised hover:bg-line'}"
						disabled={busy || !!copy?.running || !status.cacheMb}
						onclick={() => copyPhone(phone)}>Copy its songs</button
					>
				</div>
			{/each}
		</div>
	{/if}
</section>

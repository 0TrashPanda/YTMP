<script lang="ts">
	// Settings → Song cache (the desktop app): songs this computer plays, kept on its disk so they
	// start at once next time, without a lookup. Off by default.
	import { onMount } from 'svelte';
	import { clearComputerCache, changeComputer, computer, loadComputer } from '../../computer.svelte';
	import Choice from './Choice.svelte';
	import NumberField from '../NumberField.svelte';
	import { askMyPhones, chooseWhich, copyFrom, phonesNow, stopCopying, type CopyWhich, type PhoneCopyStatus, type PhonesNow } from '../../computerPhones';

	let { onToast }: { onToast: (text: string) => void } = $props();

	const sizes: [string, string][] = [
		['0', 'Off'],
		['500', '500 MB'],
		['1024', '1 GB'],
		['2048', '2 GB'],
		['5120', '5 GB'],
		['10240', '10 GB']
	];
	let busy = $state(false);

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

	const whichOptions: [CopyWhich, string][] = [
		['cache', 'Only cache'],
		['both', 'Cache and local'],
		['local', 'Only local']
	];
	/** Picked here before the host answered: shown at once. */
	let whichPicked = $state<CopyWhich | null>(null);
	const which = $derived(whichPicked ?? now?.which ?? 'both');

	function copyText(c: PhoneCopyStatus): string {
		const left = [c.here ? `${c.here} were here already` : '', c.noRoom ? `${c.noRoom} didn't fit (make the cache bigger for them)` : ''].filter(Boolean).join(', ');
		if (c.running) return c.total ? `Copying ${c.copied + 1} of ${c.total}${c.current ? `: ${c.current}` : ''}` : `Asking ${c.phone}…`;
		if (c.error) return `Stopped after ${c.copied} ${c.copied === 1 ? 'song' : 'songs'}: ${c.error}`;
		return `Copied ${c.copied} ${c.copied === 1 ? 'song' : 'songs'} (${formatSize(c.bytes)}) from ${c.phone}${left ? `. ${left}.` : '.'}`;
	}

	const status = $derived(computer.status);
	const size = $derived(String(status?.cacheMb ?? 0));
	const options = sizes;
	const used = $derived(status ? `${status.cacheSongs} ${status.cacheSongs === 1 ? 'song' : 'songs'}, ${formatSize(status.cacheBytes)}` : '');

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
		<Choice
			label="Song cache"
			hint="Songs played on this computer are kept on it, so they start at once next time without looking them up on YouTube. Only music: no podcasts, nothing over an hour. When it's full, the songs you played least lately go first."
			{options}
			value={size}
			onchange={(v) => run(() => changeComputer({ cacheMb: Number(v) }), "The size didn't change")}
		>
			<NumberField
				value={status.cacheMb / 1024}
				min={0}
				max={10000}
				step={0.1}
				unit="GB"
				label="Size in GB (0: off)"
				onchange={(v) => run(() => changeComputer({ cacheMb: Math.round(v * 1024) }), "The size didn't change")}
			/>
		</Choice>
		<div class="flex items-center gap-3">
			<div class="min-w-0 flex-1">
				<p class="text-sm font-medium">Kept now</p>
				<p class="text-xs text-muted">{used}</p>
			</div>
			<button
				class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-50"
				disabled={busy || !status.cacheSongs}
				onclick={() => run(clearComputerCache, "The cache wasn't cleared")}>Clear cache</button
			>
		</div>
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
					disabled={busy || !!copy?.running || askedAt !== null || !status.cacheMb}
					title={status.cacheMb ? undefined : 'Turn the song cache on first'}
					onclick={() => run(ask, "Your phone couldn't be asked")}>Copy from my phone</button
				>
			</div>
			<Choice
				label="Which songs"
				hint="Cache: the songs your phone kept as it played them. Local: its downloads and its own music files that are a YouTube Music song."
				options={whichOptions}
				value={which}
				onchange={(v) => {
					whichPicked = v;
					void run(async () => void (await chooseWhich(v)), "The choice wasn't kept");
				}}
			/>
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

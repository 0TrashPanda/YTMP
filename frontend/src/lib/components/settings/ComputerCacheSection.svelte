<script lang="ts">
	// Settings → Song cache (the desktop app): songs this computer plays, kept on its disk so they
	// start at once next time, without a lookup. Off by default.
	import { onMount } from 'svelte';
	import { clearComputerCache, changeComputer, computer, loadComputer } from '../../computer.svelte';
	import Choice from './Choice.svelte';
	import { copyFromPhone, phonesHere, stopCopying, type PhoneCopyStatus, type PhonesState } from '../../computerPhones';

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

	// Songs from your phone: the phones here, looked at every few seconds while this shows (and
	// the copy's progress), and the computer's cache numbers once a copy finishes.
	let phones = $state<PhonesState | null>(null);
	onMount(() => {
		let wasRunning = false;
		const look = () =>
			phonesHere()
				.then((state) => {
					phones = state;
					if (wasRunning && !state.copy?.running) void loadComputer();
					wasRunning = !!state.copy?.running;
				})
				.catch(() => {});
		void look();
		const timer = setInterval(look, 2_000);
		return () => clearInterval(timer);
	});
	const copy = $derived(phones?.copy ?? null);

	function copyText(c: PhoneCopyStatus): string {
		const left = [c.here ? `${c.here} were here already` : '', c.noRoom ? `${c.noRoom} didn't fit (make the cache bigger for them)` : ''].filter(Boolean).join(', ');
		if (c.running) return c.total ? `Copying ${c.copied + 1} of ${c.total}${c.current ? `: ${c.current}` : ''}` : `Asking ${c.phone}…`;
		if (c.error) return `Stopped after ${c.copied} ${c.copied === 1 ? 'song' : 'songs'}: ${c.error}`;
		return `Copied ${c.copied} ${c.copied === 1 ? 'song' : 'songs'} (${formatSize(c.bytes)}) from ${c.phone}${left ? `. ${left}.` : '.'}`;
	}

	const status = $derived(computer.status);
	const size = $derived(String(status?.cacheMb ?? 0));
	const options = $derived(sizes.some(([v]) => v === size) ? sizes : [...sizes, [size, `${Math.round(Number(size) / 1024)} GB`] as [string, string]]);
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
		/>
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
			<div>
				<p class="text-sm font-medium">Songs from your phone</p>
				<p class="text-xs text-muted">
					Copies the songs your phone keeps (its song cache) to this computer over Wi-Fi, so they play from here without looking them up. Open YTMP on your phone on the same Wi-Fi,
					logged in with the same account as here.
				</p>
			</div>
			{#if copy}
				<div class="flex items-center gap-3">
					<p class="min-w-0 flex-1 text-xs {copy.error ? 'text-red-400' : 'text-muted'}">{copyText(copy)}</p>
					{#if copy.running}
						<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={() => run(async () => void (phones = await stopCopying()), "Copying didn't stop")}
							>Stop</button
						>
					{/if}
				</div>
			{/if}
			{#each phones?.phones ?? [] as phone (phone.id)}
				<div class="flex items-center gap-3">
					<p class="min-w-0 flex-1 truncate text-sm">{phone.name}</p>
					<button
						class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-50"
						disabled={busy || !!copy?.running || !status.cacheMb}
						title={status.cacheMb ? undefined : 'Turn the song cache on first'}
						onclick={() => run(async () => void (phones = await copyFromPhone(phone)), "Copying didn't start")}>Copy its songs</button
					>
				</div>
			{:else}
				<p class="text-xs text-muted">No phone found on this network. Open YTMP on your phone (the newest version).</p>
			{/each}
		</div>
	{/if}
</section>

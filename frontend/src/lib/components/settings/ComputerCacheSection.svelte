<script lang="ts">
	// Settings → Song cache (the desktop app): songs this computer plays, kept on its disk so they
	// start at once next time, without a lookup. Off by default.
	import { onMount } from 'svelte';
	import { clearComputerCache, changeComputer, computer, loadComputer } from '../../computer.svelte';
	import Choice from './Choice.svelte';

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
	{/if}
</section>

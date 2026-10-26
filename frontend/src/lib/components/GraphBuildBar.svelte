<script lang="ts">
	// The song graph being built again (after an import, deleting from your history, or a new
	// version of the server): what it does and how far it got. It asks every half second while
	// it's busy, and not at all otherwise; [kick] makes it look (a rebuild was just asked for).
	import { onDestroy, onMount } from 'svelte';
	import type { GraphBuildStatus } from '../protocol.gen';

	let { load, kick = 0, onDone }: { load: () => Promise<GraphBuildStatus>; kick?: number; onDone?: () => void } = $props();

	let status = $state<GraphBuildStatus | null>(null);
	let timer: ReturnType<typeof setTimeout> | null = null;
	let busySeen = false;
	let tries = 0;

	async function poll() {
		timer = null;
		try {
			status = await load();
		} catch {
			status = null;
		}
		const busy = !!status && (status.building || status.waiting);
		if (busy) busySeen = true;
		else if (busySeen) {
			busySeen = false;
			onDone?.();
		}
		// Busy: again soon. Just asked for: a few more tries, it may not have started yet.
		if (busy || tries-- > 0) timer = setTimeout(poll, 500);
	}

	function start() {
		tries = 6;
		if (!timer) void poll();
	}

	onMount(() => void poll());
	$effect(() => {
		if (kick > 0) start();
	});
	onDestroy(() => timer && clearTimeout(timer));

	const percent = $derived(Math.round((status?.fraction ?? 0) * 100));
</script>

{#if status && (status.building || status.waiting)}
	<div class="flex flex-col gap-1.5 rounded-xl bg-raised/60 px-4 py-3" role="status" aria-live="polite">
		<div class="flex items-center justify-between gap-3 text-sm">
			<span class="min-w-0">{status.building ? `Learning the song graph · ${status.step ?? ''}` : 'The song graph learns it in a moment…'}</span>
			{#if status.building}<span class="shrink-0 text-muted tabular-nums">{percent}%</span>{/if}
		</div>
		<div class="h-1.5 overflow-hidden rounded-full bg-line" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={status.building ? percent : undefined}>
			{#if status.building}
				<div class="h-full rounded-full bg-white transition-[width] duration-500" style:width="{Math.max(2, percent)}%"></div>
			{:else}
				<div class="h-full w-1/3 animate-pulse rounded-full bg-white/50"></div>
			{/if}
		</div>
	</div>
{/if}

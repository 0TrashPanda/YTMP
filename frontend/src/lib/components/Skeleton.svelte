<script lang="ts">
	// Grey placeholders the shape of what's loading (song rows), so a page has its layout right
	// away and doesn't jump when the songs come in.
	let { rows = 8, art = true }: { rows?: number; /** Square art at the start (false: track numbers, like an album). */ art?: boolean } = $props();
</script>

<div class="flex animate-pulse flex-col gap-1" aria-label="Loading" role="status">
	{#each Array.from({ length: rows }, (_, i) => i) as i (i)}
		<div class="flex items-center gap-3 px-2 py-1.5">
			{#if art}<div class="h-12 w-12 shrink-0 rounded bg-raised"></div>{:else}<div class="h-4 w-6 shrink-0 rounded bg-raised"></div>{/if}
			<div class="flex min-w-0 flex-1 flex-col gap-2">
				<div class="h-3.5 rounded bg-raised" style:width="{55 + ((i * 37) % 35)}%"></div>
				<div class="h-3 rounded bg-raised/70" style:width="{30 + ((i * 23) % 25)}%"></div>
			</div>
		</div>
	{/each}
</div>

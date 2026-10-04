<script lang="ts">
	// Dots under a row that scrolls sideways: how many screens wide it is, and which one you're on.
	let { target }: { target: HTMLElement | undefined } = $props();

	let pages = $state(1);
	let page = $state(0);

	function measure() {
		if (!target || !target.clientWidth) return;
		const width = target.clientWidth;
		// A few pixels over doesn't make another page.
		pages = Math.max(1, Math.ceil((target.scrollWidth - 8) / width));
		const atEnd = target.scrollLeft + width >= target.scrollWidth - 8;
		page = atEnd ? pages - 1 : Math.min(pages - 1, Math.round(target.scrollLeft / width));
	}

	$effect(() => {
		const element = target;
		if (!element) return;
		measure();
		element.addEventListener('scroll', measure, { passive: true });
		const resize = new ResizeObserver(measure);
		resize.observe(element);
		// Cards and rows come in after the row itself.
		const changes = new MutationObserver(measure);
		changes.observe(element, { childList: true });
		return () => {
			element.removeEventListener('scroll', measure);
			resize.disconnect();
			changes.disconnect();
		};
	});
</script>

{#if pages > 1}
	<div class="flex justify-center gap-1.5" aria-hidden="true">
		{#each { length: pages } as _, i (i)}
			<span class="h-1.5 rounded-full transition-all {i === page ? 'w-4 bg-white' : 'w-1.5 bg-white/30'}"></span>
		{/each}
	</div>
{/if}

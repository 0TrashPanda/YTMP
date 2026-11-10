<script lang="ts">
	// One line of text that slides along when it doesn't fit, like YTM's song title in the player:
	// it waits, slides to the end, waits, and starts over. Off (Settings → Player → Slide long
	// titles) or with Reduce motion it's cut off with "…" as before.
	import { reducedMotion } from '../motion.svelte';
	import { settings } from '../settings.svelte';

	let { text, class: className = '' }: { text: string; class?: string } = $props();

	let outer = $state<HTMLElement>();
	let inner = $state<HTMLElement>();
	/** How far it has to slide to show the end (0: it fits). */
	let shift = $state(0);

	$effect(() => {
		void text;
		const box = outer;
		const line = inner;
		if (!box || !line) return;
		const measure = () => (shift = Math.max(0, line.scrollWidth - box.clientWidth));
		measure();
		const resize = new ResizeObserver(measure);
		resize.observe(box);
		return () => resize.disconnect();
	});

	const moving = $derived(shift > 0 && settings.titleScroll.value && !reducedMotion());
	// About 30 pixels a second, plus the waits at both ends.
	const seconds = $derived(Math.max(6, shift / 30 + 4));
	const fade = 'linear-gradient(90deg, #000 88%, transparent)';
</script>

<span bind:this={outer} class="block overflow-hidden {className}" style:mask-image={moving ? fade : undefined} style:-webkit-mask-image={moving ? fade : undefined}>
	<span
		bind:this={inner}
		class={moving ? 'marquee inline-block whitespace-nowrap' : 'block truncate'}
		style:--marquee-shift="-{shift}px"
		style:animation-duration={moving ? `${seconds}s` : undefined}>{text}</span
	>
</span>

<script lang="ts" module>
	/** Sharp pictures loaded since the app started: shown straight away, without the small one. */
	const loaded = new Set<string>();
</script>

<script lang="ts">
	// A big picture that shows its small version first (images.svelte.ts: smallUrl), then the sharp
	// one once it's loaded and decoded, so the swap doesn't flash. Without [small] it's a plain
	// <img>. A picture that can't load is hidden (its grey tile shows), or [onbroken] decides.
	let {
		src,
		small,
		lazy = true,
		onbroken,
		class: className = ''
	}: { src: string; small?: string; lazy?: boolean; onbroken?: () => void; class?: string } = $props();

	/** The sharp picture that's ready to show. */
	let ready = $state<string | null>(null);
	let hidden = $state(false);
	const shown = $derived(!small || ready === src || loaded.has(src) ? src : small);

	$effect(() => {
		void src;
		hidden = false;
	});

	function onload() {
		hidden = false;
		if (shown === src) return loaded.add(src);
		// The small one is in: now fetch the sharp one in the background.
		const want = src;
		const sharp = new Image();
		sharp.referrerPolicy = 'no-referrer';
		sharp.src = want;
		const done = () => {
			loaded.add(want);
			if (src === want) ready = want;
		};
		// If the sharp one fails, the small one stays.
		sharp.decode().then(done, () => sharp.complete && sharp.naturalWidth && done());
	}

	function onerror() {
		// The small one failed: try the sharp one.
		if (shown !== src) return (ready = src);
		if (onbroken) onbroken();
		else hidden = true;
	}
</script>

<img
	src={shown}
	alt=""
	loading={lazy ? 'lazy' : undefined}
	referrerpolicy="no-referrer"
	class={className}
	style:visibility={hidden ? 'hidden' : undefined}
	{onload}
	{onerror}
/>

<script lang="ts" generics="T extends string">
	// A setting with a few options side by side (like YouTube Music's), with its name and what it does.
	// Under them can come more (a number to type, for one that isn't among the options).
	import type { Snippet } from 'svelte';

	let {
		label,
		hint,
		options,
		value,
		onchange,
		children
	}: { label: string; hint?: string; options: [T, string][]; value: T; onchange: (value: T) => void; children?: Snippet } = $props();
</script>

<div class="flex flex-col gap-2">
	<div>
		<p class="text-sm font-medium">{label}</p>
		{#if hint}<p class="text-xs text-muted">{hint}</p>{/if}
	</div>
	<div class="flex rounded-full bg-raised p-1" role="radiogroup" aria-label={label}>
		{#each options as [option, text] (option)}
			<button
				class="flex-1 rounded-full py-1.5 text-sm {value === option ? 'bg-white font-medium text-black' : 'text-muted'}"
				role="radio"
				aria-checked={value === option}
				onclick={() => onchange(option)}>{text}</button
			>
		{/each}
	</div>
	{@render children?.()}
</div>

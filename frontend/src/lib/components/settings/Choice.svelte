<script lang="ts" generics="T extends string | number | boolean">
	// A setting with a few options side by side (like YouTube Music's), with its name and what it does.
	// Under them can come more (a number to type, for one that isn't among the options). With a
	// [setting] (settings.svelte.ts) it follows your account, with the phone icon to keep it
	// different on this device.
	import type { Snippet } from 'svelte';
	import type { Setting } from '../../settings.svelte';
	import DeviceNote from './DeviceNote.svelte';
	import DeviceToggle from './DeviceToggle.svelte';

	let {
		label,
		hint,
		options,
		setting,
		value,
		onchange,
		children
	}: {
		label: string;
		hint?: string;
		options: [T, string][];
		setting?: Setting<T>;
		value?: T;
		onchange?: (value: T) => void;
		children?: Snippet;
	} = $props();

	const current = $derived(value ?? setting?.value);
	const pick = (v: T) => (onchange ? onchange(v) : setting?.set(v));
	const account = $derived(setting && options.find(([o]) => o === setting.account)?.[1]);
</script>

<div class="flex flex-col gap-2">
	<div class="flex items-start gap-2">
		<div class="min-w-0 flex-1">
			<p class="text-sm font-medium">{label}</p>
			{#if hint}<p class="text-xs text-muted">{hint}</p>{/if}
		</div>
		{#if setting}<DeviceToggle setting={setting as Setting<unknown>} {label} />{/if}
	</div>
	<div class="flex rounded-full bg-raised p-1" role="radiogroup" aria-label={label}>
		{#each options as [option, text] (String(option))}
			<button
				class="flex-1 rounded-full py-1.5 text-sm {current === option ? 'bg-white font-medium text-black' : 'text-muted'}"
				role="radio"
				aria-checked={current === option}
				onclick={() => pick(option)}>{text}</button
			>
		{/each}
	</div>
	{@render children?.()}
	{#if setting}<DeviceNote setting={setting as Setting<unknown>} {account} />{/if}
</div>

<script lang="ts">
	// A setting that's a few numbers (settings.svelte.ts), like the choices when adding a long
	// playlist asks how many: each as a chip to remove, and a box to add one more.
	import type { Setting } from '../../settings.svelte';
	import Icon from '../Icon.svelte';
	import DeviceNote from './DeviceNote.svelte';
	import DeviceToggle from './DeviceToggle.svelte';

	let {
		label,
		hint,
		setting,
		min,
		max,
		most,
		unit
	}: { label: string; hint?: string; setting: Setting<number[]>; min: number; max: number; /** At most this many numbers. */ most: number; unit: string } = $props();

	let typed = $state<number | null>(null);
	const value = $derived(setting.value);

	function add(event: SubmitEvent) {
		event.preventDefault();
		const n = Math.round(Number(typed));
		if (!Number.isFinite(n) || n < min || n > max || value.includes(n) || value.length >= most) return;
		setting.set([...value, n].sort((a, b) => a - b));
		typed = null;
	}

	const remove = (n: number) => setting.set(value.filter((v) => v !== n));
</script>

<div class="flex flex-col gap-2">
	<div class="flex items-start gap-2">
		<div class="min-w-0 flex-1">
			<p class="text-sm font-medium">{label}</p>
			{#if hint}<p class="text-xs text-muted">{hint}</p>{/if}
		</div>
		<DeviceToggle setting={setting as Setting<unknown>} {label} />
	</div>
	<div class="flex flex-wrap items-center gap-2">
		{#each value as n (n)}
			<span class="flex items-center gap-1 rounded-full bg-raised py-1 pr-1 pl-3 text-sm tabular-nums">
				{n.toLocaleString()}
				<button class="rounded-full p-1 text-muted hover:bg-line hover:text-white" aria-label="Remove {n}" onclick={() => remove(n)}><Icon name="close" size={16} /></button>
			</span>
		{/each}
		{#if value.length < most}
			<form class="flex items-center gap-2" onsubmit={add}>
				<input
					class="w-20 rounded-lg bg-raised px-2 py-1.5 text-right tabular-nums outline-none ring-white/40 [appearance:textfield] focus:ring-2 [&::-webkit-inner-spin-button]:appearance-none"
					type="number"
					inputmode="numeric"
					{min}
					{max}
					placeholder={unit}
					aria-label="{label}: add one"
					bind:value={typed}
				/>
				<button class="rounded-full bg-raised px-3 py-1.5 text-sm hover:bg-line disabled:opacity-40" disabled={typed === null}>Add</button>
			</form>
		{/if}
	</div>
	<DeviceNote setting={setting as Setting<unknown>} />
</div>

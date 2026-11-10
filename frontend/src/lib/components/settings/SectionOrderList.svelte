<script lang="ts">
	// The order of a page's sections (sectionOrder.svelte.ts), to move up and down: Settings →
	// Search and Settings → Artist pages.
	import type { SectionOrder } from '../../sectionOrder.svelte';
	import type { Setting } from '../../settings.svelte';
	import Icon from '../Icon.svelte';
	import DeviceNote from './DeviceNote.svelte';
	import DeviceToggle from './DeviceToggle.svelte';

	let { order, label, hint }: { order: SectionOrder; label: string; hint: string } = $props();

	const list = $derived(order.list());
	const setting = $derived(order.setting as Setting<unknown>);
	const iconButton = 'rounded-full p-1.5 text-muted hover:bg-raised hover:text-white disabled:opacity-30';
</script>

<section class="flex flex-col gap-3">
	<div class="flex items-end gap-2">
		<div class="min-w-0 flex-1">
			<p class="text-sm font-medium">{label}</p>
			<p class="text-xs text-muted">{hint}</p>
		</div>
		<DeviceToggle {setting} {label} />
		{#if !order.isDefault()}
			<button class="shrink-0 rounded-full px-3 py-1 text-xs text-muted hover:bg-raised hover:text-white" onclick={() => order.reset()}>Defaults</button>
		{/if}
	</div>
	{#each list as { key, name }, i (key)}
		<div class="flex items-center gap-1">
			<span class="min-w-0 flex-1 truncate text-sm">{name}</span>
			<button class={iconButton} aria-label="Move {name} up" disabled={i === 0} onclick={() => order.move(key, list[i - 1].key)}>
				<Icon name="expand" size={20} />
			</button>
			<button class={iconButton} aria-label="Move {name} down" disabled={i === list.length - 1} onclick={() => order.move(key, list[i + 1].key)}>
				<Icon name="collapse" size={20} />
			</button>
		</div>
	{/each}
	<DeviceNote {setting} />
</section>

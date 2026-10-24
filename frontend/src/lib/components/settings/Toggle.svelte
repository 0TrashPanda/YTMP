<script lang="ts">
	// A setting that's on or off: its name and what it does, the switch on the right. With a
	// [setting] (settings.svelte.ts) it follows your account, with the phone icon to keep it
	// different on this device.
	import type { Setting } from '../../settings.svelte';
	import DeviceNote from './DeviceNote.svelte';
	import DeviceToggle from './DeviceToggle.svelte';
	import Switch from './Switch.svelte';

	let {
		label,
		hint,
		setting,
		checked,
		disabled = false,
		onchange
	}: { label: string; hint?: string; setting?: Setting<boolean>; checked?: boolean; disabled?: boolean; onchange?: (on: boolean) => void } = $props();

	const on = $derived(checked ?? setting?.value ?? false);
	const change = (next: boolean) => (onchange ? onchange(next) : setting?.set(next));
</script>

<div class="flex flex-col gap-1">
	<div class="flex items-center gap-3">
		<div class="min-w-0 flex-1">
			<p class="text-sm font-medium">{label}</p>
			{#if hint}<p class="text-xs text-muted">{hint}</p>{/if}
		</div>
		{#if setting}<DeviceToggle setting={setting as Setting<unknown>} {label} />{/if}
		<Switch checked={on} {disabled} {label} onchange={change} />
	</div>
	{#if setting}<DeviceNote setting={setting as Setting<unknown>} account={setting.account ? 'On' : 'Off'} />{/if}
</div>

<script lang="ts">
	// Discord's permission override: deny (✗), follow the role (/), or allow (✓).
	import type { Override } from '../../permissions';

	let { value, disabled = false, canAllow = true, onchange }: { value: Override; disabled?: boolean; canAllow?: boolean; onchange: (v: Override) => void } = $props();

	const options: { v: Override; label: string; on: string }[] = [
		{ v: 'deny', label: 'Deny', on: 'bg-d-red text-white' },
		{ v: 'inherit', label: 'Follow role', on: 'bg-[#4e5058] text-white' },
		{ v: 'allow', label: 'Allow', on: 'bg-d-green text-white' }
	];
</script>

<div class="flex shrink-0 overflow-hidden rounded border border-d-deep">
	{#each options as o (o.v)}
		<button
			type="button"
			title={o.label}
			aria-label={o.label}
			aria-pressed={value === o.v}
			disabled={disabled || (o.v === 'allow' && !canAllow && value !== 'allow')}
			class="flex h-7 w-8 items-center justify-center border-d-deep transition-colors not-first:border-l disabled:cursor-not-allowed
				{value === o.v ? o.on : 'text-d-faint hover:bg-d-hover'}"
			onclick={() => onchange(o.v)}
		>
			{#if o.v === 'deny'}
				<svg viewBox="0 0 20 20" class="h-3.5 w-3.5"><path fill="currentColor" d="M5.4 4 10 8.6 14.6 4 16 5.4 11.4 10l4.6 4.6-1.4 1.4-4.6-4.6L5.4 16 4 14.6 8.6 10 4 5.4z" /></svg>
			{:else if o.v === 'inherit'}
				<svg viewBox="0 0 20 20" class="h-3.5 w-3.5"><path fill="currentColor" d="M13.6 3.5 15 4.2 6.4 16.5 5 15.8z" /></svg>
			{:else}
				<svg viewBox="0 0 20 20" class="h-3.5 w-3.5"><path fill="currentColor" d="M7.9 14.6 3.6 10.3l1.4-1.4 2.9 2.9 7.1-7.1 1.4 1.4z" /></svg>
			{/if}
		</button>
	{/each}
</div>

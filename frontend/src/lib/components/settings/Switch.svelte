<script lang="ts">
	// Discord's toggle: green with a check when on, grey with a cross when off.
	let { checked, disabled = false, label, onchange }: { checked: boolean; disabled?: boolean; label: string; onchange: (on: boolean) => void } = $props();
</script>

<button
	type="button"
	role="switch"
	aria-checked={checked}
	aria-label={label}
	{disabled}
	class="relative h-6 w-10 shrink-0 rounded-full transition-colors disabled:cursor-not-allowed disabled:opacity-50 {checked ? 'bg-d-green' : 'bg-[#80848e]'}"
	onclick={() => onchange(!checked)}
>
	<span class="absolute top-[3px] flex h-[18px] w-[18px] items-center justify-center rounded-full bg-white transition-all {checked ? 'left-[19px]' : 'left-[3px]'}">
		{#if checked}
			<svg viewBox="0 0 20 20" class="h-3 w-3 text-d-green"><path fill="currentColor" d="M7.9 14.6 3.6 10.3l1.4-1.4 2.9 2.9 7.1-7.1 1.4 1.4z" /></svg>
		{:else}
			<svg viewBox="0 0 20 20" class="h-3 w-3 text-[#80848e]"><path fill="currentColor" d="M5.4 4 10 8.6 14.6 4 16 5.4 11.4 10l4.6 4.6-1.4 1.4-4.6-4.6L5.4 16 4 14.6 8.6 10 4 5.4z" /></svg>
		{/if}
	</span>
</button>

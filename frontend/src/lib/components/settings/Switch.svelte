<script lang="ts">
	// A switch like YouTube Music's: blue with the knob on the right when on, grey when off.
	let { checked, disabled = false, label, onchange }: { checked: boolean; disabled?: boolean; label: string; onchange: (on: boolean) => void } = $props();
</script>

<button
	type="button"
	role="switch"
	aria-checked={checked}
	aria-label={label}
	{disabled}
	class="relative h-5 w-9 shrink-0 rounded-full transition-colors disabled:cursor-not-allowed disabled:opacity-50 {checked ? 'bg-link/60' : 'bg-line'}"
	onclick={() => onchange(!checked)}
>
	<span class="absolute top-1/2 h-5 w-5 -translate-y-1/2 rounded-full shadow transition-all {checked ? 'left-4 bg-link' : 'left-0 bg-muted'}"></span>
</button>

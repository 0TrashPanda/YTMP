<script lang="ts">
	import { formatStep, typedNumber } from '../format';
	// A setting's number, typed: next to its slider or its few choices. It takes what you typed
	// when you press Enter or leave the box, kept within min–max and rounded to step; something
	// that isn't a number puts the old value back.
	let {
		value,
		min,
		max,
		step = 1,
		unit,
		label,
		disabled = false,
		class: className = 'bg-raised py-1.5',
		onchange
	}: {
		value: number;
		min: number;
		max: number;
		step?: number;
		unit?: string;
		label: string;
		disabled?: boolean;
		/** The box's colours (it sits in YTM-style settings and Discord-style room settings). */
		class?: string;
		onchange: (value: number) => void;
	} = $props();

	const format = (n: number) => formatStep(n, step);

	function commit(input: HTMLInputElement) {
		const n = typedNumber(input.value, min, max, step);
		input.value = format(n ?? value);
		if (n !== null && n !== Number(format(value))) onchange(n);
	}
</script>

<label class="flex shrink-0 items-center gap-1.5 text-sm">
	<input
		type="number"
		class="w-20 min-w-0 rounded-lg px-2 text-right tabular-nums outline-none ring-white/40 [appearance:textfield] focus:ring-2 disabled:opacity-40 [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none {className}"
		inputmode={min < 0 ? undefined : step < 1 ? 'decimal' : 'numeric'}
		{min}
		{max}
		step="any"
		value={format(value)}
		{disabled}
		aria-label={label}
		onchange={(e) => commit(e.currentTarget)}
		onkeydown={(e) => {
			if (e.key === 'Enter') {
				e.preventDefault();
				commit(e.currentTarget);
			} else if (e.key === 'Escape') {
				// Not closing the sheet or settings it's in: only the typing.
				e.stopPropagation();
				e.currentTarget.value = format(value);
				e.currentTarget.blur();
			}
		}}
	/>
	{#if unit}<span class="text-muted">{unit}</span>{/if}
</label>

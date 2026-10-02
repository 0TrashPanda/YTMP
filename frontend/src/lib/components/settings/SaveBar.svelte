<script lang="ts">
	// Discord's "Careful — you have unsaved changes!" bar.
	let { shake = false, busy = false, onReset, onSave }: { shake?: boolean; busy?: boolean; onReset: () => void; onSave: () => void } = $props();
</script>

<div
	class="flex items-center gap-3 rounded-lg p-2.5 pl-4 shadow-[0_8px_16px_rgba(0,0,0,0.24)] transition-colors {shake ? 'd-shake bg-d-red' : 'bg-d-bar'}"
>
	<p class="min-w-0 flex-1 text-sm font-medium text-d-text">Careful — you have unsaved changes!</p>
	<button type="button" class="px-3 py-1.5 text-sm font-medium text-white hover:underline" onclick={onReset}>Reset</button>
	<button
		type="button"
		class="rounded bg-d-greenbtn px-4 py-1.5 text-sm font-medium text-white hover:bg-[#1a6334] disabled:opacity-50"
		disabled={busy}
		onclick={onSave}
	>
		Save Changes
	</button>
</div>

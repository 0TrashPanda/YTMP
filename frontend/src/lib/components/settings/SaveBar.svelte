<script lang="ts">
	// Changes to the room's settings wait for Save (everyone in the room gets them at once).
	let { shake = false, busy = false, onReset, onSave }: { shake?: boolean; busy?: boolean; onReset: () => void; onSave: () => void } = $props();
</script>

<div class="flex items-center gap-3 rounded-2xl p-2 pl-4 shadow-[0_8px_16px_rgba(0,0,0,0.4)] transition-colors {shake ? 'shake bg-accent' : 'bg-raised'}">
	<p class="min-w-0 flex-1 text-sm">Not saved yet</p>
	<button type="button" class="rounded-full px-3 py-1.5 text-sm font-medium hover:bg-line" onclick={onReset}>Undo</button>
	<button type="button" class="rounded-full bg-white px-4 py-1.5 text-sm font-medium text-black hover:bg-white/90 disabled:opacity-50" disabled={busy} onclick={onSave}>Save</button>
</div>

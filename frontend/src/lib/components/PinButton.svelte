<script lang="ts">
	// Pins a playlist, album, artist or podcast to the top of Home (or unpins it).
	import { isPinned, togglePin, type Pin } from '../home.svelte';
	import Icon from './Icon.svelte';

	let { pin, onToast }: { pin: Pin | null; onToast: (text: string) => void } = $props();

	const pinned = $derived(!!pin && isPinned(pin.kind, pin.id));

	function toggle() {
		if (!pin) return;
		togglePin(pin);
		onToast(pinned ? `Pinned to Home` : `Unpinned from Home`);
	}
</script>

<button
	class="flex items-center gap-2 rounded-full px-4 py-2 text-sm hover:bg-line disabled:opacity-40 {pinned ? 'bg-white font-medium text-black hover:bg-white/90' : 'bg-raised'}"
	disabled={!pin}
	aria-pressed={pinned}
	onclick={toggle}
>
	<Icon name="pin" size={18} />
	{pinned ? 'Pinned' : 'Pin to Home'}
</button>

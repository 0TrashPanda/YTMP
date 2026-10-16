<script lang="ts">
	// Settings → Local music: which music the Local tab shows on this device.
	import { local, setLocalNetwork } from '../../local.svelte';
	import Switch from './Switch.svelte';
</script>

<section class="flex flex-col gap-5">
	<div class="flex items-center gap-4">
		<div class="min-w-0 flex-1">
			<p class="text-sm font-medium">Music on the network</p>
			<p class="text-xs text-muted">Music another device shares with YTMP, like a Raspberry Pi with your music next to the stereo.</p>
		</div>
		<Switch checked={local.network} label="Music on the network" onchange={setLocalNetwork} />
	</div>
	<p class="text-xs text-muted">The Local tab shows up when there's music to show. Saved on this device.</p>
</section>

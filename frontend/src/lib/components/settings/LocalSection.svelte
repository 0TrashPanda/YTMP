<script lang="ts">
	// Settings → Local music: which music the Local tab shows on this device.
	import { local, setLocalNetwork, setPhoneMusic } from '../../local.svelte';
	import { nativeBridge } from '../../native';
	import Switch from './Switch.svelte';

	let { onToast }: { onToast: (text: string) => void } = $props();

	let busy = $state(false);

	async function phone(on: boolean) {
		busy = true;
		const problem = await setPhoneMusic(on);
		busy = false;
		if (problem) onToast(problem);
	}
</script>

<section class="flex flex-col gap-5">
	<div class="flex items-center gap-4">
		<div class="min-w-0 flex-1">
			<p class="text-sm font-medium">Music on the network</p>
			<p class="text-xs text-muted">Music another device shares with YTMP, like a Raspberry Pi with your music next to the stereo.</p>
		</div>
		<Switch checked={local.network} label="Music on the network" onchange={setLocalNetwork} />
	</div>
	{#if nativeBridge?.setPhoneMusic}
		<div class="flex items-center gap-4">
			<div class="min-w-0 flex-1">
				<p class="text-sm font-medium">Music on this phone</p>
				<p class="text-xs text-muted">The songs stored on this phone, in rooms on this phone. It plays on every speaker, also the Pi's rooms. Android asks once.</p>
			</div>
			<Switch checked={local.phone} label="Music on this phone" disabled={busy} onchange={phone} />
		</div>
	{/if}
	<p class="text-xs text-muted">The Local tab shows up when there's music to show. Saved on this {nativeBridge ? 'phone' : 'device'}.</p>
</section>

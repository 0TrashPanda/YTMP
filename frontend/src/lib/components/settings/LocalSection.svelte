<script lang="ts">
	// Settings → Local music: which music the Local tab shows on this device.
	import { local, localPlace, addMusicFolder, removeMusicFolder, setMediaScan, setLocalNetwork, setLocalRemember, setPhoneMusic } from '../../local.svelte';
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
	{#if nativeBridge?.addMusicFolder}
		<div class="flex items-center gap-4">
			<div class="min-w-0 flex-1">
				<p class="text-sm font-medium">Use Android's music scan</p>
				<p class="text-xs text-muted">Everything Android found on the phone. Off: only the folders below. A song in both shows once.</p>
			</div>
			<Switch checked={local.scan} label="Use Android's music scan" onchange={setMediaScan} />
		</div>
		<div class="flex flex-col gap-2">
			<div>
				<p class="text-sm font-medium">Music folders</p>
				<p class="text-xs text-muted">Pick a folder with music that Android's own scan doesn't find, like a folder on the SD card. Its songs show with the music on this phone. No extra permission needed.</p>
			</div>
			{#each local.folders as folder (folder.id)}
				<div class="flex items-center gap-3 rounded-lg bg-raised px-3 py-2">
					<p class="min-w-0 flex-1 truncate text-sm">{folder.name}</p>
					<button class="text-xs font-medium text-muted hover:text-white" onclick={() => removeMusicFolder(folder.id)}>Remove</button>
				</div>
			{/each}
			<button class="self-start rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={addMusicFolder}>Add a folder</button>
		</div>
	{/if}
	<div class="flex items-center gap-4">
		<div class="min-w-0 flex-1">
			<p class="text-sm font-medium">Remember where you were</p>
			<p class="text-xs text-muted">The Local tab opens where you left it: the same music, tab, search and place in the list, also after closing the app. Off: it opens at the start.</p>
		</div>
		<Switch checked={localPlace.remember} label="Remember where you were" onchange={setLocalRemember} />
	</div>
	<p class="text-xs text-muted">The Local tab shows up when there's music to show. Saved on this {nativeBridge ? 'phone' : 'device'}.</p>
</section>

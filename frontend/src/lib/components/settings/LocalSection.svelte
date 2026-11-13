<script lang="ts">
	// Settings → Local music: which music the Local tab shows.
	import { local, localPlace, addMusicFolder, loadLocal, removeMusicFolder, setMediaScan, setLocalNetwork, setLocalRemember, setPhoneMusic } from '../../local.svelte';
	import { nativeBridge } from '../../native';
	import { addComputerFolder, changeComputer, computer, loadComputer, onComputer, removeComputerFolder, rescanComputer } from '../../computer.svelte';
	import { onMount } from 'svelte';
	import { settings } from '../../settings.svelte';
	import Switch from './Switch.svelte';

	let { onToast }: { onToast: (text: string) => void } = $props();

	let busy = $state(false);

	const desktop = onComputer();
	onMount(() => {
		if (desktop) void loadComputer();
	});

	/** This computer's music changed: the Local tab follows. */
	async function mine(work: () => Promise<void>) {
		busy = true;
		try {
			await work();
			await loadLocal(true);
		} catch (e) {
			onToast(`This app's module didn't do it: ${(e as Error).message}`);
		} finally {
			busy = false;
		}
	}

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
		<Switch checked={settings.localNetwork.value} label="Music on the network" onchange={setLocalNetwork} />
	</div>
	{#if desktop}
		<div class="flex items-center gap-4">
			<div class="min-w-0 flex-1">
				<p class="text-sm font-medium">Music on this computer</p>
				<p class="text-xs text-muted">
					The songs in the folders below, in the Local tab. They play from this computer. Only when someone else in the room plays along (or a speaker does) is a song sent to the
					server for them; alone, nothing leaves this computer.
				</p>
			</div>
			<Switch
				checked={!!computer.status?.music}
				label="Music on this computer"
				disabled={busy || !computer.status}
				onchange={(on) => mine(() => (on && !computer.status?.folders.length ? addComputerFolder() : changeComputer({ music: on })))}
			/>
		</div>
		{#if computer.status?.music || computer.status?.folders.length}
			<div class="flex flex-col gap-2">
				<div>
					<p class="text-sm font-medium">Music folders</p>
					<p class="text-xs text-muted">
						{computer.status?.scanning ? 'Looking through them…' : `${computer.status?.musicSongs ?? 0} songs.`} New and changed files are found when the Local tab opens (at most every 10 minutes), or
						with Look again.
					</p>
				</div>
				{#each computer.status?.folders ?? [] as folder (folder.path)}
					<div class="flex items-center gap-3 rounded-lg bg-raised px-3 py-2">
						<p class="min-w-0 flex-1 truncate text-sm" title={folder.path}>{folder.path}</p>
						<button class="text-xs font-medium text-muted hover:text-white" disabled={busy} onclick={() => mine(() => removeComputerFolder(folder.path))}>Remove</button>
					</div>
				{/each}
				<div class="flex gap-2">
					<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" disabled={busy} onclick={() => mine(addComputerFolder)}>Add a folder</button>
					{#if computer.status?.folders.length}
						<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" disabled={busy} onclick={() => mine(rescanComputer)}>Look again</button>
					{/if}
				</div>
			</div>
		{/if}
	{/if}
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
	<p class="text-xs text-muted">The Local tab shows up when there's music to show.</p>
</section>

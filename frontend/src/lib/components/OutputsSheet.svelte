<script lang="ts">
	import { wheelAdjust } from '../wheel';
	// "Play on": this device (with its sync adjustment and volume) and the speakers and TVs the host found.
	import { MediaQuery } from 'svelte/reactivity';
	import { castScreen, unlinkScreen } from '../api';
	import { nativeBridge, type CastScreenState, type NearbyState } from '../native';
	import type { RoomPlayer } from '../player.svelte';
	import type { RoomConnection } from '../room.svelte';
	import { saved } from '../storage';
	import Icon from './Icon.svelte';

	let {
		room,
		player,
		onClose,
		onToast
	}: { room: RoomConnection; player: RoomPlayer; onClose: () => void; onToast: (text: string) => void } = $props();

	const outputs = $derived(room.state?.outputs ?? []);
	const phone = new MediaQuery('max-width: 639px');

	async function toggle(outputId: string, active: boolean, screen = false) {
		const error = await room.run({ kind: 'SetOutput', outputId, active, screen });
		if (error) onToast(error.message);
	}

	function setVolume(outputId: string, volume: number) {
		room.run({ kind: 'SetOutputVolume', outputId, volume });
	}

	// Your other devices (the same account) that play this room: their volume, from here
	// (docs/features/playback-and-outputs.md#your-devices).
	const myDevices = $derived.by(() => {
		const me = room.me;
		if (!me?.accountId) return [];
		return (room.state?.participants ?? []).filter((p) => p.id !== me.id && p.accountId === me.accountId && !p.screen && p.online && p.listening);
	});

	async function setDeviceVolume(participantId: string, volume: number) {
		const error = await room.run({ kind: 'SetDeviceVolume', volume, participantId });
		if (error) onToast(error.message);
	}

	// Speakers and TVs on this phone's own network that the host can't reach (a room on a
	// server): the app plays the room there itself, getting the audio from the phone, and a TV
	// can show the party screen instead (docs/features/playback-and-outputs.md#host-outputs).
	// Older apps (bridge 7) only know the TVs, for the party screen.
	function readNearby(): NearbyState | null {
		try {
			if (nativeBridge?.nearby) return JSON.parse(nativeBridge.nearby()) as NearbyState;
			if (nativeBridge?.castScreens) {
				const old = JSON.parse(nativeBridge.castScreens()) as CastScreenState;
				return { devices: old.devices.map((d) => ({ ...d, kind: 'chromecast' as const })), shown: old.shown, playing: null };
			}
		} catch {
			// No app, or an app that doesn't know.
		}
		return null;
	}
	let near = $state(readNearby());
	const canPlayNear = !!nativeBridge?.playNearby;
	const nearDevices = $derived(
		room.can('listen_locally')
			? (near?.devices ?? []).filter((d) => !outputs.some((o) => o.id === d.id) && (canPlayNear || d.kind === 'chromecast'))
			: []
	);
	const ourPage = $derived(`${location.origin}/tv#cast=${encodeURIComponent(room.roomCode)}.`);
	let starting = $state<string | null>(null);
	const answers = new Map<string, (error: string | null) => void>();

	$effect(() => {
		if (!near) return;
		window.__ytmpNative = {
			...window.__ytmpNative,
			onNearby: (state) => (near = state),
			onCastScreens: (state) => {
				if (!canPlayNear) near = { devices: state.devices.map((d) => ({ ...d, kind: 'chromecast' })), shown: state.shown, playing: null };
			},
			onCastScreenShown: (deviceId, error) => {
				answers.get(deviceId)?.(error);
				answers.delete(deviceId);
			}
		};
		return () => {
			window.__ytmpNative = { ...window.__ytmpNative, onNearby: undefined, onCastScreens: undefined, onCastScreenShown: undefined };
			for (const answer of answers.values()) answer(null);
			answers.clear();
		};
	});

	function shownHere(deviceId: string) {
		return near?.shown.find((s) => s.deviceId === deviceId) ?? null;
	}

	/** The speaker plays a room from this phone: this one (with its volume), or another. */
	function playingOn(deviceId: string) {
		const speaker = near?.playing?.speakers.find((s) => s.id === deviceId);
		return speaker ? { here: near?.playing?.roomCode === room.roomCode, volume: speaker.volume } : null;
	}

	function playNear(device: { id: string }, on: boolean) {
		if (!on) {
			nativeBridge?.stopNearby?.(device.id);
			return;
		}
		const guestToken = saved.guestToken(room.roomCode);
		if (!guestToken) return;
		// A TV does one thing at a time: the music here instead of the party screen.
		if (shownHere(device.id)) nativeBridge?.stopCastScreen?.(device.id);
		nativeBridge?.playNearby?.(device.id, JSON.stringify({ hostUrl: location.origin, code: room.roomCode, guestToken }));
	}

	async function showOnTv(device: { id: string; name: string }, on: boolean) {
		if (!on) {
			nativeBridge?.stopCastScreen?.(device.id);
			return;
		}
		const guestToken = saved.guestToken(room.roomCode);
		if (!guestToken || !nativeBridge?.showCastScreen) return;
		// The party screen plays the music itself.
		if (playingOn(device.id)) nativeBridge?.stopNearby?.(device.id);
		starting = device.id;
		try {
			const screen = await castScreen(room.roomCode, guestToken, device.name);
			const url = `${location.origin}/tv#cast=${encodeURIComponent(screen.roomCode)}.${encodeURIComponent(screen.guestToken)}`;
			const error = await new Promise<string | null>((resolve) => {
				answers.set(device.id, resolve);
				nativeBridge?.showCastScreen?.(device.id, url);
			});
			if (error) {
				onToast(`${device.name} didn't show it: ${error}`);
				unlinkScreen(screen.roomCode, screen.guestToken).catch(() => {});
			}
		} catch (e) {
			onToast(e instanceof Error ? e.message : String(e));
		} finally {
			starting = null;
		}
	}

	let showSync = $state(false);
	const SYNC_RANGE_MS = 500;

	function nudgeSync(deltaMs: number) {
		player.setSyncOffset(Math.max(-SYNC_RANGE_MS, Math.min(SYNC_RANGE_MS, player.syncOffsetMs + deltaMs)));
	}
</script>

<div class="fixed inset-0 z-50 flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={onClose}>
	<div
		class="flex max-h-[85vh] w-full max-w-sm flex-col gap-3 overflow-y-auto rounded-t-2xl bg-surface p-6 ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label="Play on"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		onkeydown={(e) => e.key === 'Escape' && onClose()}
	>
		<div class="flex items-center justify-between">
			<h2 class="text-lg font-bold">Play on</h2>
			<button class="rounded-full p-1 text-muted hover:bg-raised" aria-label="Close" onclick={onClose}><Icon name="close" /></button>
		</div>

		<div class="flex flex-col gap-2 rounded-xl bg-raised p-3">
			<label class="flex items-center gap-3" data-listen-toggle>
				<Icon name="headphones" size={20} class={player.enabled ? 'text-accent' : 'text-muted'} />
				<span class="min-w-0 flex-1 truncate">{phone.current ? 'This phone' : 'This device'}</span>
				<input
					type="checkbox"
					class="h-5 w-5 accent-accent"
					checked={player.enabled}
					onchange={(e) => (e.currentTarget.checked ? player.enable() : player.disable())}
				/>
			</label>
			{#if player.enabled}
				{#if !phone.current}
					<input
						type="range"
						class="w-full accent-white"
						min="0"
						max="1"
						step="0.01"
						value={player.volume}
						aria-label="Volume"
						use:wheelAdjust
						oninput={(e) => player.setVolume(Number(e.currentTarget.value))}
					/>
				{/if}
				<button class="flex items-center gap-2 self-start text-sm text-muted hover:text-white" onclick={() => (showSync = !showSync)}>
					<Icon name="tune" size={16} />
					Sync adjustment: {player.syncOffsetMs === 0 ? 'none' : `${Math.abs(player.syncOffsetMs)} ms ${player.syncOffsetMs > 0 ? 'earlier' : 'later'}`}
				</button>
				{#if showSync}
					<div class="flex items-center gap-2 text-sm">
						<button class="rounded-full bg-surface px-3 py-1 hover:bg-line" onclick={() => nudgeSync(-10)}>Later</button>
						<input
							type="range"
							class="min-w-0 flex-1 accent-white"
							min={-SYNC_RANGE_MS}
							max={SYNC_RANGE_MS}
							step="10"
							value={player.syncOffsetMs}
							aria-label="Sync adjustment in milliseconds"
							use:wheelAdjust={10}
							oninput={(e) => player.setSyncOffset(Number(e.currentTarget.value))}
						/>
						<button class="rounded-full bg-surface px-3 py-1 hover:bg-line" onclick={() => nudgeSync(10)}>Earlier</button>
					</div>
					<p class="text-xs text-muted">
						Is this device behind the others (for example on Bluetooth)? Move it to <em>earlier</em>. Saved on this device.
						{#if player.syncOffsetMs !== 0}
							<button class="underline" onclick={() => player.setSyncOffset(0)}>Reset</button>
						{/if}
					</p>
				{/if}
			{/if}
		</div>

		{#if myDevices.length > 0}
			<p class="-mb-1 mt-1 text-xs font-medium text-muted">Your other devices</p>
		{/if}
		{#each myDevices as device (device.id)}
			{@const name = device.device || 'Another device'}
			<div class="flex flex-col gap-2 rounded-xl bg-raised p-3">
				<div class="flex items-center gap-3">
					<Icon name={/ on (Windows|Mac|Linux|ChromeOS)$/.test(name) ? 'computer' : 'phone'} size={20} class="text-accent" />
					<span class="min-w-0 flex-1 truncate">{name}</span>
				</div>
				{#if device.volume !== null}
					<input
						type="range"
						class="w-full accent-white"
						min="0"
						max="1"
						step="0.02"
						value={device.volume}
						aria-label="Volume of {name}"
						use:wheelAdjust
						onchange={(e) => setDeviceVolume(device.id, Number(e.currentTarget.value))}
					/>
				{:else}
					<p class="pl-8 text-xs text-muted">Its volume can only be changed there (update YTMP on it).</p>
				{/if}
			</div>
		{/each}

		{#each outputs as output (output.id)}
			<div class="flex flex-col gap-2 rounded-xl bg-raised p-3">
				<label class="flex items-center gap-3">
					<Icon name={output.kind === 'chromecast' ? 'cast' : 'speaker'} size={20} class={output.active ? 'text-accent' : 'text-muted'} />
					<span class="min-w-0 flex-1 truncate">{output.name}</span>
					<input
						type="checkbox"
						class="h-5 w-5 accent-accent"
						checked={output.active}
						disabled={!room.can('change_outputs')}
						onchange={(e) => toggle(output.id, e.currentTarget.checked, false)}
					/>
				</label>
				{#if output.kind === 'chromecast'}
					<!-- TVs and displays: the party screen, which plays the music too (docs/features/ui.md#party-screen). -->
					<label class="flex items-center gap-3 pl-8 text-sm">
						<span class="min-w-0 flex-1 {output.screen ? 'text-white' : 'text-muted'}">Show the party screen (TVs and displays)</span>
						<input
							type="checkbox"
							class="h-4 w-4 accent-accent"
							checked={output.screen}
							disabled={!room.can('change_outputs')}
							onchange={(e) => toggle(output.id, e.currentTarget.checked || output.active, e.currentTarget.checked)}
						/>
					</label>
				{/if}
				{#if output.active && output.volume !== null}
					<input
						type="range"
						class="w-full accent-white"
						min="0"
						max="1"
						step="0.02"
						value={output.volume}
						disabled={!room.can('output_volume')}
						aria-label="Volume of {output.name}"
						use:wheelAdjust
						onchange={(e) => setVolume(output.id, Number(e.currentTarget.value))}
					/>
				{/if}
			</div>
		{/each}
		{#if nearDevices.length > 0}
			<p class="-mb-1 mt-1 text-xs font-medium text-muted">Near this phone (it plays from here)</p>
		{/if}
		{#each nearDevices as device (device.id)}
			{@const shown = shownHere(device.id)}
			{@const showsHere = !!shown?.url.startsWith(ourPage)}
			{@const plays = playingOn(device.id)}
			<div class="flex flex-col gap-2 rounded-xl bg-raised p-3">
				{#if canPlayNear}
					<label class="flex items-center gap-3">
						<Icon name={device.kind === 'chromecast' ? 'cast' : 'speaker'} size={20} class={plays?.here || showsHere ? 'text-accent' : 'text-muted'} />
						<span class="min-w-0 flex-1 truncate">{device.name}</span>
						<input
							type="checkbox"
							class="h-5 w-5 accent-accent"
							checked={!!plays?.here}
							disabled={starting === device.id}
							onchange={(e) => playNear(device, e.currentTarget.checked)}
						/>
					</label>
				{/if}
				{#if device.kind === 'chromecast'}
					<label class="flex items-center gap-3 {canPlayNear ? 'pl-8 text-sm' : ''}">
						{#if !canPlayNear}<Icon name="tv" size={20} class={showsHere ? 'text-accent' : 'text-muted'} />{/if}
						<span class="min-w-0 flex-1 {canPlayNear ? (showsHere ? 'text-white' : 'text-muted') : 'truncate'}">
							{canPlayNear ? 'Show the party screen (TVs and displays)' : device.name}
						</span>
						<input
							type="checkbox"
							class={canPlayNear ? 'h-4 w-4 accent-accent' : 'h-5 w-5 accent-accent'}
							checked={showsHere}
							disabled={starting !== null}
							onchange={(e) => showOnTv(device, e.currentTarget.checked)}
						/>
					</label>
				{/if}
				{#if plays?.here && plays.volume !== null}
					<input
						type="range"
						class="w-full accent-white"
						min="0"
						max="1"
						step="0.02"
						value={plays.volume}
						aria-label="Volume of {device.name}"
						use:wheelAdjust
						onchange={(e) => nativeBridge?.setNearbyVolume?.(device.id, Number(e.currentTarget.value))}
					/>
				{/if}
				{#if starting === device.id}
					<p class="pl-8 text-xs text-muted">Starting on the TV…</p>
				{:else if (plays && !plays.here) || (shown && !showsHere)}
					<p class="pl-8 text-xs text-muted">Plays another room now. Switch on for this one.</p>
				{:else if !canPlayNear}
					<p class="pl-8 text-xs text-muted">Shows the party screen and plays the music. Found by this phone.</p>
				{/if}
			</div>
		{/each}
		{#if outputs.length === 0 && nearDevices.length === 0}
			<p class="text-sm text-muted">
				No speakers or TVs found. Chromecasts (and speakers with Chromecast built in) and Sonos speakers on the host's network show up here.
			</p>
		{/if}
		<p class="text-xs text-muted">Speakers and TVs play for everyone in the room. This device only plays for you.</p>
	</div>
</div>

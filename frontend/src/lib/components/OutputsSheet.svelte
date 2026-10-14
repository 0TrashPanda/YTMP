<script lang="ts">
	import { wheelAdjust } from '../wheel';
	// "Play on": this device (with its sync adjustment and volume) and the speakers and TVs the host found.
	import { MediaQuery } from 'svelte/reactivity';
	import { castScreen, unlinkScreen } from '../api';
	import { nativeBridge, type CastScreenState } from '../native';
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

	// TVs on this phone's own network that the host can't reach (a room on a server): the app
	// shows the party screen there itself, which plays the music too (docs/features/ui.md#party-screen).
	function readPhoneCasts(): CastScreenState | null {
		try {
			return nativeBridge?.castScreens ? (JSON.parse(nativeBridge.castScreens()) as CastScreenState) : null;
		} catch {
			return null;
		}
	}
	let phoneCasts = $state(readPhoneCasts());
	const phoneTvs = $derived(
		room.can('listen_locally') ? (phoneCasts?.devices ?? []).filter((d) => !outputs.some((o) => o.id === d.id)) : []
	);
	const ourPage = $derived(`${location.origin}/tv#cast=${encodeURIComponent(room.roomCode)}.`);
	let starting = $state<string | null>(null);
	const answers = new Map<string, (error: string | null) => void>();

	$effect(() => {
		if (!nativeBridge?.castScreens) return;
		window.__ytmpNative = {
			...window.__ytmpNative,
			onCastScreens: (state) => (phoneCasts = state),
			onCastScreenShown: (deviceId, error) => {
				answers.get(deviceId)?.(error);
				answers.delete(deviceId);
			}
		};
		return () => {
			window.__ytmpNative = { ...window.__ytmpNative, onCastScreens: undefined, onCastScreenShown: undefined };
			for (const answer of answers.values()) answer(null);
			answers.clear();
		};
	});

	function shownHere(deviceId: string) {
		return phoneCasts?.shown.find((s) => s.deviceId === deviceId) ?? null;
	}

	async function showOnTv(device: { id: string; name: string }, on: boolean) {
		if (!on) {
			nativeBridge?.stopCastScreen?.(device.id);
			return;
		}
		const guestToken = saved.guestToken(room.roomCode);
		if (!guestToken || !nativeBridge?.showCastScreen) return;
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

		{#each outputs as output (output.id)}
			<div class="flex flex-col gap-2 rounded-xl bg-raised p-3">
				<label class="flex items-center gap-3">
					<Icon name={output.kind === 'sonos' ? 'speaker' : 'cast'} size={20} class={output.active ? 'text-accent' : 'text-muted'} />
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
		{#each phoneTvs as tv (tv.id)}
			{@const shown = shownHere(tv.id)}
			{@const here = !!shown?.url.startsWith(ourPage)}
			<div class="flex flex-col gap-1 rounded-xl bg-raised p-3">
				<label class="flex items-center gap-3">
					<Icon name="tv" size={20} class={here ? 'text-accent' : 'text-muted'} />
					<span class="min-w-0 flex-1 truncate">{tv.name}</span>
					<input
						type="checkbox"
						class="h-5 w-5 accent-accent"
						checked={here}
						disabled={starting !== null}
						onchange={(e) => showOnTv(tv, e.currentTarget.checked)}
					/>
				</label>
				<p class="pl-8 text-xs text-muted">
					{#if starting === tv.id}
						Starting on the TV…
					{:else if shown && !here}
						Shows another room now. Switch on to show this one.
					{:else}
						Shows the party screen and plays the music. Found by this phone.
					{/if}
				</p>
			</div>
		{/each}
		{#if outputs.length === 0 && phoneTvs.length === 0}
			<p class="text-sm text-muted">
				No speakers or TVs found. Chromecasts (and speakers with Chromecast built in) and Sonos speakers on the host's network show up here.
			</p>
		{/if}
		<p class="text-xs text-muted">Speakers and TVs play for everyone in the room. This device only plays for you.</p>
	</div>
</div>

<script lang="ts">
	// "Play on": this device (with its sync adjustment and volume) and the speakers and TVs the host found.
	import { MediaQuery } from 'svelte/reactivity';
	import type { RoomPlayer } from '../player.svelte';
	import type { RoomConnection } from '../room.svelte';
	import Icon from './Icon.svelte';

	let {
		room,
		player,
		onClose,
		onToast
	}: { room: RoomConnection; player: RoomPlayer; onClose: () => void; onToast: (text: string) => void } = $props();

	const outputs = $derived(room.state?.outputs ?? []);
	const phone = new MediaQuery('max-width: 639px');

	async function toggle(outputId: string, active: boolean) {
		const error = await room.run({ kind: 'SetOutput', outputId, active });
		if (error) onToast(error.message);
	}

	function setVolume(outputId: string, volume: number) {
		room.run({ kind: 'SetOutputVolume', outputId, volume });
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
			<label class="flex items-center gap-3">
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
						onchange={(e) => toggle(output.id, e.currentTarget.checked)}
					/>
				</label>
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
						onchange={(e) => setVolume(output.id, Number(e.currentTarget.value))}
					/>
				{/if}
			</div>
		{:else}
			<p class="text-sm text-muted">
				No speakers or TVs found. Chromecasts (and speakers with Chromecast built in) and Sonos speakers on the host's network show up here.
			</p>
		{/each}
		<p class="text-xs text-muted">Speakers and TVs play for everyone in the room. This device only plays for you.</p>
	</div>
</div>

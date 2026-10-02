<script lang="ts">
	import { artistNames, formatTime } from '../format';
	import type { RoomPlayer } from '../player.svelte';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import OutputsSheet from './OutputsSheet.svelte';

	let {
		room,
		player,
		positionMs,
		onToast
	}: { room: RoomConnection; player: RoomPlayer; positionMs: number; onToast: (text: string) => void } = $props();

	let showOutputs = $state(false);
	const activeOutputs = $derived(room.state?.outputs.filter((o) => o.active).length ?? 0);

	const current = $derived(room.state?.nowPlaying ?? null);
	const playback = $derived(room.state?.playback);
	const duration = $derived(current?.item.song.durationMs ?? 0);
	const loading = $derived(current !== null && current.streamUrl === null);

	// While dragging the progress bar, show the drag position instead of the live one.
	let seeking = $state<number | null>(null);
	const shown = $derived(seeking ?? Math.min(positionMs, duration));

	let showSync = $state(false);
	const SYNC_RANGE_MS = 500;

	function nudgeSync(deltaMs: number) {
		player.setSyncOffset(Math.max(-SYNC_RANGE_MS, Math.min(SYNC_RANGE_MS, player.syncOffsetMs + deltaMs)));
	}
</script>

<footer class="border-t border-line bg-surface">
	<input
		type="range"
		class="block h-1 w-full cursor-pointer appearance-none bg-line accent-accent"
		min="0"
		max={duration || 1}
		value={shown}
		disabled={!current}
		aria-label="Position"
		oninput={(e) => (seeking = Number(e.currentTarget.value))}
		onchange={(e) => {
			room.run({ kind: 'Seek', positionMs: Number(e.currentTarget.value) });
			seeking = null;
		}}
		style="background: linear-gradient(to right, var(--color-accent) {(shown / (duration || 1)) * 100}%, var(--color-line) 0)"
	/>
	<div class="flex h-18 items-center gap-2 px-2 sm:gap-4 sm:px-4">
		<div class="flex items-center">
			<button class="rounded-full p-2 hover:bg-raised" aria-label="Previous" onclick={() => room.run({ kind: 'Previous' })}>
				<Icon name="previous" />
			</button>
			<button
				class="rounded-full p-2 hover:bg-raised"
				aria-label={playback?.playing ? 'Pause' : 'Play'}
				onclick={() => room.run({ kind: playback?.playing ? 'Pause' : 'Play' })}
			>
				<Icon name={playback?.playing ? 'pause' : 'play'} size={36} />
			</button>
			<button class="rounded-full p-2 hover:bg-raised" aria-label="Next" onclick={() => room.run({ kind: 'Skip' })}>
				<Icon name="next" />
			</button>
			<span class="hidden pl-2 text-xs text-muted tabular-nums sm:inline">
				{formatTime(shown)} / {formatTime(duration)}
			</span>
		</div>

		<div class="flex min-w-0 flex-1 items-center gap-3">
			<Art song={current?.item.song ?? null} size={48} class="h-10 w-10 sm:h-12 sm:w-12" />
			<div class="min-w-0">
				<div class="truncate font-medium">{current?.item.song.title ?? 'Nothing playing'}</div>
				<div class="truncate text-sm text-muted">
					{#if current}
						{artistNames(current.item.song)}{loading ? ' • loading…' : player.buffering ? ' • buffering…' : ''}
					{/if}
				</div>
			</div>
		</div>

		<div class="flex items-center gap-2">
			<button
				class="rounded-full p-2 hover:bg-raised {activeOutputs ? 'text-accent' : ''}"
				aria-label="Speakers and TVs"
				title={activeOutputs ? `Playing on ${activeOutputs} speaker(s)` : 'Play on a speaker or TV'}
				onclick={() => (showOutputs = true)}
			>
				<Icon name="cast" size={20} />
			</button>
			{#if player.enabled}
				<button
					class="rounded-full p-2 hover:bg-raised {showSync ? 'bg-raised' : ''}"
					aria-label="Sync adjustment"
					title="Sync adjustment"
					onclick={() => (showSync = !showSync)}
				>
					<Icon name="tune" size={20} />
				</button>
				<input
					type="range"
					class="hidden w-24 accent-white sm:block"
					min="0"
					max="1"
					step="0.01"
					value={player.volume}
					aria-label="Volume"
					oninput={(e) => player.setVolume(Number(e.currentTarget.value))}
				/>
			{/if}
			<button
				class="flex items-center gap-2 rounded-full px-3 py-2 text-sm font-medium
					{player.enabled ? 'bg-white text-black' : 'bg-raised hover:bg-line'}"
				onclick={() => (player.enabled ? player.disable() : player.enable())}
				title="Play the music on this device"
			>
				<Icon name="headphones" size={20} />
				<span class="hidden sm:inline">{player.enabled ? 'Playing here' : 'Play here'}</span>
			</button>
		</div>
	</div>
	{#if player.enabled && showSync}
		<div class="flex flex-col gap-2 border-t border-line px-4 py-3 text-sm">
			<div class="flex items-center justify-between">
				<span class="font-medium">Sync adjustment</span>
				<span class="text-muted tabular-nums">
					{player.syncOffsetMs === 0 ? 'none' : `${Math.abs(player.syncOffsetMs)} ms ${player.syncOffsetMs > 0 ? 'earlier' : 'later'}`}
				</span>
			</div>
			<div class="flex items-center gap-2">
				<button class="rounded-full bg-raised px-3 py-1 hover:bg-line" onclick={() => nudgeSync(-10)}>Later</button>
				<input
					type="range"
					class="flex-1 accent-white"
					min={-SYNC_RANGE_MS}
					max={SYNC_RANGE_MS}
					step="10"
					value={player.syncOffsetMs}
					aria-label="Sync adjustment in milliseconds"
					oninput={(e) => player.setSyncOffset(Number(e.currentTarget.value))}
				/>
				<button class="rounded-full bg-raised px-3 py-1 hover:bg-line" onclick={() => nudgeSync(10)}>Earlier</button>
			</div>
			<p class="text-xs text-muted">
				Is this device behind the others (for example on Bluetooth)? Move it to <em>earlier</em>. Saved on this device.
				{#if player.syncOffsetMs !== 0}
					<button class="underline" onclick={() => player.setSyncOffset(0)}>Reset</button>
				{/if}
			</p>
		</div>
	{/if}
	{#if showOutputs}
		<OutputsSheet {room} onClose={() => (showOutputs = false)} {onToast} />
	{/if}
	{#if player.error}
		<p class="px-4 pb-2 text-sm text-accent">{player.error}</p>
	{/if}
</footer>

<script lang="ts">
	import { artistNames, formatTime } from '../format';
	import type { RoomPlayer } from '../player.svelte';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import OutputsSheet from './OutputsSheet.svelte';
	import Progress from './Progress.svelte';
	import SkipButtons from './SkipButtons.svelte';

	let {
		room,
		player,
		positionMs,
		expanded,
		onExpand,
		onToast,
		onSongMenu
	}: {
		room: RoomConnection;
		player: RoomPlayer;
		positionMs: number;
		/** The full player is open. */
		expanded: boolean;
		/** Open (true) or close the full player. */
		onExpand: (open: boolean) => void;
		onToast: (text: string) => void;
		/** Right-click or ⋮ on the current song. */
		onSongMenu: (event: MouseEvent) => void;
	} = $props();

	let showOutputs = $state(false);
	const activeOutputs = $derived(room.state?.outputs.filter((o) => o.active).length ?? 0);

	const current = $derived(room.state?.nowPlaying ?? null);
	const playback = $derived(room.state?.playback);
	const duration = $derived(current?.item.song.durationMs ?? 0);
	const loading = $derived(current !== null && current.streamUrl === null);
	const status = $derived(loading ? ' • loading…' : player.buffering ? ' • buffering…' : '');

	// Phones: swipe the mini player up to open the full player (a tap opens it too).
	let swipeStart: number | null = null;
</script>

<footer class="relative z-40 border-line bg-surface sm:border-t">
	<!-- Phones: YTM's mini player. The song (tap or swipe up for the full player), play/pause, next. -->
	<div
		class="sm:hidden"
		role="presentation"
		ontouchstart={(e) => (swipeStart = e.touches[0].clientY)}
		ontouchmove={(e) => {
			if (swipeStart !== null && swipeStart - e.touches[0].clientY > 30) {
				swipeStart = null;
				if (current) onExpand(true);
			}
		}}
		ontouchend={() => (swipeStart = null)}
	>
		<div class="flex h-16 items-center gap-1 pr-1 pl-2">
			<button class="flex min-w-0 flex-1 items-center gap-2.5 text-left" disabled={!current} onclick={() => onExpand(true)} oncontextmenu={onSongMenu}>
				<Art song={current?.item.song ?? null} size={96} class="h-10 w-10 shrink-0" />
				<div class="min-w-0 flex-1">
					<div class="truncate text-sm font-medium">{current?.item.song.title ?? 'Nothing playing'}</div>
					<div class="truncate text-xs text-muted">
						{#if current}{artistNames(current.item.song)}{status}{/if}
					</div>
				</div>
			</button>
			<button
				class="shrink-0 rounded-full p-1.5 disabled:opacity-40"
				disabled={!room.can('play_pause')}
				aria-label={playback?.playing ? 'Pause' : 'Play'}
				onclick={() => room.run({ kind: playback?.playing ? 'Pause' : 'Play' })}
			>
				<Icon name={playback?.playing ? 'pause' : 'play'} size={32} />
			</button>
			<button class="shrink-0 rounded-full p-1.5 disabled:opacity-40" aria-label="Next" disabled={!room.can('skip')} onclick={() => room.run({ kind: 'Skip' })}>
				<Icon name="next" size={26} />
			</button>
		</div>
		<!-- At the bottom, between the mini player and the bottom bar, like YTM. -->
		<div class="h-0.5 bg-white/15">
			<div class="h-full bg-accent" style:width="{duration ? Math.min(100, (positionMs / duration) * 100) : 0}%"></div>
		</div>
	</div>

	<!-- Larger screens: controls left, the song in the middle (centered), outputs and "Play here" right. -->
	<div class="hidden sm:block">
		<Progress {room} {positionMs} />
		<div class="grid h-18 grid-cols-[1fr_minmax(0,auto)_1fr] items-center gap-4 px-4">
			<div class="flex items-center justify-self-start">
				<button class="rounded-full p-2 hover:bg-raised disabled:opacity-40" aria-label="Previous" disabled={!room.can('skip')} onclick={() => room.run({ kind: 'Previous' })}>
					<Icon name="previous" />
				</button>
				{#snippet playPause()}
					<button
						class="rounded-full p-2 hover:bg-raised disabled:opacity-40"
						disabled={!room.can('play_pause')}
						aria-label={playback?.playing ? 'Pause' : 'Play'}
						onclick={() => room.run({ kind: playback?.playing ? 'Pause' : 'Play' })}
					>
						<Icon name={playback?.playing ? 'pause' : 'play'} size={36} />
					</button>
				{/snippet}
				{#if current?.item.song.podcast}
					<!-- Episodes: back 10 s and ahead 30 s around play, like YTM. -->
					<SkipButtons {room} {positionMs}>{@render playPause()}</SkipButtons>
				{:else}
					{@render playPause()}
				{/if}
				<button class="rounded-full p-2 hover:bg-raised disabled:opacity-40" aria-label="Next" disabled={!room.can('skip')} onclick={() => room.run({ kind: 'Skip' })}>
					<Icon name="next" />
				</button>
				<span class="pl-2 text-xs text-muted tabular-nums">
					{formatTime(Math.min(positionMs, duration))} / {formatTime(duration)}
				</span>
			</div>

			<div class="flex max-w-xl min-w-0 items-center gap-3 justify-self-center" role="presentation" oncontextmenu={onSongMenu}>
				<button class="flex min-w-0 items-center gap-3 text-left" disabled={!current} title={expanded ? undefined : 'Open the player'} onclick={() => onExpand(!expanded)}>
					<Art song={current?.item.song ?? null} size={96} class="h-12 w-12" />
					<div class="min-w-0">
						<div class="truncate font-medium">{current?.item.song.title ?? 'Nothing playing'}</div>
						<div class="truncate text-sm text-muted">
							{#if current}{artistNames(current.item.song)}{status}{/if}
						</div>
					</div>
				</button>
				{#if current}
					<button
						class="shrink-0 rounded-full p-1.5 text-muted hover:bg-raised hover:text-white"
						aria-label="More for {current.item.song.title}"
						onclick={(e) => {
							const r = e.currentTarget.getBoundingClientRect();
							onSongMenu(new MouseEvent('contextmenu', { clientX: r.left, clientY: r.top - 8 }));
						}}
					>
						<Icon name="more" size={20} />
					</button>
				{/if}
			</div>

			<div class="flex items-center gap-2 justify-self-end">
				{#if player.enabled}
					<input
						type="range"
						class="hidden w-24 accent-white md:block"
						min="0"
						max="1"
						step="0.01"
						value={player.volume}
						aria-label="Volume"
						oninput={(e) => player.setVolume(Number(e.currentTarget.value))}
					/>
				{/if}
				<button
					class="rounded-full p-2 hover:bg-raised {activeOutputs ? 'text-accent' : ''}"
					aria-label="Play on"
					title={activeOutputs ? `Playing on ${activeOutputs} speaker(s)` : 'Play on a speaker or TV'}
					onclick={() => (showOutputs = true)}
				>
					<Icon name="cast" size={20} />
				</button>
				<button
					data-listen-toggle
					class="flex items-center gap-2 rounded-full px-3 py-2 text-sm font-medium
						{player.enabled ? 'bg-white text-black' : 'bg-raised hover:bg-line'}"
					onclick={() => (player.enabled ? player.disable() : player.enable())}
					title="Play the music on this device"
				>
					<Icon name="headphones" size={20} />
					<span class="hidden lg:inline">{player.enabled ? 'Playing here' : 'Play here'}</span>
				</button>
				<button
					class="rounded-full p-2 hover:bg-raised disabled:opacity-40"
					disabled={!current && !expanded}
					aria-label={expanded ? 'Close the player' : 'Open the player'}
					onclick={() => onExpand(!expanded)}
				>
					<Icon name={expanded ? 'collapse' : 'expand'} />
				</button>
			</div>
		</div>
	</div>
	{#if showOutputs}
		<OutputsSheet {room} {player} onClose={() => (showOutputs = false)} {onToast} />
	{/if}
	{#if player.error}
		<p class="px-4 pb-2 text-sm text-accent">{player.error}</p>
	{/if}
</footer>

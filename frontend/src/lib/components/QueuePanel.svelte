<script lang="ts">
	import { tick } from 'svelte';
	import { artistNames, formatTime } from '../format';
	import type { QueueItem } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';

	let { room }: { room: RoomConnection } = $props();

	const ROW_HEIGHT = 64;

	const roomState = $derived(room.state);
	let list = $state<HTMLElement>();
	/** The item whose song menu is open. */
	let menuFor = $state<string | null>(null);
	const canRadio = $derived(room.can('start_radio') || room.can('autoplay_from_here'));

	// Dragging a song in "up next" by its handle.
	let drag = $state<{ itemId: string; from: number; startY: number; offset: number } | null>(null);
	const dropIndex = $derived(
		drag && roomState ? Math.max(0, Math.min(roomState.queue.length - 1, drag.from + Math.round(drag.offset / ROW_HEIGHT))) : -1
	);

	// Keep the current song in view, with the history above it (like YTM). Only where the
	// queue scrolls on its own (desktop); on phones it is part of the page and must not
	// pull the page along.
	$effect(() => {
		roomState?.nowPlaying?.item.itemId;
		tick().then(() => {
			if (!list || list.scrollHeight <= list.clientHeight) return;
			const current = list.querySelector<HTMLElement>('[data-current]');
			if (current) list.scrollTo({ top: current.offsetTop - list.clientHeight / 2, behavior: 'smooth' });
		});
	});

	function startDrag(event: PointerEvent, item: QueueItem, index: number) {
		event.preventDefault();
		(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
		drag = { itemId: item.itemId, from: index, startY: event.clientY, offset: 0 };
	}

	function moveDrag(event: PointerEvent) {
		if (drag) drag.offset = event.clientY - drag.startY;
	}

	function endDrag() {
		if (drag && dropIndex !== drag.from) room.run({ kind: 'MoveQueueItem', itemId: drag.itemId, toIndex: dropIndex });
		drag = null;
	}

	function shift(index: number): number {
		// Make room for the dragged song while it is moving.
		if (!drag || index === drag.from) return 0;
		if (drag.from < index && index <= dropIndex) return -ROW_HEIGHT;
		if (dropIndex <= index && index < drag.from) return ROW_HEIGHT;
		return 0;
	}
</script>

{#snippet row(item: QueueItem, variant: 'history' | 'current' | 'upcoming' | 'autoplay', index: number)}
	{@const dragging = drag?.itemId === item.itemId}
	<div
		class="relative flex h-16 items-center gap-3 rounded-md px-2 select-none
			{variant === 'current' ? 'bg-raised' : 'hover:bg-raised'}
			{variant === 'history' ? 'opacity-50' : ''}
			{dragging ? 'relative z-10 bg-line shadow-xl' : 'transition-transform'}"
		style:transform={variant === 'upcoming' ? `translateY(${dragging ? drag!.offset : shift(index)}px)` : undefined}
		data-current={variant === 'current' ? '' : undefined}
	>
		<button
			class="flex min-w-0 flex-1 items-center gap-3 text-left"
			disabled={variant === 'current' || !room.can('play_now')}
			onclick={() => room.run({ kind: 'JumpTo', itemId: item.itemId })}
			title={variant === 'current' || !room.can('play_now') ? undefined : 'Jump to this song'}
		>
			<Art song={item.song} size={40} class="h-10 w-10" />
			<div class="min-w-0 flex-1">
				<div class="truncate text-sm font-medium {variant === 'current' ? 'text-white' : ''}">{item.song.title}</div>
				<div class="truncate text-xs text-muted">
					{#if variant === 'autoplay'}
						{artistNames(item.song)}
					{:else}
						{artistNames(item.song)} • {item.origin === 'radio' ? 'radio by' : item.origin === 'autoplay' ? 'autoplay' : 'added by'}
						{item.origin === 'autoplay' ? '' : item.addedByName}{item.result === 'skipped' ? ' • skipped' : ''}
					{/if}
				</div>
			</div>
			<span class="text-xs text-muted tabular-nums">{formatTime(item.song.durationMs)}</span>
		</button>
		{#if canRadio}
			<button
				class="rounded-full p-1.5 text-muted hover:bg-line hover:text-white"
				aria-label="More for {item.song.title}"
				onclick={(e) => {
					e.stopPropagation();
					menuFor = menuFor === item.itemId ? null : item.itemId;
				}}
			>
				<Icon name="more" size={18} />
			</button>
		{/if}
		{#if menuFor === item.itemId}
			<div class="absolute top-14 right-2 z-20 w-56 overflow-hidden rounded-lg bg-raised py-1 shadow-xl ring-1 ring-line">
				{#if room.can('start_radio')}
					<button class="flex w-full items-center gap-3 px-4 py-2 text-left hover:bg-line" onclick={() => ((menuFor = null), room.run({ kind: 'StartRadio', song: item.song }))}>
						<Icon name="radio" size={20} /> Start radio
					</button>
				{/if}
				{#if room.can('autoplay_from_here')}
					<button class="flex w-full items-center gap-3 px-4 py-2 text-left hover:bg-line" onclick={() => ((menuFor = null), room.run({ kind: 'AutoplayFromHere', song: item.song }))}>
						<Icon name="autoplay" size={20} /> Autoplay from here
					</button>
				{/if}
			</div>
		{/if}
		{#if variant === 'upcoming' && room.can(item.addedBy === room.participantId ? 'remove_own' : 'remove_others')}
			<button
				class="rounded-full p-1.5 text-muted hover:bg-line hover:text-white"
				aria-label="Remove from queue"
				onclick={() => room.run({ kind: 'RemoveQueueItem', itemId: item.itemId })}
			>
				<Icon name="remove" size={18} />
			</button>
		{/if}
		{#if variant === 'upcoming' && room.can('reorder')}
			<button
				class="cursor-grab touch-none rounded-full p-1.5 text-muted hover:text-white active:cursor-grabbing"
				aria-label="Drag to move"
				onpointerdown={(e) => startDrag(e, item, index)}
				onpointermove={moveDrag}
				onpointerup={endDrag}
				onpointercancel={() => (drag = null)}
			>
				<Icon name="drag" size={18} />
			</button>
		{/if}
	</div>
{/snippet}

<svelte:window onclick={() => (menuFor = null)} />

<aside class="flex flex-col lg:h-full lg:min-h-0">
	<h2 class="px-4 pt-4 pb-2 text-sm font-medium tracking-wide text-muted uppercase">Up next</h2>
	<div bind:this={list} class="relative px-2 pb-4 lg:min-h-0 lg:flex-1 lg:overflow-y-auto">
		{#if roomState}
			{#each roomState.history as item, i (item.itemId)}
				{@render row(item, 'history', i)}
			{/each}
			{#if roomState.nowPlaying}
				{@render row(roomState.nowPlaying.item, 'current', 0)}
			{/if}
			{#each roomState.queue as item, i (item.itemId)}
				{@render row(item, 'upcoming', i)}
			{/each}
			{#if !roomState.nowPlaying && roomState.queue.length === 0 && roomState.autoplay.length === 0}
				<p class="px-2 py-8 text-center text-sm text-muted">The queue is empty. Search for a song to get started.</p>
			{/if}
			{#if roomState.autoplay.length > 0}
				<div class="flex items-baseline gap-2 px-2 pt-4 pb-1">
					<h3 class="text-sm font-medium tracking-wide text-muted uppercase">Autoplay</h3>
					<span class="truncate text-xs text-muted">
						{roomState.autoplaySeed ? `Songs like ${roomState.autoplaySeed.title}` : 'Plays when the queue runs out'}
					</span>
				</div>
				{#each roomState.autoplay as item, i (item.itemId)}
					{@render row(item, 'autoplay', i)}
				{/each}
			{/if}
		{/if}
	</div>
</aside>

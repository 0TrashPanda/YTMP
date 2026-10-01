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

	// Dragging a song in "up next" by its handle.
	let drag = $state<{ itemId: string; from: number; startY: number; offset: number } | null>(null);
	const dropIndex = $derived(
		drag && roomState ? Math.max(0, Math.min(roomState.queue.length - 1, drag.from + Math.round(drag.offset / ROW_HEIGHT))) : -1
	);

	// Keep the current song in view, with the history above it (like YTM).
	$effect(() => {
		roomState?.nowPlaying?.item.itemId;
		tick().then(() => list?.querySelector('[data-current]')?.scrollIntoView({ block: 'center', behavior: 'smooth' }));
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

{#snippet row(item: QueueItem, variant: 'history' | 'current' | 'upcoming', index: number)}
	{@const dragging = drag?.itemId === item.itemId}
	<div
		class="flex h-16 items-center gap-3 rounded-md px-2 select-none
			{variant === 'current' ? 'bg-raised' : 'hover:bg-raised'}
			{variant === 'history' ? 'opacity-50' : ''}
			{dragging ? 'relative z-10 bg-line shadow-xl' : 'transition-transform'}"
		style:transform={variant === 'upcoming' ? `translateY(${dragging ? drag!.offset : shift(index)}px)` : undefined}
		data-current={variant === 'current' ? '' : undefined}
	>
		<button
			class="flex min-w-0 flex-1 items-center gap-3 text-left"
			disabled={variant === 'current'}
			onclick={() => room.run({ kind: 'JumpTo', itemId: item.itemId })}
			title={variant === 'current' ? undefined : 'Jump to this song'}
		>
			<Art song={item.song} size={40} class="h-10 w-10" />
			<div class="min-w-0 flex-1">
				<div class="truncate text-sm font-medium {variant === 'current' ? 'text-white' : ''}">{item.song.title}</div>
				<div class="truncate text-xs text-muted">
					{artistNames(item.song)} • added by {item.addedByName}{item.result === 'skipped' ? ' • skipped' : ''}
				</div>
			</div>
			<span class="text-xs text-muted tabular-nums">{formatTime(item.song.durationMs)}</span>
		</button>
		{#if variant === 'upcoming'}
			<button
				class="rounded-full p-1.5 text-muted hover:bg-line hover:text-white"
				aria-label="Remove from queue"
				onclick={() => room.run({ kind: 'RemoveQueueItem', itemId: item.itemId })}
			>
				<Icon name="remove" size={18} />
			</button>
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

<aside class="flex min-h-0 flex-col">
	<h2 class="px-4 pt-4 pb-2 text-sm font-medium tracking-wide text-muted uppercase">Up next</h2>
	<div bind:this={list} class="min-h-0 flex-1 overflow-y-auto px-2 pb-4">
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
			{#if !roomState.nowPlaying && roomState.queue.length === 0}
				<p class="px-2 py-8 text-center text-sm text-muted">The queue is empty. Search for a song to get started.</p>
			{/if}
		{/if}
	</div>
</aside>

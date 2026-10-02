<script lang="ts">
	// "Up next": played songs, the current one, the queue and the autoplay queue as one list.
	// Every song except the current one can be dragged anywhere in it (a played song moved
	// below the current one plays again), removed, and has the song menu (⋮ or right-click).
	import { tick } from 'svelte';
	import { artistNames, formatTime } from '../format';
	import type { ItemList, QueueItem } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let { room, onMenu }: { room: RoomConnection; onMenu: (target: MenuTarget) => void } = $props();

	type Place = 'history' | 'queue' | 'autoplay';
	type Slot =
		| { kind: 'item'; place: Place; item: QueueItem; key: string }
		| { kind: 'current'; item: QueueItem; key: string }
		| { kind: 'header'; key: string };

	const roomState = $derived(room.state);
	let list = $state<HTMLElement>();

	const slots = $derived.by((): Slot[] => {
		const s = roomState;
		if (!s) return [];
		// Keys include the place: while events arrive one by one, a song can briefly be in two
		// lists at once (e.g. already in the history and still current).
		const out: Slot[] = s.history.map((item) => ({ kind: 'item', place: 'history', item, key: `h:${item.itemId}` }));
		if (s.nowPlaying) out.push({ kind: 'current', item: s.nowPlaying.item, key: `c:${s.nowPlaying.item.itemId}` });
		for (const item of s.queue) out.push({ kind: 'item', place: 'queue', item, key: `q:${item.itemId}` });
		if (s.autoplay.length > 0) {
			out.push({ kind: 'header', key: 'autoplay-header' });
			for (const item of s.autoplay) out.push({ kind: 'item', place: 'autoplay', item, key: `a:${item.itemId}` });
		}
		return out;
	});

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

	// --- dragging --------------------------------------------------------------------------

	/** `mids`: the middle of every slot when the drag started, to find where the song lands. */
	let drag = $state<{ from: number; startY: number; offset: number; mids: number[]; height: number } | null>(null);

	/** Where the dragged song lands, as an index in the list without it. */
	const drop = $derived.by(() => {
		if (!drag) return -1;
		const center = drag.mids[drag.from] + drag.offset;
		return drag.mids.filter((mid, i) => i !== drag!.from && mid < center).length;
	});

	function startDrag(event: PointerEvent, index: number) {
		event.preventDefault();
		(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
		const elements = [...(list?.querySelectorAll<HTMLElement>('[data-slot]') ?? [])];
		drag = {
			from: index,
			startY: event.clientY,
			offset: 0,
			mids: elements.map((el) => el.offsetTop + el.offsetHeight / 2),
			height: elements[index]?.offsetHeight ?? 64
		};
	}

	function endDrag() {
		const d = drag;
		const p = drop;
		drag = null;
		if (!d || p === d.from) return;
		const slot = slots[d.from];
		if (slot.kind !== 'item') return;
		const target = landing(d.from, p);
		room.run({ kind: 'MoveItem', itemId: slot.item.itemId, list: target.list, toIndex: target.index });
	}

	/** Which list (and where in it) position [p] is, in the list without the dragged slot. */
	function landing(from: number, p: number): { list: ItemList; index: number } {
		const rest = slots.filter((_, i) => i !== from);
		const current = rest.findIndex((s) => s.kind === 'current');
		const header = rest.findIndex((s) => s.kind === 'header');
		const played = rest.filter((s) => s.kind === 'item' && s.place === 'history').length;
		if (header >= 0 && p > header) return { list: 'autoplay', index: p - header - 1 };
		if (current >= 0) return p <= current ? { list: 'history', index: p } : { list: 'queue', index: p - current - 1 };
		return p < played ? { list: 'history', index: p } : { list: 'queue', index: p - played };
	}

	/** Other songs make room for the dragged one. */
	function shift(index: number): number {
		if (!drag || index === drag.from) return 0;
		if (index < drag.from && index >= drop) return drag.height;
		if (index > drag.from && index <= drop) return -drag.height;
		return 0;
	}

	// --- actions ---------------------------------------------------------------------------

	function open(slot: Slot) {
		if (slot.kind !== 'item') return;
		// An autoplay song plays next; others play now (jump), like YTM.
		if (slot.place === 'autoplay') room.run({ kind: 'MoveItem', itemId: slot.item.itemId, list: 'queue', toIndex: 0 });
		else room.run({ kind: 'JumpTo', itemId: slot.item.itemId });
	}

	function canOpen(slot: Slot) {
		return slot.kind === 'item' && room.can(slot.place === 'autoplay' ? 'add_songs' : 'play_now');
	}

	function menu(event: MouseEvent, slot: Slot, atPointer: boolean) {
		if (slot.kind === 'header') return;
		event.preventDefault();
		event.stopPropagation();
		const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
		onMenu({
			song: slot.item.song,
			item: slot.item,
			place: slot.kind === 'current' ? 'current' : slot.place,
			x: atPointer ? event.clientX : rect.right - 256,
			y: atPointer ? event.clientY : rect.bottom
		});
	}

	function subtitle(item: QueueItem, place: Place | 'current') {
		if (place === 'autoplay') return artistNames(item.song);
		const by = item.origin === 'autoplay' ? ' • autoplay' : ` • ${item.origin === 'radio' ? 'radio by' : 'added by'} ${item.addedByName}`;
		return artistNames(item.song) + by + (item.result === 'skipped' ? ' • skipped' : '');
	}
</script>

<aside class="flex flex-col lg:h-full lg:min-h-0">
	<h2 class="px-4 pt-4 pb-2 text-sm font-medium tracking-wide text-muted uppercase">Up next</h2>
	<div bind:this={list} class="relative px-2 pb-4 lg:min-h-0 lg:flex-1 lg:overflow-y-auto">
		{#each slots as slot, index (slot.key)}
			{@const dragging = drag?.from === index}
			{#if slot.kind === 'header'}
				<div
					data-slot
					class="flex h-10 items-end gap-2 px-2 pb-1 transition-transform"
					style:transform="translateY({shift(index)}px)"
				>
					<h3 class="text-sm font-medium tracking-wide text-muted uppercase">Autoplay</h3>
					<span class="truncate text-xs text-muted">
						{roomState?.autoplaySeed ? `Songs like ${roomState.autoplaySeed.title}` : 'Plays when the queue runs out'}
					</span>
				</div>
			{:else}
				{@const place = slot.kind === 'current' ? 'current' : slot.place}
				<div
					data-slot
					data-current={slot.kind === 'current' ? '' : undefined}
					class="relative flex h-16 items-center gap-2 rounded-md px-2 select-none
						{slot.kind === 'current' ? 'bg-raised' : 'hover:bg-raised'}
						{place === 'history' && !dragging ? 'opacity-50' : ''}
						{dragging ? 'z-10 bg-line shadow-xl' : 'transition-transform'}"
					style:transform="translateY({dragging ? drag!.offset : shift(index)}px)"
					oncontextmenu={(e) => menu(e, slot, true)}
					role="listitem"
				>
					<button
						class="flex min-w-0 flex-1 items-center gap-3 text-left"
						disabled={!canOpen(slot)}
						onclick={() => open(slot)}
						title={slot.kind === 'item' && canOpen(slot) ? (slot.place === 'autoplay' ? 'Play next' : 'Jump to this song') : undefined}
					>
						<Art song={slot.item.song} size={40} class="h-10 w-10" />
						<div class="min-w-0 flex-1">
							<div class="truncate text-sm font-medium {slot.kind === 'current' ? 'text-white' : ''}">{slot.item.song.title}</div>
							<div class="truncate text-xs text-muted">{subtitle(slot.item, place)}</div>
						</div>
						<span class="text-xs text-muted tabular-nums">{formatTime(slot.item.song.durationMs)}</span>
					</button>
					<button class="rounded-full p-1.5 text-muted hover:bg-line hover:text-white" aria-label="More for {slot.item.song.title}" onclick={(e) => menu(e, slot, false)}>
						<Icon name="more" size={18} />
					</button>
					{#if slot.kind === 'item'}
						{#if room.can(slot.item.addedBy === room.participantId ? 'remove_own' : 'remove_others')}
							<button
								class="rounded-full p-1.5 text-muted hover:bg-line hover:text-white"
								aria-label="Remove {slot.item.song.title}"
								onclick={() => room.run({ kind: 'RemoveQueueItem', itemId: slot.item.itemId })}
							>
								<Icon name="remove" size={18} />
							</button>
						{/if}
						{#if room.can('reorder')}
							<button
								class="cursor-grab touch-none rounded-full p-1.5 text-muted hover:text-white active:cursor-grabbing"
								aria-label="Drag to move"
								onpointerdown={(e) => startDrag(e, index)}
								onpointermove={(e) => drag && (drag.offset = e.clientY - drag.startY)}
								onpointerup={endDrag}
								onpointercancel={() => (drag = null)}
							>
								<Icon name="drag" size={18} />
							</button>
						{/if}
					{/if}
				</div>
			{/if}
		{/each}
		{#if roomState && !roomState.nowPlaying && roomState.queue.length === 0 && roomState.autoplay.length === 0}
			<p class="px-2 py-8 text-center text-sm text-muted">The queue is empty. Search for a song to get started.</p>
		{/if}
	</div>
</aside>

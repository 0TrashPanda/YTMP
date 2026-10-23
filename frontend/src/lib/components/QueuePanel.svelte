<script lang="ts">
	// "Up next": played songs, the current one, the queue and the autoplay queue as one list.
	// Every song can be dragged anywhere in it (a played song moved below the current one
	// plays again; the current one keeps playing, and the songs it's dragged past change sides). Tap, swipe right, swipe
	// left and hold do what Settings → Taps and swipes says (play, play next, remove like YTM,
	// the song menu by default); right-click or ⋮ on hover with a mouse: the song menu.
	import { tick } from 'svelte';
	import { artistNames, formatTime } from '../format';
	import { ACTION_ICONS, ACTION_NAMES, gestures, RowGestures, type QueueAction } from '../gestures.svelte';
	import { menuAt, startRadio } from '../songActions';
	import type { ItemList, QueueItem } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	/** [title]: false where a tab already says "Up next". */
	let { room, onMenu, title = true }: { room: RoomConnection; onMenu: (target: MenuTarget) => void; title?: boolean } = $props();

	type Place = 'history' | 'queue' | 'autoplay';
	type Slot =
		| { kind: 'item'; place: Place; item: QueueItem; key: string }
		| { kind: 'current'; item: QueueItem; key: string }
		| { kind: 'header'; key: string };

	const roomState = $derived(room.state);
	const canClear = $derived(
		!!roomState?.queue.length && room.can(roomState.queue.every((i) => i.addedBy === room.participantId) ? 'remove_own' : 'remove_others')
	);
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
		// A dropped song stays where it was dropped while the host moves it.
		const from = dropped ? out.findIndex((slot) => slot.key === dropped!.key) : -1;
		if (from >= 0) out.splice(dropped!.to, 0, ...out.splice(from, 1));
		return out;
	});

	// Keep the current song in view, with the history above it (like YTM): there right away
	// when the list opens, gliding along when the song changes.
	let placed = false;
	$effect(() => {
		roomState?.nowPlaying?.item.itemId;
		tick().then(() => {
			if (!list || list.scrollHeight <= list.clientHeight) return;
			const current = list.querySelector<HTMLElement>('[data-current]');
			if (current) list.scrollTo({ top: current.offsetTop - list.clientHeight / 2, behavior: placed ? 'smooth' : 'instant' });
			placed = true;
		});
	});

	// --- dragging --------------------------------------------------------------------------

	/**
	 * `mids`: the middle of every slot when the drag started, to find where the song lands.
	 * The song follows the pointer also while the list scrolls (the wheel, or near its top or
	 * bottom edge): `offset` counts both.
	 */
	let drag = $state<{ from: number; startY: number; startScroll: number; pointerY: number; offset: number; mids: number[]; height: number } | null>(
		null
	);
	/** Scrolls the list while the pointer is near its top or bottom edge. */
	let edgeScroll = 0;
	/** The song just dropped (by its key) and where, until the host has moved it. */
	let dropped = $state<{ key: string; to: number } | null>(null);
	/**
	 * Songs gliding to their places after the list changed order (see [slide]): first each one
	 * is put back where it was shown (`dy`, by its id), then they all glide to 0 (`moving`).
	 * [lift]: the song that was dragged, above the others while it glides.
	 */
	let glide = $state<{ dy: Record<string, number>; moving: boolean; lift: string | null } | null>(null);
	/** Counts glides: a newer one (or a new drag) takes over from an older one. */
	let glides = 0;
	/** How long the host may take to move a dropped song before it goes back. */
	const MOVE_TIMEOUT_MS = 5000;

	/** Where the dragged song lands, as an index in the list without it. */
	const drop = $derived.by(() => {
		if (!drag) return -1;
		const center = drag.mids[drag.from] + drag.offset;
		return drag.mids.filter((mid, i) => i !== drag!.from && mid < center).length;
	});

	function startDrag(event: PointerEvent, index: number) {
		event.preventDefault();
		glide = null;
		glides++;
		(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
		const elements = [...(list?.querySelectorAll<HTMLElement>('[data-slot]') ?? [])];
		drag = {
			from: index,
			startY: event.clientY,
			startScroll: list?.scrollTop ?? 0,
			pointerY: event.clientY,
			offset: 0,
			mids: elements.map((el) => el.offsetTop + el.offsetHeight / 2),
			height: elements[index]?.offsetHeight ?? 64
		};
		cancelAnimationFrame(edgeScroll);
		edgeScroll = requestAnimationFrame(scrollNearEdges);
	}

	function moveDrag(event: PointerEvent) {
		if (!drag) return;
		drag.pointerY = event.clientY;
		follow();
	}

	/** Where the dragged song is: under the pointer, in the list's own coordinates. */
	function follow() {
		if (drag) drag.offset = drag.pointerY - drag.startY + ((list?.scrollTop ?? 0) - drag.startScroll);
	}

	/** Near the top or bottom edge of the list, it scrolls (faster the closer), like YouTube Music. */
	function scrollNearEdges() {
		if (!drag || !list) return;
		const box = list.getBoundingClientRect();
		const zone = Math.min(64, box.height / 4);
		const above = box.top + zone - drag.pointerY;
		const below = drag.pointerY - (box.bottom - zone);
		const speed = above > 0 ? -Math.min(above, zone) / 6 : below > 0 ? Math.min(below, zone) / 6 : 0;
		if (speed) list.scrollTop += speed;
		edgeScroll = requestAnimationFrame(scrollNearEdges);
	}

	async function endDrag() {
		cancelAnimationFrame(edgeScroll);
		const d = drag;
		const p = drop;
		if (!d) return;
		const slot = slots[d.from];
		if (p === d.from || slot.kind === 'header') {
			drag = null;
			return;
		}
		const target = landing(d.from, p);
		const done = room.run({ kind: 'MoveItem', itemId: slot.item.itemId, list: target.list, toIndex: target.index });
		const id = slot.item.itemId;
		// The song glides from under the finger into its new place, and stays there for now.
		await slide(id, () => {
			dropped = { key: slot.key, to: p };
			drag = null;
		});
		// Then the room's own order: the same when the host moved it; back where it was when it
		// didn't (not allowed, or no answer); or wherever someone else's change put it.
		await Promise.race([done, new Promise((resolve) => setTimeout(resolve, MOVE_TIMEOUT_MS))]);
		if (dropped?.key !== slot.key) return;
		// Already dragging the next song: no gliding under it.
		if (drag) dropped = null;
		else await slide(id, () => (dropped = null));
	}

	/** Where every slot is shown now (with its transform), by id, in the list's own coordinates. */
	function shownAt(): Map<string, number> {
		const top = (list?.getBoundingClientRect().top ?? 0) - (list?.scrollTop ?? 0);
		const at = new Map<string, number>();
		for (const el of list?.querySelectorAll<HTMLElement>('[data-slot]') ?? []) at.set(el.dataset.id!, el.getBoundingClientRect().top - top);
		return at;
	}

	/**
	 * Changes the list's order with [change], and every song that moves glides from where it's
	 * shown now to its new place, instead of jumping (or sliding back first). [lift]: the
	 * dragged song, kept above the others.
	 */
	async function slide(lift: string, change: () => void) {
		const before = shownAt();
		// No transitions while the order changes and the songs are measured.
		const mine = ++glides;
		glide = { dy: {}, moving: false, lift };
		change();
		await tick();
		// Not painted yet: put every song back where it was shown, then let them all glide.
		const dy: Record<string, number> = {};
		for (const el of list?.querySelectorAll<HTMLElement>('[data-slot]') ?? []) {
			const was = before.get(el.dataset.id!);
			if (was !== undefined && Math.abs(was - el.offsetTop) > 0.5) dy[el.dataset.id!] = was - el.offsetTop;
		}
		if (Object.keys(dy).length === 0) {
			glide = null;
			return;
		}
		glide = { dy, moving: false, lift };
		await tick();
		await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
		if (glides !== mine) return;
		glide = { dy: {}, moving: true, lift };
		await new Promise((resolve) => setTimeout(resolve, 200));
		if (glides === mine) glide = null;
	}

	/** How far slot [index] (with [id]) is moved from its place: dragged, gliding, or making room. */
	function offsetOf(id: string, index: number): number {
		if (drag?.from === index) return drag.offset;
		if (glide && !glide.moving) return glide.dy[id] ?? 0;
		return shift(index);
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

	// --- tap, swipe and hold ----------------------------------------------------------------

	/** Swiped away, waiting for the host to remove it. */
	let removing = $state<string | null>(null);

	function canRemove(item: QueueItem) {
		return room.can(item.addedBy === room.participantId ? 'remove_own' : 'remove_others');
	}

	function allowed(action: QueueAction, slot: Slot): boolean {
		if (slot.kind === 'header') return false;
		const moves = slot.kind === 'item' ? room.can(slot.place === 'autoplay' ? 'add_songs' : 'reorder') : room.can('add_songs');
		switch (action) {
			case 'playNow':
				return canOpen(slot);
			case 'playNext':
			case 'addToQueue':
				return moves;
			case 'remove':
				// The current song too: the next one plays.
				return canRemove(slot.item);
			case 'radio':
				return room.can('start_radio') && !slot.item.song.podcast;
			case 'menu':
				return true;
			default:
				return false;
		}
	}

	/** Does [action] (one of Settings → Taps and swipes) for [slot]. */
	async function run(action: QueueAction, slot: Slot, event: MouseEvent | null, row: HTMLElement | null) {
		if (slot.kind === 'header') return;
		if (action === 'menu') return openMenu(slot, menuAt(row ?? (event!.currentTarget as HTMLElement)));
		const item = slot.item;
		const queueLength = roomState?.queue.length ?? 0;
		switch (action) {
			case 'playNow':
				return open(slot);
			case 'playNext':
				return slot.kind === 'current'
					? room.run({ kind: 'AddSongs', songs: [item.song], position: 'next' })
					: room.run({ kind: 'MoveItem', itemId: item.itemId, list: 'queue', toIndex: 0 });
			case 'addToQueue':
				return slot.kind === 'current'
					? room.run({ kind: 'AddSongs', songs: [item.song], position: 'end' })
					: room.run({ kind: 'MoveItem', itemId: item.itemId, list: 'queue', toIndex: slot.place === 'queue' ? queueLength - 1 : queueLength });
			case 'remove': {
				const key = slot.key;
				removing = key;
				const error = await room.run({ kind: 'RemoveQueueItem', itemId: item.itemId });
				// Not removed (e.g. no permission any more): put it back.
				if (error && removing === key) removing = null;
				return;
			}
			case 'radio':
				await startRadio(room, item.song, () => {});
				return;
		}
	}

	const rows = new RowGestures<Slot>('queue', allowed, run, menu);
	const swipe = rows.swipe;

	/** How far a song is swiped, or swiped away. */
	function swipeOffset(key: string): string {
		if (removing === key) return '-100%';
		return `${swipe.offset(key)}px`;
	}

	// --- actions ---------------------------------------------------------------------------

	function open(slot: Slot) {
		if (slot.kind !== 'item' || !canOpen(slot)) return;
		// An autoplay song plays next; others play now (jump), like YTM.
		if (slot.place === 'autoplay') room.run({ kind: 'MoveItem', itemId: slot.item.itemId, list: 'queue', toIndex: 0 });
		else room.run({ kind: 'JumpTo', itemId: slot.item.itemId });
	}

	function canOpen(slot: Slot) {
		return slot.kind === 'item' && room.can(slot.place === 'autoplay' ? 'add_songs' : 'play_now');
	}

	/** The ⋮ button or a right-click. */
	function menu(slot: Slot, event: MouseEvent, atPointer: boolean) {
		if (slot.kind === 'header') return;
		event.preventDefault();
		event.stopPropagation();
		openMenu(slot, menuAt(event.currentTarget as HTMLElement, event, atPointer));
	}

	function openMenu(slot: Slot, at: { x: number; y: number }) {
		if (slot.kind === 'header') return;
		onMenu({ song: slot.item.song, item: slot.item, place: slot.kind === 'current' ? 'current' : slot.place, ...at });
	}

	function subtitle(item: QueueItem, place: Place | 'current') {
		if (place === 'autoplay') return artistNames(item.song);
		const by = item.origin === 'autoplay' ? ' • autoplay' : ` • ${item.origin === 'radio' ? 'radio by' : 'added by'} ${item.addedByName}`;
		return artistNames(item.song) + by + (item.result === 'skipped' ? ' • skipped' : '');
	}
</script>

<aside class="flex h-full min-h-0 flex-col">
	{#if title || canClear}
		<div class="flex items-center gap-2 px-4 {title ? 'pt-4' : 'pt-2'} pb-2">
			<h2 class="flex-1 text-sm font-medium tracking-wide text-muted uppercase">{title ? 'Up next' : ''}</h2>
			{#if canClear}
				<button class="rounded-full px-2 py-0.5 text-xs text-muted hover:bg-raised hover:text-white" onclick={() => room.run({ kind: 'ClearQueue' })}>
					Clear queue
				</button>
			{/if}
		</div>
	{/if}
	<div bind:this={list} onscroll={follow} class="relative min-h-0 flex-1 overflow-y-auto overscroll-contain px-2 pb-4">
		{#each slots as slot, index (slot.key)}
			{@const dragging = drag?.from === index}
			{@const id = slot.kind === 'header' ? slot.key : slot.item.itemId}
			{@const instant = dragging || (!!glide && !glide.moving)}
			{#if slot.kind === 'header'}
				<div
					data-slot
					data-id={id}
					class="flex h-10 items-end gap-2 px-2 pb-1 {instant ? '' : 'transition-transform'}"
					style:transform="translateY({offsetOf(id, index)}px)"
				>
					<h3 class="text-sm font-medium tracking-wide text-muted uppercase">Autoplay</h3>
					<span class="min-w-0 flex-1 truncate text-xs text-muted">
						{roomState?.autoplaySeed ? `Songs like ${roomState.autoplaySeed.title}` : 'Plays when the queue runs out'}
					</span>
					{#if room.can('autoplay_from_here')}
						<button class="shrink-0 rounded-full px-2 py-0.5 text-xs text-muted hover:bg-raised hover:text-white" onclick={() => room.run({ kind: 'ClearAutoplay' })}>
							Clear
						</button>
					{/if}
				</div>
			{:else}
				{@const place = slot.kind === 'current' ? 'current' : slot.place}
				{@const offset = swipe.offset(slot.key)}
				{@const swiping = offset !== 0}
				<div
					data-slot
					data-id={id}
					data-current={slot.kind === 'current' ? '' : undefined}
					class="group relative h-16 overflow-hidden rounded-md {dragging || glide?.lift === id ? 'z-10 shadow-xl' : ''} {instant ? '' : 'transition-transform'}"
					style:transform="translateY({offsetOf(id, index)}px)"
				>
					<!-- Under the song while it's swiped: what letting go does. -->
					{#if removing === slot.key}
						<div class="absolute inset-0 flex items-center justify-end bg-accent px-5 text-white">
							<Icon name="remove" size={22} />
						</div>
					{:else if swiping}
						{@const action = rows.actionFor(offset)}
						<div
							class="absolute inset-0 flex items-center gap-2 px-5 text-sm font-medium text-white transition-colors
								{offset > 0 ? 'justify-start' : 'justify-end'} {swipe.far(slot.key) ? (action === 'remove' ? 'bg-accent' : 'bg-d-green') : 'bg-line'}"
						>
							<Icon name={ACTION_ICONS[action]} size={22} />
							{ACTION_NAMES[action]}
						</div>
					{/if}
					<div
						class="relative flex h-full touch-pan-y items-center gap-2 rounded-md px-2 select-none
							{slot.kind === 'current' ? 'bg-raised' : swiping || removing === slot.key ? 'bg-surface' : 'hover:bg-raised'}
							{place === 'history' && !dragging && !swiping ? 'opacity-50' : ''}
							{dragging ? 'bg-line' : ''}
							{swiping ? '' : 'transition-transform duration-200'}"
						style:transform="translateX({swipeOffset(slot.key)})"
						onpointerdown={(e) => rows.pressed(e)}
						oncontextmenu={(e) => rows.held(e, slot)}
						ontouchstart={(e) => !drag && swipe.start(e, slot.key)}
						ontouchmove={(e) => swipe.move(e, rows.canSwipe(slot))}
						ontouchend={(e) => rows.swiped(slot, e.currentTarget as HTMLElement)}
						ontouchcancel={() => swipe.cancel()}
						role="listitem"
					>
						<!-- Not `disabled`: browsers don't send right-clicks to disabled buttons. -->
						<button
							class="flex min-w-0 flex-1 items-center gap-3 text-left {canOpen(slot) ? '' : 'cursor-default'}"
							aria-disabled={!canOpen(slot)}
							onclick={(e) => rows.act('tap', slot, e, e.currentTarget.parentElement)}
							title={ACTION_NAMES[gestures.queue.tap]}
						>
							<Art song={slot.item.song} size={40} class="h-10 w-10" />
							<div class="min-w-0 flex-1">
								<div class="truncate text-sm font-medium {slot.kind === 'current' ? 'text-white' : ''}">{slot.item.song.title}</div>
								<div class="truncate text-xs text-muted">{subtitle(slot.item, place)}</div>
							</div>
							<span class="text-xs text-muted tabular-nums">{slot.item.song.durationMs ? formatTime(slot.item.song.durationMs) : ''}</span>
						</button>
						<!-- With a mouse, on hover; touch screens hold the song for the menu, or (when Hold does something else) have it here. -->
						<button
							class="rounded-full p-1.5 text-muted opacity-0 group-hover:opacity-100 hover:bg-line hover:text-white focus:opacity-100 {gestures.queue.hold === 'menu' ? 'pointer-coarse:hidden' : 'pointer-coarse:opacity-100'}"
							aria-label="More for {slot.item.song.title}"
							onclick={(e) => menu(slot, e, false)}
						>
							<Icon name="more" size={18} />
						</button>
						{#if room.can('reorder')}
							<button
								class="cursor-grab touch-none rounded-full p-1.5 text-muted hover:text-white active:cursor-grabbing"
								aria-label="Drag to move"
								onpointerdown={(e) => startDrag(e, index)}
								onpointermove={moveDrag}
								onpointerup={endDrag}
								onpointercancel={() => {
									cancelAnimationFrame(edgeScroll);
									drag = null;
								}}
							>
								<Icon name="drag" size={18} />
							</button>
						{/if}
					</div>
				</div>
			{/if}
		{/each}
		{#if roomState && !roomState.nowPlaying && roomState.queue.length === 0 && roomState.autoplay.length === 0}
			<p class="px-2 py-8 text-center text-sm text-muted">The queue is empty. Search for a song to get started.</p>
		{/if}
	</div>
</aside>

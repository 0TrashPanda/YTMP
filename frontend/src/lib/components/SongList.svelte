<script lang="ts">
	// A list of songs (search, find similar, artist, album, playlist, history and Local pages). Tap,
	// swipe right, swipe left and hold do what Settings → Taps and swipes says (play next, add
	// to queue, play now, the song menu by default); ⋮ or right-click = song menu.
	import { artistNames, formatTime } from '../format';
	import { ACTION_ICONS, ACTION_NAMES, gestures, RowGestures, type QueueAction } from '../gestures.svelte';
	import { addToQueue, menuAt, playNext, playNow, quoted, startRadio } from '../songActions';
	import type { Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let {
		room,
		songs,
		numbered = false,
		showAlbum = true,
		playlist,
		details,
		listened,
		onMove,
		onToast,
		onMenu
	}: {
		room: RoomConnection;
		songs: Song[];
		/** Track numbers instead of art (album pages). */
		numbered?: boolean;
		showAlbum?: boolean;
		/** One of your own playlists (or Liked music): its songs can be removed from it (the song menu). */
		playlist?: { id: string; itemIds: string[]; likedMusic: boolean; onRemoved: (index: number) => void };
		/** A third line under each song (your history: when, where, with whom). */
		details?: string[];
		/** Songs of your listening history (their entries' IDs): they can be removed from it (the song menu). */
		listened?: { ids: string[]; onRemoved: (index: number) => void };
		/** Songs can be dragged to another place (your own playlists). */
		onMove?: (from: number, to: number) => void;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
	} = $props();

	// Dragging (with the handle): the song follows the pointer, the others make room.
	let list = $state<HTMLElement | null>(null);
	let drag = $state<{ from: number; startY: number; offset: number; mids: number[]; height: number } | null>(null);
	const drop = $derived.by(() => {
		if (!drag) return -1;
		const center = drag.mids[drag.from] + drag.offset;
		return drag.mids.filter((mid, i) => i !== drag!.from && mid < center).length;
	});
	function startDrag(event: PointerEvent, index: number) {
		event.preventDefault();
		(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
		const rows = [...(list?.querySelectorAll<HTMLElement>('[data-row]') ?? [])];
		drag = { from: index, startY: event.clientY, offset: 0, mids: rows.map((r) => r.offsetTop + r.offsetHeight / 2), height: rows[index]?.offsetHeight ?? 64 };
	}
	function endDrag() {
		const d = drag;
		const to = drop;
		drag = null;
		if (d && to >= 0 && to !== d.from) onMove?.(d.from, to);
	}
	function shift(index: number): number {
		if (!drag || index === drag.from) return 0;
		if (index < drag.from && index >= drop) return drag.height;
		if (index > drag.from && index <= drop) return -drag.height;
		return 0;
	}

	// --- tap, swipe and hold ---------------------------------------------------------------

	type Row = { song: Song; index: number };

	function allowed(action: QueueAction, { song }: Row): boolean {
		switch (action) {
			case 'playNow':
				return room.can('play_now');
			case 'playNext':
			case 'addToQueue':
				return room.can('add_songs');
			case 'radio':
				return room.can('start_radio') && !song.podcast;
			default:
				return false;
		}
	}

	function run(action: QueueAction, row: Row, event: MouseEvent | null, element: HTMLElement | null) {
		const { song } = row;
		switch (action) {
			case 'menu':
				return openMenu(row, menuAt(element ?? (event!.currentTarget as HTMLElement)));
			case 'playNow':
				return playNow(room, song, onToast);
			case 'playNext':
				return playNext(room, [song], quoted(song), onToast);
			case 'addToQueue':
				return addToQueue(room, [song], quoted(song), onToast);
			case 'radio':
				return startRadio(room, song, onToast);
		}
	}

	/** The ⋮ button or a right-click. */
	function menu(row: Row, event: MouseEvent, atPointer: boolean) {
		event.preventDefault();
		event.stopPropagation();
		openMenu(row, menuAt(event.currentTarget as HTMLElement, event, atPointer));
	}

	function openMenu({ song, index }: Row, at: { x: number; y: number }) {
		const inPlaylist = playlist && {
			id: playlist.id,
			itemId: playlist.itemIds[index] || null,
			likedMusic: playlist.likedMusic,
			onRemoved: () => playlist.onRemoved(index)
		};
		const inHistory = listened && { id: listened.ids[index], onRemoved: () => listened.onRemoved(index) };
		onMenu({ song, place: 'search', playlist: inPlaylist, listened: inHistory, ...at });
	}

	const rows = new RowGestures<Row>('list', allowed, run, menu);
	const swipe = rows.swipe;
</script>

<div bind:this={list} class="relative flex flex-col gap-1">
{#each songs as song, i (song.id + i)}
	{@const dragging = drag?.from === i}
	{@const key = song.id + i}
	{@const offset = swipe.offset(key)}
	<div
		data-row
		class="relative overflow-hidden rounded-md [contain-intrinsic-size:auto_56px] [content-visibility:auto] {dragging ? 'z-10 shadow-xl' : drag ? 'transition-transform' : ''}"
		style:transform="translateY({dragging ? drag!.offset : shift(i)}px)"
	>
		<!-- Under the song while it's swiped: what letting go does. -->
		{#if offset !== 0}
			{@const action = rows.actionFor(offset)}
			<div
				class="absolute inset-0 flex items-center gap-2 px-5 text-sm font-medium text-white transition-colors
					{offset > 0 ? 'justify-start' : 'justify-end'} {swipe.far(key) ? (action === 'remove' ? 'bg-accent' : 'bg-good') : 'bg-line'}"
			>
				<Icon name={ACTION_ICONS[action]} size={22} />
				{ACTION_NAMES[action]}
			</div>
		{/if}
	<div
		class="group relative flex touch-pan-y items-center gap-3 rounded-md px-2 py-2 {dragging ? 'bg-raised' : offset !== 0 ? 'bg-surface' : 'hover:bg-raised'} {offset !== 0 ? '' : 'transition-transform duration-200'}"
		style:transform="translateX({offset}px)"
		role="listitem"
		onpointerdown={(e) => rows.pressed(e)}
		oncontextmenu={(e) => rows.held(e, { song, index: i })}
		ontouchstart={(e) => swipe.start(e, key)}
		ontouchmove={(e) => swipe.move(e, rows.canSwipe({ song, index: i }))}
		ontouchend={(e) => rows.swiped({ song, index: i }, e.currentTarget as HTMLElement)}
		ontouchcancel={() => swipe.cancel()}
	>
		<button class="flex min-w-0 flex-1 items-center gap-3 text-left" onclick={(e) => rows.act('tap', { song, index: i }, e, e.currentTarget.parentElement)} title={ACTION_NAMES[gestures.list.tap]}>
			{#if numbered}
				<span class="w-8 shrink-0 text-center text-muted tabular-nums">{i + 1}</span>
			{:else}
				<Art {song} size={48} class="h-12 w-12" />
			{/if}
			<div class="min-w-0 flex-1">
				<div class="truncate font-medium">{song.title}</div>
				<div class="truncate text-sm text-muted">
					{artistNames(song)}{showAlbum && song.album ? ` • ${song.album.name}` : ''}
				</div>
				{#if details?.[i]}
					<div class="truncate text-xs text-muted">{details[i]}</div>
				{/if}
			</div>
			<!-- Some listings have no length (episodes in search); it's known once it plays. -->
			<span class="text-sm text-muted tabular-nums">{song.durationMs ? formatTime(song.durationMs) : ''}</span>
		</button>
		<button class="rounded-full p-2 text-muted hover:bg-line hover:text-white" aria-label="More for {song.title}" onclick={(e) => menu({ song, index: i }, e, false)}>
			<Icon name="more" />
		</button>
		{#if onMove}
			<button
				class="cursor-grab touch-none rounded-full p-2 text-muted hover:text-white active:cursor-grabbing"
				aria-label="Drag to move"
				onpointerdown={(e) => startDrag(e, i)}
				onpointermove={(e) => drag && (drag.offset = e.clientY - drag.startY)}
				onpointerup={endDrag}
				onpointercancel={() => (drag = null)}
			>
				<Icon name="drag" size={18} />
			</button>
		{/if}
	</div>
	</div>
{/each}
</div>

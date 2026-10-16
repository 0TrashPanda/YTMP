<script lang="ts">
	// A list of songs (search, find similar, artist, album, playlist and Local pages). Tap,
	// swipe right, swipe left and hold do what Settings → Taps and swipes says (play next, add
	// to queue, play now, the song menu by default); ⋮ or right-click = song menu.
	import { yourRadio } from '../api';
	import { artistNames, formatTime } from '../format';
	import { ACTION_ICONS, ACTION_NAMES, gestures, RowSwipe, type Gesture, type QueueAction } from '../gestures.svelte';
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
		playlist?: { id: string; itemIds: string[]; likedMusic: boolean; onChanged: () => void };
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

	const swipe = new RowSwipe();
	/** Touch or mouse, for the last press: holding a finger does the Hold action, a right-click always opens the menu. */
	let pointer = 'mouse';

	function allowed(action: QueueAction, song: Song): boolean {
		switch (action) {
			case 'playNow':
				return room.can('play_now');
			case 'playNext':
			case 'addToQueue':
				return room.can('add_songs');
			case 'radio':
				return room.can('start_radio') && !song.podcast;
			case 'menu':
				return true;
			default:
				return false;
		}
	}

	/** Does what [gesture] is set to for [song]; [event] places the menu. */
	async function act(gesture: Gesture, song: Song, index: number, event: MouseEvent | null, row: HTMLElement | null) {
		const action = gestures.list[gesture];
		if (action === 'menu') return openMenu(song, index, event, row);
		if (!allowed(action, song)) return;
		let error;
		switch (action) {
			case 'playNow':
				error = await room.run({ kind: 'PlayNow', song });
				if (error) onToast(error.message);
				return;
			case 'playNext':
				error = await room.run({ kind: 'AddSongs', songs: [song], position: 'next' });
				onToast(error ? error.message : room.playNextText(`"${song.title}"`));
				return;
			case 'addToQueue':
				error = await room.run({ kind: 'AddSongs', songs: [song], position: 'end' });
				onToast(error ? error.message : `Added "${song.title}" to the queue`);
				return;
			case 'radio':
				error = await room.run({ kind: 'StartRadio', song, songs: await yourRadio(song.id) });
				onToast(error ? error.message : `Starting a radio from "${song.title}"`);
				return;
		}
	}

	function swiped(song: Song, index: number, row: HTMLElement) {
		const done = swipe.end();
		if (done) act(done.direction, song, index, null, row);
	}

	/** Hold (touch) does the Hold action; a right-click opens the menu. */
	function held(event: MouseEvent, song: Song, index: number) {
		if (pointer === 'touch' && gestures.list.hold !== 'menu') {
			event.preventDefault();
			swipe.cancel();
			act('hold', song, index, null, event.currentTarget as HTMLElement);
		} else {
			menu(event, song, index, true);
		}
	}

	function openMenu(song: Song, index: number, event: MouseEvent | null, row: HTMLElement | null) {
		const rect = (row ?? (event?.currentTarget as HTMLElement)).getBoundingClientRect();
		const inPlaylist = playlist && {
			id: playlist.id,
			itemId: playlist.itemIds[index] || null,
			likedMusic: playlist.likedMusic,
			onRemoved: playlist.onChanged
		};
		onMenu({ song, place: 'search', playlist: inPlaylist, x: rect.right - 256, y: rect.bottom });
	}

	/** The action a swipe of [offset] px would do. */
	const swipeAction = (offset: number): QueueAction => gestures.list[offset > 0 ? 'right' : 'left'];

	function menu(event: MouseEvent, song: Song, index: number, atPointer: boolean) {
		event.preventDefault();
		event.stopPropagation();
		const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
		const inPlaylist = playlist && {
			id: playlist.id,
			itemId: playlist.itemIds[index] || null,
			likedMusic: playlist.likedMusic,
			onRemoved: playlist.onChanged
		};
		onMenu({ song, place: 'search', playlist: inPlaylist, x: atPointer ? event.clientX : rect.right - 256, y: atPointer ? event.clientY : rect.bottom });
	}
</script>

<div bind:this={list} class="relative flex flex-col gap-1">
{#each songs as song, i (song.id + i)}
	{@const dragging = drag?.from === i}
	{@const key = song.id + i}
	{@const offset = swipe.offset(key)}
	<div
		data-row
		class="relative overflow-hidden rounded-md {dragging ? 'z-10 shadow-xl' : drag ? 'transition-transform' : ''}"
		style:transform="translateY({dragging ? drag!.offset : shift(i)}px)"
	>
		<!-- Under the song while it's swiped: what letting go does. -->
		{#if offset !== 0}
			{@const action = swipeAction(offset)}
			<div
				class="absolute inset-0 flex items-center gap-2 px-5 text-sm font-medium text-white transition-colors
					{offset > 0 ? 'justify-start' : 'justify-end'} {swipe.far(key) ? (action === 'remove' ? 'bg-accent' : 'bg-d-green') : 'bg-line'}"
			>
				<Icon name={ACTION_ICONS[action]} size={22} />
				{ACTION_NAMES[action]}
			</div>
		{/if}
	<div
		class="group relative flex touch-pan-y items-center gap-3 rounded-md px-2 py-2 {dragging ? 'bg-raised' : offset !== 0 ? 'bg-surface' : 'hover:bg-raised'} {offset !== 0 ? '' : 'transition-transform duration-200'}"
		style:transform="translateX({offset}px)"
		role="listitem"
		onpointerdown={(e) => (pointer = e.pointerType)}
		oncontextmenu={(e) => held(e, song, i)}
		ontouchstart={(e) => swipe.start(e, key)}
		ontouchmove={(e) => swipe.move(e, (way) => { const a = gestures.list[way]; return a !== 'none' && allowed(a, song); })}
		ontouchend={(e) => swiped(song, i, e.currentTarget as HTMLElement)}
		ontouchcancel={() => swipe.cancel()}
	>
		<button class="flex min-w-0 flex-1 items-center gap-3 text-left" onclick={(e) => act('tap', song, i, e, e.currentTarget.parentElement)} title={ACTION_NAMES[gestures.list.tap]}>
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
			</div>
			<!-- Some listings have no length (episodes in search); it's known once it plays. -->
			<span class="text-sm text-muted tabular-nums">{song.durationMs ? formatTime(song.durationMs) : ''}</span>
		</button>
		<button class="rounded-full p-2 text-muted hover:bg-line hover:text-white" aria-label="More for {song.title}" onclick={(e) => menu(e, song, i, false)}>
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

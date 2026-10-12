<script lang="ts">
	// A list of songs (search, find similar, artist and album pages). Tap = play next;
	// ⋮ or right-click = song menu.
	import { artistNames, formatTime } from '../format';
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

	async function playNext(song: Song) {
		const error = await room.run({ kind: 'AddSongs', songs: [song], position: 'next' });
		onToast(error ? error.message : `Playing "${song.title}" next`);
	}

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
	<div
		data-row
		class="group relative flex items-center gap-3 rounded-md px-2 py-2 hover:bg-raised {dragging ? 'z-10 bg-raised shadow-xl' : drag ? 'transition-transform' : ''}"
		style:transform="translateY({dragging ? drag!.offset : shift(i)}px)"
		role="listitem"
		oncontextmenu={(e) => menu(e, song, i, true)}
	>
		<button class="flex min-w-0 flex-1 items-center gap-3 text-left" onclick={() => playNext(song)} title="Play next">
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
{/each}
</div>

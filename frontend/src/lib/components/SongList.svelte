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
		onToast,
		onMenu
	}: {
		room: RoomConnection;
		songs: Song[];
		/** Track numbers instead of art (album pages). */
		numbered?: boolean;
		showAlbum?: boolean;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
	} = $props();

	async function playNext(song: Song) {
		const error = await room.run({ kind: 'AddSongs', songs: [song], position: 'next' });
		onToast(error ? error.message : `Playing "${song.title}" next`);
	}

	function menu(event: MouseEvent, song: Song, atPointer: boolean) {
		event.preventDefault();
		event.stopPropagation();
		const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
		onMenu({ song, place: 'search', x: atPointer ? event.clientX : rect.right - 256, y: atPointer ? event.clientY : rect.bottom });
	}
</script>

{#each songs as song, i (song.id + i)}
	<div class="group relative flex items-center gap-3 rounded-md px-2 py-2 hover:bg-raised" role="listitem" oncontextmenu={(e) => menu(e, song, true)}>
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
			<span class="text-sm text-muted tabular-nums">{formatTime(song.durationMs)}</span>
		</button>
		<button class="rounded-full p-2 text-muted hover:bg-line hover:text-white" aria-label="More for {song.title}" onclick={(e) => menu(e, song, false)}>
			<Icon name="more" />
		</button>
	</div>
{/each}

<script lang="ts">
	// Songs in a grid of (up to) four rows that scrolls sideways, like YTM's quick picks (Home): tap = play
	// next, ⋮ or right-click = song menu.
	import { artistNames } from '../format';
	import { menuAt, playNext, quoted } from '../songActions';
	import type { Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import PageDots from './PageDots.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let { room, songs, onToast, onMenu }: { room: RoomConnection; songs: Song[]; onToast: (text: string) => void; onMenu: (target: MenuTarget) => void } = $props();

	let scroller = $state<HTMLElement>();

	function menu(event: MouseEvent, song: Song, atPointer: boolean) {
		event.preventDefault();
		event.stopPropagation();
		onMenu({ song, place: 'search', ...menuAt(event.currentTarget as HTMLElement, event, atPointer) });
	}
</script>

<div
	bind:this={scroller}
	class="grid snap-x grid-flow-col gap-x-2 overflow-x-auto px-0 [scrollbar-width:none]
		{songs.length > 4 ? 'auto-cols-[88%] sm:auto-cols-[24rem]' : 'auto-cols-[100%] sm:auto-cols-[32rem]'}"
	style:grid-template-rows="repeat({Math.min(4, songs.length)}, auto)"
>
	{#each songs as song, j (song.id + j)}
		<div class="flex min-w-0 snap-start items-center gap-1 rounded-md hover:bg-raised" role="listitem" oncontextmenu={(e) => menu(e, song, true)}>
			<button class="flex min-w-0 flex-1 items-center gap-3 px-2 py-1.5 text-left" title="Play next" onclick={() => playNext(room, [song], quoted(song), onToast)}>
				<Art {song} size={96} class="h-12 w-12" />
				<div class="min-w-0 flex-1">
					<div class="truncate font-medium">{song.title}</div>
					<div class="truncate text-sm text-muted">{artistNames(song)}{song.album ? ` • ${song.album.name}` : ''}</div>
				</div>
			</button>
			<button class="shrink-0 rounded-full p-2 text-muted hover:bg-line hover:text-white" aria-label="More for {song.title}" onclick={(e) => menu(e, song, false)}>
				<Icon name="more" size={20} />
			</button>
		</div>
	{/each}
</div>
<PageDots target={scroller} />

<script lang="ts">
	import { playingMark } from '../playingMark';
	// Songs in a grid of (up to) four rows that scrolls sideways, like YTM's quick picks (Home): tap = what
	// Tap does in song lists, ⋮ or right-click = song menu.
	import { artistNames } from '../format';
	import { ACTION_NAMES, gestures, moreButtonClass } from '../gestures.svelte';
	import { menuAt, tapSong } from '../songActions';
	import type { Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import PageDots from './PageDots.svelte';
	import type { MenuTarget } from './SongMenu.svelte';
	import { screen } from '../screen.svelte';

	/** [onScrolls]: whether it scrolls sideways (Home shows ‹ › only then). */
	let {
		room,
		songs,
		onToast,
		onMenu,
		onScrolls
	}: { room: RoomConnection; songs: Song[]; onToast: (text: string) => void; onMenu: (target: MenuTarget) => void; onScrolls?: (scrolls: boolean) => void } = $props();

	let scroller = $state<HTMLElement>();
	let width = $state(0);
	/** A column on a computer is at least this wide (px), 8 px apart. */
	const MIN_COLUMN = 256;
	/** Columns that fit side by side on a computer. */
	const fit = $derived(Math.max(1, Math.floor((width + 8) / (MIN_COLUMN + 8))));
	/** On a computer, all the songs across the width without scrolling when they fit in up to 5
	 * rows (20 songs: 5 columns of 4, or 4 of 5 in a narrower window); else 4 rows that scroll,
	 * whole columns a screen. Phones: 4 rows that scroll. */
	const stretched = $derived(screen.wide && songs.length > 4 && Math.ceil(songs.length / fit) <= 5);
	const rows = $derived(stretched ? Math.ceil(songs.length / Math.min(fit, Math.ceil(songs.length / 4))) : Math.min(4, songs.length));
	const columns = $derived(Math.ceil(songs.length / Math.max(1, rows)));
	$effect(() => {
		onScrolls?.(columns > 1 && !stretched);
	});

	function menu(event: MouseEvent, song: Song, atPointer: boolean) {
		event.preventDefault();
		event.stopPropagation();
		onMenu({ song, place: 'search', ...menuAt(event.currentTarget as HTMLElement, event, atPointer) });
	}
</script>

<div
	bind:this={scroller}
	data-carousel
	bind:clientWidth={width}
	class="grid snap-x grid-flow-col gap-x-2 overflow-x-auto px-0 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden
		{songs.length > 4 ? 'auto-cols-[88%] sm:auto-cols-[24rem]' : 'auto-cols-[100%] sm:auto-cols-[32rem]'}"
	style:grid-template-rows="repeat({rows}, auto)"
	style:grid-template-columns={stretched ? `repeat(${columns}, minmax(0, 1fr))` : undefined}
	style:grid-auto-columns={screen.wide && !stretched && columns > 1 && width ? `${(width - (fit - 1) * 8) / fit}px` : undefined}
>
	{#each songs as song, j (song.id + j)}
		<div class="flex min-w-0 snap-start items-center gap-1 rounded-md hover:bg-raised" role="listitem" oncontextmenu={(e) => menu(e, song, true)}>
			<button class="flex min-w-0 flex-1 items-center gap-3 px-2 py-1.5 text-left" title={ACTION_NAMES[gestures.list.tap]} onclick={(e) => tapSong(room, song, onToast, () => menu(e, song, false))}>
				<Art {song} size={96} class="h-12 w-12" playing={playingMark(room, song.id)} />
				<div class="min-w-0 flex-1">
					<div class="truncate font-medium">{song.title}</div>
					<div class="truncate text-sm text-muted">{artistNames(song)}{song.album ? ` • ${song.album.name}` : ''}</div>
				</div>
			</button>
			<button class="tap shrink-0 rounded-full p-2 text-muted hover:bg-line hover:text-white {moreButtonClass(true)}" aria-label="More for {song.title}" onclick={(e) => menu(e, song, false)}>
				<Icon name="more" size={20} />
			</button>
		</div>
	{/each}
</div>
<PageDots target={scroller} />

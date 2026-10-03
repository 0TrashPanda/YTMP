<script lang="ts">
	// The home page, like YTM's: rows of suggestions. Songs (quick picks) are a grid of rows
	// that scrolls sideways (tap = play next, ⋮ or right-click = song menu); albums,
	// playlists, artists and podcasts are cards that open their page.
	import { getHome } from '../api';
	import { itemCard, type PageHandlers } from '../cards';
	import { artistNames } from '../format';
	import type { HomePage, SearchItem, Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import CardGrid from './CardGrid.svelte';
	import Icon from './Icon.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let {
		room,
		onToast,
		onMenu,
		...open
	}: { room: RoomConnection; onToast: (text: string) => void; onMenu: (target: MenuTarget) => void } & PageHandlers = $props();

	let home = $state<HomePage | null>(null);
	let error = $state<string | null>(null);

	getHome()
		.then((h) => (home = h))
		.catch((e) => (error = e instanceof Error ? e.message : 'Could not load suggestions'));

	const songs = (items: SearchItem[]): Song[] => items.flatMap((i) => (i.kind === 'song' ? [i.song] : []));
	const cards = (items: SearchItem[]) => items.flatMap((i) => itemCard(i, open) ?? []);

	async function playNext(song: Song) {
		const e = await room.run({ kind: 'AddSongs', songs: [song], position: 'next' });
		onToast(e ? e.message : `Playing "${song.title}" next`);
	}

	function menu(event: MouseEvent, song: Song, atPointer: boolean) {
		event.preventDefault();
		event.stopPropagation();
		const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
		onMenu({ song, place: 'search', x: atPointer ? event.clientX : rect.right - 256, y: atPointer ? event.clientY : rect.bottom });
	}
</script>

<div class="flex flex-col gap-8 pb-4">
	{#if error}
		<div class="flex flex-col items-center gap-2 py-16 text-center text-muted">
			<Icon name="search" size={48} />
			<p>Search for a song to start the music.</p>
		</div>
	{:else if !home}
		<!-- Placeholders the size of the real thing, so the page doesn't jump. -->
		{#each [0, 1, 2] as i (i)}
			<div class="flex animate-pulse flex-col gap-3 px-2">
				<div class="h-6 w-40 rounded bg-raised"></div>
				<div class="flex gap-4 overflow-hidden">
					{#each [0, 1, 2, 3, 4, 5] as j (j)}
						<div class="aspect-square w-[9.5rem] shrink-0 rounded-md bg-raised sm:w-[11rem]"></div>
					{/each}
				</div>
			</div>
		{/each}
	{:else}
		{#each home.sections as section, i (section.title + i)}
			{@const list = songs(section.items)}
			{@const others = cards(section.items)}
			<section class="flex flex-col gap-3">
				<h2 class="px-2 text-xl font-bold sm:text-2xl">{section.title}</h2>
				{#if list.length}
					<div
						class="grid snap-x grid-flow-col grid-rows-4 gap-x-2 overflow-x-auto px-0 [scrollbar-width:none]
							{list.length > 4 ? 'auto-cols-[88%] sm:auto-cols-[24rem]' : 'auto-cols-[100%] sm:auto-cols-[32rem]'}"
					>
						{#each list as song, j (song.id + j)}
							<div class="flex min-w-0 snap-start items-center gap-1 rounded-md hover:bg-raised" role="listitem" oncontextmenu={(e) => menu(e, song, true)}>
								<button class="flex min-w-0 flex-1 items-center gap-3 px-2 py-1.5 text-left" title="Play next" onclick={() => playNext(song)}>
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
				{/if}
				{#if others.length}
					<CardGrid cards={others} row />
				{/if}
			</section>
		{/each}
	{/if}
</div>

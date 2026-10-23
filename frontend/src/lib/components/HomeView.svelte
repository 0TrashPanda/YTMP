<script lang="ts">
	// The home page, like YTM's: rows of suggestions. Songs (quick picks) are a grid of rows
	// that scrolls sideways (tap = play next, ⋮ or right-click = song menu); albums,
	// playlists, artists and podcasts are cards that open their page. Each section's ⋮ hides it,
	// moves it, or shows it as a list (songs then tap and swipe like any song list); Settings → Home
	// has all of that for every section (home.svelte.ts).
	import { getHome } from '../api';
	import { menuAt, playNext, quoted } from '../songActions';
	import { itemCard, type PageHandlers } from '../cards';
	import { artistNames } from '../format';
	import { arrange, home as prefs, nudge, seen, setHidden, setLayout } from '../home.svelte';
	import type { HomePage, SearchItem, Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import CardGrid from './CardGrid.svelte';
	import Icon from './Icon.svelte';
	import PageDots from './PageDots.svelte';
	import SongList from './SongList.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let {
		room,
		onToast,
		onMenu,
		onSettings,
		...open
	}: {
		room: RoomConnection;
		onToast: (text: string) => void;
		onMenu: (target: MenuTarget) => void;
		/** Opens Settings → Home. */
		onSettings: () => void;
	} & PageHandlers = $props();

	let home = $state<HomePage | null>(null);
	let error = $state<string | null>(null);
	/** The quick picks' sideways-scrolling rows, per section, for their page dots. */
	let songRows = $state<HTMLElement[]>([]);

	const sections = $derived(home ? arrange(home.sections, prefs) : []);
	/** The section whose ⋮ menu is open. */
	let sectionMenu = $state<string | null>(null);

	getHome()
		.then((h) => {
			home = h;
			seen(h.sections);
		})
		.catch((e) => (error = e instanceof Error ? e.message : 'Could not load suggestions'));

	const songs = (items: SearchItem[]): Song[] => items.flatMap((i) => (i.kind === 'song' ? [i.song] : []));
	const cards = (items: SearchItem[]) => items.flatMap((i) => itemCard(i, open) ?? []);

	const playSongNext = (song: Song) => playNext(room, [song], quoted(song), onToast);

	function sectionAction(title: string, action: 'up' | 'down' | 'layout' | 'hide') {
		sectionMenu = null;
		const shown = sections.map((s) => s.title);
		if (action === 'up' || action === 'down') nudge(title, action === 'up' ? -1 : 1, shown);
		else if (action === 'layout') setLayout(title, sections.find((s) => s.title === title)?.layout === 'list' ? 'carousel' : 'list');
		else {
			setHidden(title, true);
			onToast(`“${title}” won't show on Home. Settings → Home brings it back.`);
		}
	}

	function menu(event: MouseEvent, song: Song, atPointer: boolean) {
		event.preventDefault();
		event.stopPropagation();
		onMenu({ song, place: 'search', ...menuAt(event.currentTarget as HTMLElement, event, atPointer) });
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
		{#each sections as section, i (section.title)}
			{@const list = songs(section.items)}
			{@const others = cards(section.items)}
			<section class="flex flex-col gap-3">
				<div class="relative flex items-center gap-2 px-2">
					<h2 class="min-w-0 flex-1 truncate text-xl font-bold sm:text-2xl">{section.title}</h2>
					<button
						class="shrink-0 rounded-full p-1.5 text-muted hover:bg-raised hover:text-white"
						aria-label="Options for {section.title}"
						aria-expanded={sectionMenu === section.title}
						onclick={() => (sectionMenu = sectionMenu === section.title ? null : section.title)}
					>
						<Icon name="more" size={20} />
					</button>
					{#if sectionMenu === section.title}
						<!-- A tap anywhere else closes it. -->
						<button class="fixed inset-0 z-[55] cursor-default" aria-label="Close" onclick={() => (sectionMenu = null)}></button>
						<div class="absolute top-full right-2 z-[56] w-52 overflow-hidden rounded-lg bg-raised py-1 shadow-2xl ring-1 ring-line" role="menu">
							{#each [['up', 'Move up', i > 0], ['down', 'Move down', i < sections.length - 1], ['layout', section.layout === 'list' ? 'Show as carousel' : 'Show as list', true], ['hide', 'Hide from Home', true]] as const as [action, label, shown] (action)}
								{#if shown}
									<button class="block w-full px-4 py-2.5 text-left text-sm hover:bg-line" role="menuitem" onclick={() => sectionAction(section.title, action)}>{label}</button>
								{/if}
							{/each}
							<button class="block w-full px-4 py-2.5 text-left text-sm hover:bg-line" role="menuitem" onclick={() => ((sectionMenu = null), onSettings())}>Edit Home…</button>
						</div>
					{/if}
				</div>
				{#if list.length && section.layout === 'list'}
					<SongList {room} songs={list} {onToast} {onMenu} />
				{:else if list.length}
					<div
						bind:this={songRows[i]}
						class="grid snap-x grid-flow-col grid-rows-4 gap-x-2 overflow-x-auto px-0 [scrollbar-width:none]
							{list.length > 4 ? 'auto-cols-[88%] sm:auto-cols-[24rem]' : 'auto-cols-[100%] sm:auto-cols-[32rem]'}"
					>
						{#each list as song, j (song.id + j)}
							<div class="flex min-w-0 snap-start items-center gap-1 rounded-md hover:bg-raised" role="listitem" oncontextmenu={(e) => menu(e, song, true)}>
								<button class="flex min-w-0 flex-1 items-center gap-3 px-2 py-1.5 text-left" title="Play next" onclick={() => playSongNext(song)}>
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
					<PageDots target={songRows[i]} />
				{/if}
				{#if others.length}
					<CardGrid cards={others} row={section.layout === 'carousel'} list={section.layout === 'list'} />
				{/if}
			</section>
		{/each}
		{#if home.sections.length > sections.length}
			<button class="mx-auto rounded-full px-4 py-2 text-sm text-muted hover:bg-raised hover:text-white" onclick={onSettings}>
				Some sections are hidden · Edit Home
			</button>
		{/if}
	{/if}
</div>

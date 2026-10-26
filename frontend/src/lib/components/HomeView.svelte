<script lang="ts">
	// The home page, like YTM's: rows of suggestions. Songs (quick picks) are a grid of rows
	// that scrolls sideways (tap = play next, ⋮ or right-click = song menu); albums,
	// playlists, artists and podcasts are cards that open their page. Each section's ⋮ hides it,
	// moves it, or shows it as a list (songs then tap and swipe like any song list); Settings → Home
	// has all of that for every section (home.svelte.ts), and a speed dial (YTM's 3×3 covers).
	// Your pins: tiles in the Pinned section (a speed dial),
	// or each its own section with its songs. They show at once: nothing to wait for.
	import { getHome, lastSeen } from '../api';
	import { itemCard, type PageHandlers } from '../cards';
	import { cardTiles, songTiles } from '../dial';
	import { hideIfBroken } from '../pictures';
	import { arrange, home as prefs, nudge, seen, setHidden, setLayout, setPinContents, togglePin, type Block, type Layout, type Pin } from '../home.svelte';
	import type { HomePage, SearchItem, Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import CardGrid from './CardGrid.svelte';
	import Icon from './Icon.svelte';
	import PinContents from './PinContents.svelte';
	import SongCarousel from './SongCarousel.svelte';
	import SongList from './SongList.svelte';
	import SpeedDial from './SpeedDial.svelte';
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

	// The last one, until the new one is here.
	let home = $state<HomePage | null>(lastSeen('home'));
	let error = $state<string | null>(null);

	const sections = $derived(arrange(home?.sections ?? null, prefs));
	/** The section whose ⋮ menu is open (its key). */
	let sectionMenu = $state<string | null>(null);

	getHome()
		.then((h) => {
			home = h;
			seen(h.sections);
		})
		.catch((e) => (error = e instanceof Error ? e.message : 'Could not load suggestions'));

	const songs = (items: SearchItem[]): Song[] => items.flatMap((i) => (i.kind === 'song' ? [i.song] : []));
	const cards = (items: SearchItem[]) => items.flatMap((i) => itemCard(i, open) ?? []);

	function openPin(pin: Pin) {
		if (pin.kind === 'playlist') open.onPlaylist({ id: pin.id, title: pin.title }, pin.personal);
		else if (pin.kind === 'album') open.onAlbum({ id: pin.id, name: pin.title });
		else if (pin.kind === 'artist') open.onArtist({ id: pin.id, name: pin.title });
		else open.onPodcast({ id: pin.id, name: pin.title });
	}

	type Action = 'up' | 'down' | Layout | 'tile' | 'unpin' | 'hide';

	/** What a section's ⋮ offers. */
	function actions(block: Block, i: number): [Action, string][] {
		const layouts: [Layout, string][] = [
			['dial', 'Show as speed dial'],
			['carousel', block.kind === 'pinned' ? 'Show as tiles' : 'Show as carousel'],
			['list', 'Show as list']
		];
		return [
			...(i > 0 ? [['up', 'Move up'] as [Action, string]] : []),
			...(i < sections.length - 1 ? [['down', 'Move down'] as [Action, string]] : []),
			...layouts.filter(([l]) => l !== block.layout),
			...(block.kind === 'pin'
				? [
						['tile', 'Show as a tile in Pinned'] as [Action, string],
						['unpin', 'Unpin'] as [Action, string]
					]
				: []),
			['hide', 'Hide from Home']
		];
	}

	function sectionAction(block: Block, action: Action) {
		sectionMenu = null;
		const shown = sections.map((s) => s.key);
		if (action === 'up' || action === 'down') nudge(block.key, action === 'up' ? -1 : 1, shown);
		else if (action === 'dial' || action === 'carousel' || action === 'list') setLayout(block.key, action);
		else if (block.kind === 'pin' && action === 'tile') setPinContents(block.pin, false);
		else if (block.kind === 'pin' && action === 'unpin') togglePin(block.pin);
		else {
			setHidden(block.key, true);
			onToast(`“${block.title}” won't show on Home. Settings → Home brings it back.`);
		}
	}
</script>

{#snippet header(block: Block, i: number)}
	<div class="relative flex items-center gap-2 px-2">
		<h2 class="min-w-0 flex-1 truncate text-xl font-bold sm:text-2xl">{block.title}</h2>
		{#if block.kind === 'pin'}
			<button class="shrink-0 rounded-full border border-line px-3 py-1 text-sm font-medium hover:bg-raised" onclick={() => openPin(block.pin)}>More</button>
		{/if}
		<button
			class="shrink-0 rounded-full p-1.5 text-muted hover:bg-raised hover:text-white"
			aria-label="Options for {block.title}"
			aria-expanded={sectionMenu === block.key}
			onclick={() => (sectionMenu = sectionMenu === block.key ? null : block.key)}
		>
			<Icon name="more" size={20} />
		</button>
		{#if sectionMenu === block.key}
			<!-- A tap anywhere else closes it. -->
			<button class="fixed inset-0 z-[55] cursor-default" aria-label="Close" onclick={() => (sectionMenu = null)}></button>
			<div class="absolute top-full right-2 z-[56] w-56 overflow-hidden rounded-lg bg-raised py-1 shadow-2xl ring-1 ring-line" role="menu">
				{#each actions(block, i) as [action, label] (action)}
					<button class="block w-full px-4 py-2.5 text-left text-sm hover:bg-line" role="menuitem" onclick={() => sectionAction(block, action)}>{label}</button>
				{/each}
				<button class="block w-full px-4 py-2.5 text-left text-sm hover:bg-line" role="menuitem" onclick={() => ((sectionMenu = null), onSettings())}>Edit Home…</button>
			</div>
		{/if}
	</div>
{/snippet}

{#snippet tile(pin: Pin, list: boolean)}
	{@const round = pin.kind === 'artist' ? 'rounded-full' : ''}
	<button
		class="flex min-w-0 items-center gap-3 overflow-hidden rounded-md text-left {list ? 'px-2 py-1.5 hover:bg-raised' : 'bg-raised hover:bg-line'}"
		onclick={() => openPin(pin)}
	>
		<div class="relative grid h-12 w-12 shrink-0 place-items-center overflow-hidden bg-line text-muted {list ? `rounded ${round}` : round}">
			<Icon name={pin.kind === 'artist' ? 'person' : pin.kind === 'podcast' ? 'podcast' : 'note'} size={24} class="absolute" />
			{#if pin.art}
				<img src={pin.art} alt="" use:hideIfBroken referrerpolicy="no-referrer" class="relative h-full w-full object-cover" />
			{/if}
		</div>
		<span class="min-w-0 flex-1 pr-2 {list ? 'truncate font-medium' : 'line-clamp-2 text-sm font-medium'}">{pin.title}</span>
	</button>
{/snippet}

<div class="flex flex-col gap-8 pb-4">
	{#each sections as block, i (block.key)}
		<section class="flex flex-col gap-3">
			{@render header(block, i)}
			{#if block.kind === 'pinned' && block.layout === 'dial'}
				<SpeedDial
					tiles={block.pins.map((pin) => ({
						key: pin.kind + pin.id,
						title: pin.title,
						art: pin.art,
						icon: pin.kind === 'artist' ? 'person' : pin.kind === 'podcast' ? 'podcast' : 'note',
						open: () => openPin(pin)
					}))}
				/>
			{:else if block.kind === 'pinned'}
				<div class={block.layout === 'list' ? 'flex flex-col' : 'grid grid-cols-2 gap-2 px-2 sm:grid-cols-3 lg:grid-cols-4'}>
					{#each block.pins as pin (pin.kind + pin.id)}
						{@render tile(pin, block.layout === 'list')}
					{/each}
				</div>
			{:else if block.kind === 'pin'}
				<PinContents {room} pin={block.pin} layout={block.layout} {onToast} {onMenu} />
			{:else}
				{@const list = songs(block.section.items)}
				{@const others = cards(block.section.items)}
				{#if block.layout === 'dial'}
					<SpeedDial tiles={[...songTiles(list, room, onToast, onMenu), ...cardTiles(others)]} />
				{:else if list.length && block.layout === 'list'}
					<SongList {room} songs={list} {onToast} {onMenu} />
				{:else if list.length}
					<SongCarousel {room} songs={list} {onToast} {onMenu} />
				{/if}
				{#if others.length && block.layout !== 'dial'}
					<CardGrid cards={others} row={block.layout === 'carousel'} list={block.layout === 'list'} />
				{/if}
			{/if}
		</section>
	{/each}
	{#if error && !home}
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
	{:else if sections.length < arrange(home.sections, { ...prefs, hidden: [], hidePodcasts: false }).length}
		<button class="mx-auto rounded-full px-4 py-2 text-sm text-muted hover:bg-raised hover:text-white" onclick={onSettings}>
			Some sections are hidden · Edit Home
		</button>
	{/if}
</div>

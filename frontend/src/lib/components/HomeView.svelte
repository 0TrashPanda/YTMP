<script lang="ts">
	import { scrollBehavior } from '../motion.svelte';
	// The home page, like YTM's: rows of suggestions. Songs (quick picks) are a grid of rows
	// that scrolls sideways (tap = play next, ⋮ or right-click = song menu); albums,
	// playlists, artists and podcasts are cards that open their page. Each section's ⋮ hides it,
	// moves it, or shows it as a list (songs then tap and swipe like any song list); Settings → Home
	// has all of that for every section (home.svelte.ts), and a speed dial (YTM's 3×3 covers).
	// Your pins: tiles in the Pinned section (a speed dial),
	// or each its own section with its songs. They show at once: nothing to wait for.
	import { keepInView } from '../keepInView';
	import { getHome, homeRefreshAsked, lastSeen } from '../api';
	import { itemCard, type PageHandlers } from '../cards';
	import { cardTiles, songTiles } from '../dial';
	import { hideIfBroken } from '../pictures';
	import { arrange, home as prefs, nudge, seen, setHidden, setLayout, setPinContents, togglePin, type Block, type Layout, type Pin } from '../home.svelte';
	import { friends } from '../friends.svelte';
	import FriendShares from './FriendShares.svelte';
	import type { HomePage, SearchItem, Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import CardGrid, { type Card } from './CardGrid.svelte';
	import { artistNames } from '../format';
	import { smallUrl, thumbUrl } from '../images.svelte';
	import Icon from './Icon.svelte';
	import PinContents from './PinContents.svelte';
	import SongCarousel from './SongCarousel.svelte';
	import SongList from './SongList.svelte';
	import SpeedDial from './SpeedDial.svelte';
	import type { MenuTarget } from './SongMenu.svelte';
	import type { Toast } from '../songActions';
	import { menuAt, tapSong } from '../songActions';
	import { openCardMenu } from '../cardMenu.svelte';
	import { youtube } from '../youtube.svelte';
	import type { DialTile } from './SpeedDial.svelte';

	let {
		room,
		onToast,
		onMenu,
		onSettings,
		onFriends,
		...open
	}: {
		room: RoomConnection;
		onToast: Toast;
		onMenu: (target: MenuTarget) => void;
		/** Opens Settings → Home. */
		onSettings: () => void;
		/** Opens your friends and groups (Settings → Friends). */
		onFriends: () => void;
	} & PageHandlers = $props();

	// The last one, until the new one is here.
	let home = $state<HomePage | null>(lastSeen('home'));
	let error = $state<string | null>(null);

	const sections = $derived(arrange(home?.sections ?? null, prefs, friends.shares ?? []));
	/** Which sections' rows scroll sideways (`<key>:songs`, `<key>:cards`): ‹ › show only for those. */
	const scrolling = $state<Record<string, boolean>>({});
	/** The section whose ⋮ menu is open (its key). */
	let sectionMenu = $state<string | null>(null);

	let loadingAgain = $state(false);
	/** How long the bar shows at least when you asked for new suggestions. */
	const SHOWN_AT_LEAST_MS = 700;
	/** New suggestions are on their way (pulled down, the logo, or old ones): the bar at the top runs. */
	let loading = $state(false);
	function load() {
		error = null;
		// Asked for (the logo, pulling down): the bar shows at once and stays a moment, so you see
		// it happened even when they come fast. By itself: only when it takes a while.
		const asked = homeRefreshAsked();
		const since = Date.now();
		const shown = asked ? ((loading = true), undefined) : setTimeout(() => (loading = true), 150);
		getHome()
			.then((h) => {
				home = h;
				seen(h.sections, friends.shares ?? []);
			})
			.catch((e) => (error = e instanceof Error ? e.message : 'Could not load suggestions'))
			.finally(() => {
				clearTimeout(shown);
				loadingAgain = false;
				setTimeout(() => (loading = false), asked ? Math.max(0, SHOWN_AT_LEAST_MS - (Date.now() - since)) : 0);
			});
	}
	load();
	// What friends shared with you: each time Home shows.
	void friends.loadShares().then((shares) => home && seen(home.sections, shares));

	const songs = (items: SearchItem[]): Song[] => items.flatMap((i) => (i.kind === 'song' ? [i.song] : []));
	const cards = (items: SearchItem[]) => items.flatMap((i) => itemCard(i, open) ?? []);
	/** A row with songs and albums/artists/playlists mixed: all of them cards, in YTM's order (a
	 * song's card plays it like a tap in a song list; ⋮ or right-click: the song menu). */
	const mixedCards = (items: SearchItem[]): Card[] =>
		items.flatMap((item, i) => {
			if (item.kind !== 'song') return itemCard(item, open) ?? [];
			const song = item.song;
			const songMenu = (event: MouseEvent, atPointer: boolean) => onMenu({ song, place: 'search', ...menuAt(event.currentTarget as HTMLElement, event, atPointer) });
			return {
				key: `song:${song.id}:${i}`,
				title: song.title,
				subtitle: artistNames(song),
				art: thumbUrl(song.thumbnails, 400),
				small: smallUrl(song.thumbnails, 400),
				open: () => tapSong(room, song, onToast),
				songMenu
			};
		});

	/** All the songs Home suggests, for a speed dial's *Random song*. */
	const homeSongs = $derived(sections.flatMap((b) => (b.kind === 'ytm' ? songs(b.section.items) : [])));
	/** Tiles that fill a speed dial's gaps (SpeedDial: extras): Liked music (signed in), and a
	 * random song from Home that isn't in [shown] (what Tap does in song lists). */
	function dialExtras(shown: Song[] = []): DialTile[] {
		const others = homeSongs.filter((s) => !shown.some((t) => t.id === s.id));
		return [
			...(youtube.account
				? [{ key: 'playlistLM', title: 'Liked music', art: undefined, icon: 'liked' as const, open: () => open.onPlaylist({ id: 'LM', title: 'Liked music' }, true) }]
				: []),
			...(others.length
				? [{ key: 'ytmp:random', title: 'Random song', art: undefined, icon: 'shuffle' as const, open: () => tapSong(room, others[Math.floor(Math.random() * others.length)], onToast) }]
				: [])
		];
	}

	/** ‹ or › of a section: its row moves along by most of its width. */
	function slide(event: MouseEvent, way: -1 | 1) {
		const row = (event.currentTarget as HTMLElement).closest('section')?.querySelector<HTMLElement>('[data-carousel]');
		row?.scrollBy({ left: way * row.clientWidth * 0.9, behavior: scrollBehavior() });
	}

	/** Right-click a pinned album, playlist, artist or podcast: its menu (as on a card). */
	function pinMenu(pin: Pin, event: MouseEvent) {
		event.preventDefault();
		openCardMenu({ kind: pin.kind, id: pin.id, title: pin.title, subtitle: '', art: pin.art }, menuAt(event.currentTarget as HTMLElement, event, true));
	}

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
		<div class="min-w-0 flex-1">
			{#if block.kind === 'ytm' && block.section.strapline}
				<!-- Like YTM: "Similar to" over an artist's name. -->
				<p class="truncate text-xs font-medium tracking-wide text-muted uppercase">{block.section.strapline}</p>
			{/if}
			<h2 class="truncate text-xl font-bold sm:text-2xl">{block.title}</h2>
		</div>
		<!-- With a mouse, like YTM: ‹ › scroll the row a screen's width (phones swipe it). -->
		{#if block.layout === 'carousel' && (block.kind === 'ytm' || block.kind === 'pin') && (scrolling[`${block.key}:songs`] || scrolling[`${block.key}:cards`])}
			<div class="hidden shrink-0 gap-2 pointer-fine:flex">
				<button class="grid h-9 w-9 place-items-center rounded-full border border-line hover:bg-raised" aria-label="Back in {block.title}" onclick={(e) => slide(e, -1)}>
					<Icon name="chevron" size={22} class="rotate-180" />
				</button>
				<button class="grid h-9 w-9 place-items-center rounded-full border border-line hover:bg-raised" aria-label="More in {block.title}" onclick={(e) => slide(e, 1)}>
					<Icon name="chevron" size={22} />
				</button>
			</div>
		{/if}
		{#if block.kind === 'pin'}
			<button class="shrink-0 rounded-full border border-line px-3 py-1 text-sm font-medium hover:bg-raised" onclick={() => openPin(block.pin)}>More</button>
		{:else if block.kind === 'friends'}
			<button class="shrink-0 rounded-full border border-line px-3 py-1 text-sm font-medium hover:bg-raised" onclick={onFriends}>Friends</button>
		{/if}
		<button
			class="tap shrink-0 rounded-full p-1.5 text-muted hover:bg-raised hover:text-white"
			aria-label="Options for {block.title}"
			aria-expanded={sectionMenu === block.key}
			onclick={() => (sectionMenu = sectionMenu === block.key ? null : block.key)}
		>
			<Icon name="more" size={20} />
		</button>
		{#if sectionMenu === block.key}
			<!-- A tap anywhere else closes it. -->
			<button class="fixed inset-0 z-[55] cursor-default" aria-label="Close" onclick={() => (sectionMenu = null)}></button>
			<div use:keepInView class="absolute top-full right-2 z-[56] w-56 overflow-hidden rounded-lg bg-raised py-1 shadow-2xl ring-1 ring-line" role="menu">
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
		oncontextmenu={(e) => pinMenu(pin, e)}
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

{#if loading}
	<div class="pointer-events-none sticky top-0 z-20 -mb-[3px] h-[3px] overflow-hidden bg-accent/20" role="progressbar" aria-label="Loading new suggestions">
		<div class="loading-run h-full w-2/5 bg-accent"></div>
	</div>
{/if}
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
						open: () => openPin(pin),
						menu: (e: MouseEvent) => pinMenu(pin, e)
					}))}
					all
					extras={dialExtras()}
				/>
			{:else if block.kind === 'pinned'}
				<div class={block.layout === 'list' ? 'flex flex-col' : 'grid grid-cols-2 gap-2 px-2 sm:grid-cols-3 lg:grid-cols-4'}>
					{#each block.pins as pin (pin.kind + pin.id)}
						{@render tile(pin, block.layout === 'list')}
					{/each}
				</div>
			{:else if block.kind === 'pin'}
				<PinContents {room} pin={block.pin} layout={block.layout} {onToast} {onMenu} onScrolls={(s) => (scrolling[`${block.key}:songs`] = s)} />
			{:else if block.kind === 'friends' && block.layout === 'dial'}
				<SpeedDial tiles={songTiles(block.shares.map((s) => s.song), room, onToast, onMenu)} extras={dialExtras(block.shares.map((s) => s.song))} />
			{:else if block.kind === 'friends'}
				<FriendShares {room} shares={block.shares} list={block.layout === 'list'} {onToast} {onMenu} />
			{:else}
				{@const list = songs(block.section.items)}
				{@const others = cards(block.section.items)}
				{#if block.layout === 'dial'}
					<SpeedDial tiles={[...songTiles(list, room, onToast, onMenu), ...cardTiles(others)]} extras={dialExtras(list)} />
				{:else if list.length && others.length && block.layout === 'carousel'}
					<CardGrid cards={mixedCards(block.section.items)} row onScrolls={(s) => (scrolling[`${block.key}:cards`] = s)} />
				{:else if list.length && block.layout === 'list'}
					<SongList {room} songs={list} {onToast} {onMenu} />
				{:else if list.length}
					<SongCarousel {room} songs={list} {onToast} {onMenu} onScrolls={(s) => (scrolling[`${block.key}:songs`] = s)} />
				{/if}
				{#if others.length && (block.layout === 'list' || (block.layout === 'carousel' && !list.length))}
					<CardGrid cards={others} row={block.layout === 'carousel'} list={block.layout === 'list'} onScrolls={(s) => (scrolling[`${block.key}:cards`] = s)} />
				{/if}
			{/if}
		</section>
	{/each}
	{#if error && !home}
		<!-- Home couldn't load: say so, with Try again; searching still works. -->
		<div class="flex flex-col items-center gap-3 py-16 text-center" role="alert">
			<p class="text-muted">Couldn't load Home: {error}</p>
			<button
				class="rounded-full bg-raised px-5 py-2 text-sm font-medium hover:bg-line disabled:opacity-40"
				disabled={loadingAgain}
				onclick={() => ((loadingAgain = true), load())}
			>
				{loadingAgain ? 'Loading…' : 'Try again'}
			</button>
			<p class="text-sm text-muted">Or search for a song to start the music.</p>
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

<script lang="ts" module>
	/** A tile in a speed dial: a song, or a page to open. */
	export interface DialTile {
		key: string;
		title: string;
		art: string | undefined;
		/** Shown first while [art] loads. */
		small?: string;
		/** Shown when there's no picture. */
		icon: IconName;
		open: () => void;
		/** Right-click: its menu (songs). */
		menu?: (event: MouseEvent) => void;
		prefetch?: () => void;
	}
	import type { IconName } from './Icon.svelte';

	/** A tile is at least this wide on a computer (px). */
	const MIN_TILE = 120;
	/** …and at most this wide, so a few tiles don't get huge. */
	const MAX_TILE = 176;
	const GAP = 8;
</script>

<script lang="ts">
	// YTM's speed dial: covers with the name over them (Home: Pinned, and any section set to Speed
	// dial). Phones: 3×3 pages swiped sideways; with Settings → Home's *Full pages* only full pages
	// (18, or 9) so no page is half empty; your own pins all show. Computers: one grid without
	// pages, its rows even (20 tiles: 7 · 7 · 6 at 1440 px, two rows of 10 on a wider screen).
	// [extras] (Liked music, a random song) fill the gaps a full last row or page would leave.
	import { home } from '../home.svelte';
	import { screen } from '../screen.svelte';
	import Icon from './Icon.svelte';
	import Picture from './Picture.svelte';
	import PageDots from './PageDots.svelte';

	/** [all]: show every tile on phones too (your pins), not only full pages; [extras]: tiles that
	 * only show to fill a gap. */
	let { tiles, all = !home.fullDialPages, extras = [] }: { tiles: DialTile[]; all?: boolean; extras?: DialTile[] } = $props();

	const unique = $derived(tiles.filter((t, i) => tiles.findIndex((u) => u.key === t.key) === i));
	const spare = $derived(extras.filter((e) => !unique.some((t) => t.key === e.key)));
	/** [list] with the first [count] spare tiles after it, when that many are there. */
	const filled = (list: DialTile[], count: number) => (count > 0 && count <= spare.length ? [...list, ...spare.slice(0, count)] : list);
	const onPhone = $derived.by(() => {
		if (all) return filled(unique, (3 - (unique.length % 3)) % 3);
		const pages = unique.length + spare.length >= 18 ? 2 : unique.length + spare.length >= 9 ? 1 : 0;
		if (!pages) return filled(unique, (3 - (unique.length % 3)) % 3);
		return filled(unique.slice(0, pages * 9), pages * 9 - Math.min(unique.length, pages * 9));
	});
	const pages = $derived(Array.from({ length: Math.ceil(onPhone.length / 9) }, (_, i) => onPhone.slice(i * 9, i * 9 + 9)));

	let width = $state(0);
	const fit = $derived(Math.max(1, Math.floor((width + GAP) / (MIN_TILE + GAP))));
	/** Columns on a computer: as many as fit, then fewer so the rows come out even; they fill the
	 * width, except when there are fewer tiles than fit (those stay at most [MAX_TILE] wide). */
	const rows = $derived(Math.ceil(unique.length / fit));
	const perRow = $derived(Math.ceil(unique.length / Math.max(1, rows)));
	/** On a computer: the tiles, and extras where the last row has a gap. */
	const onComputer = $derived(rows > 1 ? filled(unique, rows * perRow - unique.length) : unique);
	const columns = $derived(rows > 1 ? `repeat(${perRow}, minmax(0, 1fr))` : `repeat(${unique.length}, minmax(0, ${MAX_TILE}px))`);
	let scroller = $state<HTMLElement>();
</script>

{#snippet tile(tile: DialTile)}
	<button
		class="group relative aspect-square overflow-hidden rounded-md bg-raised text-left"
		onclick={tile.open}
		oncontextmenu={tile.menu}
		onpointerenter={tile.prefetch}
		ontouchstart={tile.prefetch}
	>
		<Icon name={tile.icon} size={36} class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-muted" />
		{#if tile.art}
			<Picture src={tile.art} small={tile.small} class="relative h-full w-full object-cover transition-transform group-hover:scale-105" />
		{/if}
		<!-- The name over the cover, readable on any picture. -->
		<span class="absolute inset-x-0 bottom-0 line-clamp-2 bg-gradient-to-t from-black/85 to-transparent px-2 pt-6 pb-1.5 text-xs leading-tight font-semibold">{tile.title}</span>
	</button>
{/snippet}

{#if screen.wide}
	<div class="px-2" bind:clientWidth={width}>
		<div class="grid gap-2" style:grid-template-columns={columns}>
			{#each onComputer as t (t.key)}
				{@render tile(t)}
			{/each}
		</div>
	</div>
{:else}
	<div bind:this={scroller} class="flex snap-x gap-2 overflow-x-auto scroll-px-2 px-2 [scrollbar-width:none]">
		{#each pages as page, p (p)}
			<div class="grid shrink-0 snap-start grid-cols-3 content-start gap-2 {pages.length === 1 ? 'w-full' : 'w-[calc(100%-1rem)]'}">
				{#each page as t (t.key)}
					{@render tile(t)}
				{/each}
			</div>
		{/each}
	</div>
	{#if pages.length > 1}
		<PageDots target={scroller} />
	{/if}
{/if}

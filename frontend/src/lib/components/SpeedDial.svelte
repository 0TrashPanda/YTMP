<script lang="ts" module>
	/** A tile in a speed dial: a song, or a page to open. */
	export interface DialTile {
		key: string;
		title: string;
		art: string | undefined;
		/** Shown when there's no picture. */
		icon: IconName;
		open: () => void;
		/** Right-click: its menu (songs). */
		menu?: (event: MouseEvent) => void;
		prefetch?: () => void;
	}
	import type { IconName } from './Icon.svelte';
</script>

<script lang="ts">
	// YTM's speed dial: covers in a 3×3 grid with the name over them, nine at a time, the next nine
	// a swipe to the side (Home: Pinned, and any section set to Speed dial).
	import { hideIfBroken } from '../pictures';
	import Icon from './Icon.svelte';
	import PageDots from './PageDots.svelte';

	let { tiles }: { tiles: DialTile[] } = $props();

	const unique = $derived(tiles.filter((t, i) => tiles.findIndex((u) => u.key === t.key) === i));
	const pages = $derived(Array.from({ length: Math.ceil(unique.length / 9) }, (_, i) => unique.slice(i * 9, i * 9 + 9)));
	let scroller = $state<HTMLElement>();
</script>

<div bind:this={scroller} class="flex snap-x gap-2 overflow-x-auto scroll-px-2 px-2 [scrollbar-width:none]">
	{#each pages as page, p (p)}
		<div class="grid w-[calc(100%-1rem)] shrink-0 snap-start grid-cols-3 content-start gap-2 sm:w-[30rem] {pages.length === 1 ? 'w-full' : ''}">
			{#each page as tile (tile.key)}
				<button
					class="group relative aspect-square overflow-hidden rounded-md bg-raised text-left"
					onclick={tile.open}
					oncontextmenu={tile.menu}
					onpointerenter={tile.prefetch}
					ontouchstart={tile.prefetch}
				>
					<Icon name={tile.icon} size={36} class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 text-muted" />
					{#if tile.art}
						<img src={tile.art} alt="" use:hideIfBroken loading="lazy" referrerpolicy="no-referrer" class="relative h-full w-full object-cover transition-transform group-hover:scale-105" />
					{/if}
					<!-- The name over the cover, readable on any picture. -->
					<span
						class="absolute inset-x-0 bottom-0 line-clamp-2 bg-gradient-to-t from-black/85 to-transparent px-2 pt-6 pb-1.5 text-xs leading-tight font-semibold">{tile.title}</span>
				</button>
			{/each}
		</div>
	{/each}
</div>
{#if pages.length > 1}
	<PageDots target={scroller} />
{/if}

<script lang="ts" module>
	/** An album, artist, podcast or playlist as a card. */
	export interface Card {
		key: string;
		title: string;
		subtitle: string;
		art: string | undefined;
		/** Round art, for artists. */
		round?: boolean;
		open: () => void;
		/** Starts loading the page, so it opens instantly. */
		prefetch?: () => void;
	}
</script>

<script lang="ts">
	import { hideIfBroken } from '../pictures';
	import Icon from './Icon.svelte';
	import PageDots from './PageDots.svelte';

	/** [row]: one row that scrolls sideways (the home page), instead of a grid; [list]: one card under the other. */
	let { cards, row = false, list = false }: { cards: Card[]; row?: boolean; list?: boolean } = $props();
	// YouTube Music can list the same album or playlist twice: show it once.
	const unique = $derived(cards.filter((c, i) => cards.findIndex((d) => d.key === c.key) === i));
	let scroller = $state<HTMLElement>();
</script>

{#if list}
	<div class="flex flex-col">
		{#each unique as card (card.key)}
			<button class="flex min-w-0 items-center gap-3 rounded-md px-2 py-1.5 text-left hover:bg-raised" onclick={card.open} onpointerenter={card.prefetch} ontouchstart={card.prefetch}>
				<div class="relative grid h-12 w-12 shrink-0 place-items-center overflow-hidden bg-raised text-muted {card.round ? 'rounded-full' : 'rounded'}">
					<Icon name={card.round ? 'person' : 'note'} size={24} class="absolute" />
					{#if card.art}
						<img src={card.art} alt="" use:hideIfBroken loading="lazy" referrerpolicy="no-referrer" class="relative h-full w-full object-cover" />
					{/if}
				</div>
				<div class="min-w-0 flex-1">
					<p class="truncate font-medium">{card.title}</p>
					<p class="truncate text-sm text-muted">{card.subtitle}</p>
				</div>
			</button>
		{/each}
	</div>
{:else}
<div
	bind:this={scroller}
	class={row
		? 'grid snap-x auto-cols-[9.5rem] grid-flow-col gap-4 overflow-x-auto scroll-px-2 px-2 pb-2 [scrollbar-width:none] sm:auto-cols-[11rem]'
		: 'grid grid-cols-[repeat(auto-fill,minmax(9rem,1fr))] gap-4 px-2'}
>
	{#each unique as card (card.key)}
		<button class="group flex min-w-0 snap-start flex-col gap-2 text-left" onclick={card.open} onpointerenter={card.prefetch} ontouchstart={card.prefetch}>
			<div class="relative grid aspect-square w-full place-items-center overflow-hidden bg-raised text-muted {card.round ? 'rounded-full' : 'rounded-md'}">
				<!-- Under the picture: shows when there's none, or it doesn't load. -->
				<Icon name={card.round ? 'person' : 'note'} size={40} class="absolute" />
				{#if card.art}
					<img src={card.art} alt="" use:hideIfBroken loading="lazy" referrerpolicy="no-referrer" class="relative h-full w-full object-cover transition-transform group-hover:scale-105" />
				{/if}
			</div>
			<div class="min-w-0 {card.round ? 'text-center' : ''}">
				<p class="truncate text-sm font-medium">{card.title}</p>
				<p class="truncate text-xs text-muted">{card.subtitle}</p>
			</div>
		</button>
	{/each}
</div>
{#if row}
	<PageDots target={scroller} />
{/if}
{/if}

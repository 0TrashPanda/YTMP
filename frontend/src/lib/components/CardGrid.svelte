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
	/** [row]: one row that scrolls sideways (the home page), instead of a grid. */
	import PageDots from './PageDots.svelte';

	let { cards, row = false }: { cards: Card[]; row?: boolean } = $props();
	// YouTube Music can list the same album or playlist twice: show it once.
	const unique = $derived(cards.filter((c, i) => cards.findIndex((d) => d.key === c.key) === i));
	let scroller = $state<HTMLElement>();
</script>

<div
	bind:this={scroller}
	class={row
		? 'grid snap-x auto-cols-[9.5rem] grid-flow-col gap-4 overflow-x-auto scroll-px-2 px-2 pb-2 [scrollbar-width:none] sm:auto-cols-[11rem]'
		: 'grid grid-cols-[repeat(auto-fill,minmax(9rem,1fr))] gap-4 px-2'}
>
	{#each unique as card (card.key)}
		<button class="group flex min-w-0 snap-start flex-col gap-2 text-left" onclick={card.open} onpointerenter={card.prefetch} ontouchstart={card.prefetch}>
			<div class="aspect-square w-full overflow-hidden bg-raised {card.round ? 'rounded-full' : 'rounded-md'}">
				{#if card.art}
					<img src={card.art} alt="" loading="lazy" referrerpolicy="no-referrer" class="h-full w-full object-cover transition-transform group-hover:scale-105" />
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

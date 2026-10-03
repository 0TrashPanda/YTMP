<script lang="ts" module>
	/** An album, artist or playlist as a card. */
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
	let { cards }: { cards: Card[] } = $props();
</script>

<div class="grid grid-cols-[repeat(auto-fill,minmax(9rem,1fr))] gap-4 px-2">
	{#each cards as card (card.key)}
		<button class="group flex min-w-0 flex-col gap-2 text-left" onclick={card.open} onpointerenter={card.prefetch} ontouchstart={card.prefetch}>
			<div class="aspect-square w-full overflow-hidden bg-raised {card.round ? 'rounded-full' : 'rounded-md'}">
				{#if card.art}
					<img src={card.art} alt="" loading="lazy" class="h-full w-full object-cover transition-transform group-hover:scale-105" />
				{/if}
			</div>
			<div class="min-w-0 {card.round ? 'text-center' : ''}">
				<p class="truncate text-sm font-medium">{card.title}</p>
				<p class="truncate text-xs text-muted">{card.subtitle}</p>
			</div>
		</button>
	{/each}
</div>

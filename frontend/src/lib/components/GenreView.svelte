<script lang="ts">
	// YouTube Music's page for a genre (Explore → Moods & genres), opened from a song's genre: the
	// song menu's "More Pop", or the genre under the title in the player. Its rows, like Home's:
	// songs (sideways, four rows), playlists, music videos and albums.
	import { getGenre } from '../api';
	import { itemCard, type PageHandlers } from '../cards';
	import type { HomePage, Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import CardGrid, { type Card } from './CardGrid.svelte';
	import SongCarousel from './SongCarousel.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let {
		room,
		id,
		title,
		onToast,
		onMenu,
		...open
	}: { room: RoomConnection; id: string; title: string; onToast: (text: string) => void; onMenu: (target: MenuTarget) => void } & PageHandlers = $props();

	let page = $state<HomePage | null>(null);
	let error = $state<string | null>(null);

	$effect(() => {
		const asked = id;
		page = null;
		error = null;
		getGenre(asked)
			.then((p) => asked === id && (page = p))
			.catch((e) => asked === id && (error = e instanceof Error ? e.message : `Couldn't load ${title}`));
	});

	const sections = $derived(
		(page?.sections ?? [])
			.map((s) => ({
				title: s.title,
				songs: s.items.flatMap((item): Song[] => (item.kind === 'song' ? [item.song] : [])),
				cards: s.items.map((item) => itemCard(item, open)).filter((c): c is Card => c !== null)
			}))
			.filter((s) => s.songs.length || s.cards.length)
	);
</script>

<div class="flex flex-col gap-6 pb-4">
	<h1 class="px-2 text-2xl font-bold sm:text-3xl">{title}</h1>
	{#if error}
		<p class="px-2 text-muted">{error}</p>
	{:else if !page}
		<div class="grid animate-pulse grid-cols-[repeat(auto-fill,minmax(9rem,1fr))] gap-4 px-2">
			{#each [0, 1, 2, 3, 4, 5] as i (i)}
				<div class="aspect-square rounded-md bg-raised"></div>
			{/each}
		</div>
	{:else}
		{#each sections as section, i (section.title + i)}
			<section class="flex flex-col gap-3">
				<h2 class="px-2 text-xl font-bold sm:text-2xl">{section.title}</h2>
				{#if section.songs.length}
					<SongCarousel {room} songs={section.songs} {onToast} {onMenu} />
				{/if}
				{#if section.cards.length}
					<CardGrid cards={section.cards} row />
				{/if}
			</section>
		{:else}
			<p class="px-2 text-muted">YouTube Music has nothing for {title} right now.</p>
		{/each}
	{/if}
</div>

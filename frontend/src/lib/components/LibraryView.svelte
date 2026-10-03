<script lang="ts">
	// Your library, like YTM's: your playlists (Liked music first) and podcasts, through your
	// YouTube Music sign-in. Signed out, it asks you to sign in.
	import { getLibrary } from '../api';
	import { itemCard, type PageHandlers } from '../cards';
	import type { HomePage } from '../protocol.gen';
	import { youtube } from '../youtube.svelte';
	import CardGrid from './CardGrid.svelte';
	import Icon from './Icon.svelte';

	let { onToast, ...open }: { onToast: (text: string) => void } & PageHandlers = $props();

	let library = $state<HomePage | null>(null);
	let error = $state<string | null>(null);

	$effect(() => {
		void youtube.version;
		if (!youtube.account) return;
		library = null;
		error = null;
		getLibrary()
			.then((l) => (library = l))
			.catch((e) => (error = e instanceof Error ? e.message : "Couldn't load your library"));
	});

	async function signIn() {
		const problem = await youtube.signIn();
		if (problem) onToast(problem);
	}
</script>

<div class="flex flex-col gap-8 pb-4">
	{#if !youtube.account}
		<div class="flex flex-col items-center gap-4 px-6 py-16 text-center">
			<Icon name="library" size={56} class="text-muted" />
			<h2 class="text-xl font-bold">Your playlists and podcasts</h2>
			<p class="max-w-sm text-muted">Sign in to YouTube Music to see your playlists, liked songs and podcasts here, and get your own suggestions on Home.</p>
			<button class="rounded-full bg-white px-6 py-2.5 font-medium text-black disabled:opacity-50" disabled={youtube.busy} onclick={signIn}>
				{youtube.busy ? 'Signing in…' : 'Sign in'}
			</button>
		</div>
	{:else if error}
		<p class="px-2 text-muted">{error}</p>
	{:else if !library}
		<div class="grid animate-pulse grid-cols-[repeat(auto-fill,minmax(9rem,1fr))] gap-4 px-2">
			{#each [0, 1, 2, 3, 4, 5] as i (i)}
				<div class="aspect-square rounded-md bg-raised"></div>
			{/each}
		</div>
	{:else}
		{#each library.sections as section (section.title)}
			{@const cards = section.items.flatMap((i) => itemCard(i, open) ?? [])}
			{#if cards.length}
				<section class="flex flex-col gap-3">
					<h2 class="px-2 text-xl font-bold sm:text-2xl">{section.title}</h2>
					<CardGrid {cards} />
				</section>
			{/if}
		{:else}
			<p class="px-2 text-muted">Your library is empty.</p>
		{/each}
	{/if}
</div>

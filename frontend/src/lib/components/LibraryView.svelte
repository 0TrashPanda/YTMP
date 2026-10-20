<script lang="ts">
	// Your library, like YTM's: your playlists (Liked music first) and podcasts, through your
	// YouTube Music sign-in. Signed out, it asks you to sign in. The search field filters it by
	// name (and author), as you type. With a YTMP account, History opens your listening history.
	import { getLibrary } from '../api';
	import { itemCard, type PageHandlers } from '../cards';
	import { fuzzyFind } from '../format';
	import type { HomePage } from '../protocol.gen';
	import type { Card } from './CardGrid.svelte';
	import { youtube } from '../youtube.svelte';
	import { listening } from '../listening.svelte';
	import CardGrid from './CardGrid.svelte';
	import Icon from './Icon.svelte';

	let { onToast, onHistory, ...open }: { onToast: (text: string) => void; onHistory: () => void } & PageHandlers = $props();

	let library = $state<HomePage | null>(null);
	let error = $state<string | null>(null);
	let query = $state('');

	const sections = $derived(
		(library?.sections ?? [])
			.map((section) => ({
				title: section.title,
				cards: fuzzyFind(
					section.items.flatMap((i) => itemCard(i, open) ?? []),
					query,
					(card: Card) => `${card.title} ${card.subtitle ?? ''}`
				)
			}))
			.filter((section) => section.cards.length)
	);

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
	{#if listening.available}
		<div class="flex gap-2 px-2">
			<button class="flex items-center gap-2 rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={onHistory}>
				<Icon name="history" size={20} />
				History
			</button>
		</div>
	{/if}
	{#if !youtube.account && !youtube.available}
		<!-- No YouTube Music here: just your history. -->
	{:else if !youtube.account}
		<div class="flex flex-col items-center gap-4 px-6 py-16 text-center">
			<Icon name="library" size={56} class="text-muted" />
			<h2 class="text-xl font-bold">Your playlists and podcasts</h2>
			<p class="max-w-sm text-muted">Sign in to YouTube Music to see your playlists, liked songs and podcasts here, and get your own suggestions on Home.</p>
			{#if youtube.linkedServer}
				<button class="rounded-full bg-white px-6 py-2.5 font-medium text-black" onclick={() => youtube.useServerSignIn()}>
					Use the one from your account on {new URL(youtube.linkedServer).host}
				</button>
			{/if}
			<button
				class="rounded-full px-6 py-2.5 font-medium disabled:opacity-50 {youtube.linkedServer ? 'bg-raised hover:bg-line' : 'bg-white text-black'}"
				disabled={youtube.busy}
				onclick={signIn}
			>
				{youtube.busy ? 'Signing in…' : youtube.phoneAccount ? `Use ${youtube.phoneAccount.name} from this phone` : 'Sign in'}
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
		<label class="mx-2 flex items-center gap-3 rounded-lg bg-raised px-4 py-2 sm:max-w-md">
			<Icon name="search" size={22} class="shrink-0 text-muted" />
			<input class="min-w-0 flex-1 bg-transparent py-1 outline-none placeholder:text-muted" type="text" bind:value={query} placeholder="Search your library" aria-label="Search your library" autocapitalize="none" spellcheck="false" enterkeyhint="search" />
			{#if query}
				<button class="-my-1 -mr-2 rounded-full p-1.5 text-muted hover:text-white" aria-label="Clear" onclick={() => (query = '')}><Icon name="close" size={20} /></button>
			{/if}
		</label>
		{#each sections as section, i (section.title + i)}
			<section class="flex flex-col gap-3">
				<h2 class="px-2 text-xl font-bold sm:text-2xl">{section.title}</h2>
				<CardGrid cards={section.cards} />
			</section>
		{:else}
			<p class="px-2 text-muted">{query.trim() ? `Nothing in your library matches “${query.trim()}”.` : 'Your library is empty.'}</p>
		{/each}
	{/if}
</div>

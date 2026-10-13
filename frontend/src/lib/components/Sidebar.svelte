<script lang="ts">
	// Desktop: YTM's left side. The logo, Home and Library, and (wide screens) your playlists.
	// Narrower screens get YTM's mini version: icons with small labels. Phones use the bottom bar.
	// A search field above your playlists finds one by name.
	import { getLibrary } from '../api';
	import { fuzzyFind } from '../format';
	import type { SearchItemPlaylist } from '../protocol.gen';
	import { youtube } from '../youtube.svelte';
	import Icon from './Icon.svelte';

	let {
		section,
		onLogo,
		onSection,
		onPlaylist
	}: {
		/** What's shown, or null while another page (search, an artist…) is open. */
		section: 'home' | 'library' | null;
		onLogo: () => void;
		onSection: (section: 'home' | 'library') => void;
		/** One of your own playlists. */
		onPlaylist: (playlist: { id: string; title: string }) => void;
	} = $props();

	let playlists = $state<SearchItemPlaylist[]>([]);
	$effect(() => {
		void youtube.version;
		if (!youtube.account) {
			playlists = [];
			return;
		}
		getLibrary()
			.then((library) => {
				// YouTube Music can list a playlist twice: once each.
				const all = library.sections.flatMap((s) => s.items).filter((i): i is SearchItemPlaylist => i.kind === 'playlist');
				playlists = all.filter((p, i) => all.findIndex((q) => q.id === p.id) === i);
			})
			.catch(() => {}); // the Library page says what went wrong
	});

	let query = $state('');
	const shown = $derived(fuzzyFind(playlists, query, (p) => `${p.title} ${p.author ?? ''}`));

	const sections = $derived(youtube.available ? (['home', 'library'] as const) : (['home'] as const));
	const labels = { home: 'Home', library: 'Library' };
</script>

<aside class="hidden w-20 shrink-0 flex-col border-r border-line bg-bg sm:flex lg:w-60">
	<div class="flex h-14 shrink-0 items-center border-b border-line px-6">
		<button class="text-xl font-black tracking-tight" title="Home" onclick={onLogo}>YT<span class="text-accent">MP</span></button>
	</div>
	<nav class="flex flex-col gap-1 p-2">
		{#each sections as value (value)}
			{@const active = section === value}
			<button
				class="flex items-center rounded-lg text-muted hover:bg-raised hover:text-white max-lg:flex-col max-lg:gap-1 max-lg:py-3 max-lg:text-[10px] lg:gap-5 lg:px-4 lg:py-2.5 lg:font-medium
					{active ? 'bg-raised text-white' : ''}"
				aria-current={active ? 'page' : undefined}
				onclick={() => onSection(value)}
			>
				<Icon name={value} size={24} />
				{labels[value]}
			</button>
		{/each}
	</nav>
	{#if playlists.length}
		<div class="mx-6 hidden border-t border-line lg:block"></div>
		<label class="mx-2 mt-2 hidden items-center gap-2 rounded-lg bg-raised px-3 py-1.5 lg:flex">
			<Icon name="search" size={18} class="shrink-0 text-muted" />
			<input
				class="min-w-0 flex-1 bg-transparent py-0.5 text-sm outline-none placeholder:text-muted"
				type="text"
				bind:value={query}
				placeholder="Search playlists"
				aria-label="Search playlists"
				autocapitalize="none"
				spellcheck="false"
				onkeydown={(e) => e.key === 'Escape' && (query = '')}
			/>
			{#if query}
				<button class="-my-1 -mr-1 rounded-full p-1 text-muted hover:text-white" aria-label="Clear" onclick={() => (query = '')}><Icon name="close" size={16} /></button>
			{/if}
		</label>
		<div class="hidden min-h-0 flex-1 flex-col overflow-y-auto p-2 lg:flex">
			{#each shown as playlist (playlist.id)}
				<button class="rounded-lg px-4 py-2 text-left hover:bg-raised" onclick={() => onPlaylist(playlist)}>
					<span class="block truncate text-sm font-medium">{playlist.title}</span>
					{#if playlist.author}<span class="block truncate text-xs text-muted">{playlist.author}</span>{/if}
				</button>
			{:else}
				<p class="px-4 py-2 text-sm text-muted">No playlist matches “{query.trim()}”.</p>
			{/each}
		</div>
	{/if}
</aside>

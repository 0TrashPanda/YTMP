<script lang="ts">
	// Desktop: YTM's left side. The logo, Home and Library, and (wide screens) your playlists.
	// Narrower screens get YTM's mini version: icons with small labels. Phones use the bottom bar.
	import { getLibrary } from '../api';
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
			.then((library) => (playlists = library.sections.flatMap((s) => s.items).filter((i): i is SearchItemPlaylist => i.kind === 'playlist')))
			.catch(() => {}); // the Library page says what went wrong
	});

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
		<div class="hidden min-h-0 flex-1 flex-col overflow-y-auto p-2 lg:flex">
			{#each playlists as playlist (playlist.id)}
				<button class="rounded-lg px-4 py-2 text-left hover:bg-raised" onclick={() => onPlaylist(playlist)}>
					<span class="block truncate text-sm font-medium">{playlist.title}</span>
					{#if playlist.author}<span class="block truncate text-xs text-muted">{playlist.author}</span>{/if}
				</button>
			{/each}
		</div>
	{/if}
</aside>

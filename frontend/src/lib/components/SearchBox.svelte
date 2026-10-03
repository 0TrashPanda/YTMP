<script lang="ts">
	// Search, like YTM: typing shows suggestions (recent searches when empty); Enter or a
	// suggestion searches and opens the results. Desktop: a box with a dropdown. Phones: a
	// button that opens a full-screen search page. ↑/↓ pick, Esc closes.
	import { tick } from 'svelte';
	import { pushState } from '$app/navigation';
	import { page } from '$app/state';
	import { MediaQuery } from 'svelte/reactivity';
	import { suggestions } from '../api';
	import { saved } from '../storage';
	import Icon from './Icon.svelte';

	let {
		query,
		onsearch,
		onopen
	}: {
		/** The current search (shown in the box). */
		query: string;
		/** Search for this and show the results. */
		onsearch: (query: string) => void;
		/** The search box was opened (e.g. to show the header). */
		onopen: () => void;
	} = $props();

	const wide = new MediaQuery('min-width: 640px');

	let input = $state<HTMLInputElement>();
	let active = $state(false);
	let text = $state('');
	let found = $state<string[]>([]);
	let recent = $state(saved.recentSearches);
	let highlighted = $state(-1);

	// Back button on a phone: the search page's history entry went away, so close it.
	let wasSearching = false;
	$effect(() => {
		const searching = !!page.state.searching;
		if (wasSearching && !searching) active = false;
		wasSearching = searching;
	});

	// The box shows the current search while closed.
	$effect(() => {
		const q = query;
		if (!active) text = q;
	});

	// Suggestions for what's typed. Requests aren't cancelled while typing (then nothing
	// would show until you stop); the answer to the newest request so far wins. The old list
	// stays until a newer one is there, so it updates in place instead of flickering.
	let asked = 0;
	let shown = 0;
	$effect(() => {
		const q = text;
		if (!active || !q.trim()) return;
		const timer = setTimeout(() => {
			const id = ++asked;
			suggestions(q)
				.then((items) => {
					if (id > shown) {
						shown = id;
						found = items;
					}
				})
				.catch(() => {}); // typing goes on without suggestions
		}, 50);
		return () => clearTimeout(timer);
	});

	type Row = { text: string; recent: boolean };
	const rows = $derived.by((): Row[] => {
		const q = text.trim().toLowerCase();
		if (!q) return recent.slice(0, 10).map((t) => ({ text: t, recent: true }));
		// Matching recent searches first (at most two), then YTM's suggestions.
		const mine = recent.filter((t) => t.toLowerCase().startsWith(q) && t.toLowerCase() !== q).slice(0, 2);
		const theirs = found.filter((t) => !mine.includes(t));
		return [...mine.map((t) => ({ text: t, recent: true })), ...theirs.slice(0, 8 - mine.length).map((t) => ({ text: t, recent: false }))];
	});

	async function open() {
		// On phones the search page is a history entry, so the back button closes it.
		if (!wide.current && !page.state.searching) pushState('', { searching: true });
		active = true;
		highlighted = -1;
		onopen();
		await tick();
		// Closed again in the meantime (a quick Enter): don't reopen it by focusing.
		if (!active || document.activeElement === input) return;
		input?.focus();
		if (wide.current) input?.select();
	}

	function close() {
		if (page.state.searching) history.back();
		active = false;
		highlighted = -1;
		input?.blur();
	}

	function search(q: string) {
		const value = q.trim();
		if (!value) return;
		recent = [value, ...recent.filter((t) => t.toLowerCase() !== value.toLowerCase())].slice(0, 20);
		saved.recentSearches = recent;
		text = value;
		close();
		onsearch(value);
	}

	function forget(t: string) {
		recent = recent.filter((r) => r !== t);
		saved.recentSearches = recent;
	}

	/** Puts a suggestion in the box to keep typing (the ↖ button). */
	function fill(t: string) {
		text = t + ' ';
		highlighted = -1;
		input?.focus();
	}

	function keydown(event: KeyboardEvent) {
		if (event.key === 'ArrowDown' && rows.length) {
			event.preventDefault();
			highlighted = (highlighted + 1) % rows.length;
		} else if (event.key === 'ArrowUp' && rows.length) {
			event.preventDefault();
			highlighted = highlighted <= 0 ? rows.length - 1 : highlighted - 1;
		} else if (event.key === 'Enter') {
			event.preventDefault();
			search(highlighted >= 0 ? rows[highlighted].text : text);
		} else if (event.key === 'Escape') {
			close();
		}
	}

	/**
	 * Moves the phone search page to <body>: inside the header (which has a backdrop blur)
	 * a fixed element would only cover the header.
	 */
	function portal(node: HTMLElement) {
		document.body.appendChild(node);
		return { destroy: () => node.remove() };
	}

	/** The typed part stays plain and the rest is bold, like YTM. */
	function parts(t: string): [string, string] {
		const typed = text.trimStart();
		return typed && t.toLowerCase().startsWith(typed.toLowerCase()) ? [t.slice(0, typed.length), t.slice(typed.length)] : ['', t];
	}
</script>

{#snippet field(full: boolean)}
	<input
		bind:this={input}
		bind:value={text}
		class="min-w-0 flex-1 bg-transparent outline-none {full ? 'text-lg' : ''}"
		enterkeyhint="search"
		autocomplete="off"
		spellcheck="false"
		placeholder="Search songs, albums, artists, podcasts"
		role="combobox"
		aria-expanded={active && rows.length > 0}
		aria-controls="search-suggestions"
		aria-autocomplete="list"
		aria-activedescendant={active && highlighted >= 0 ? `suggestion-${highlighted}` : undefined}
		onfocus={() => !active && open()}
		onblur={() => wide.current && close()}
		oninput={() => (highlighted = -1)}
		onkeydown={keydown}
	/>
	{#if text}
		<button class="rounded-full p-1 text-muted hover:text-white" aria-label="Clear search" onpointerdown={(e) => e.preventDefault()} onclick={() => ((text = ''), input?.focus())}>
			<Icon name="close" size={22} />
		</button>
	{/if}
{/snippet}

{#snippet list(full: boolean)}
	<ul id="search-suggestions" role="listbox" class={full ? 'flex-1 overflow-y-auto py-1' : 'absolute top-full right-0 left-0 z-30 rounded-b-lg border-t border-line bg-raised py-2 shadow-2xl'}>
		{#each rows as row, i (row.text)}
			{@const [typed, rest] = parts(row.text)}
			<!-- The keyboard works on the input (↑/↓/Enter), the usual combobox pattern. -->
			<!-- svelte-ignore a11y_click_events_have_key_events -->
			<li
				id="suggestion-{i}"
				role="option"
				aria-selected={i === highlighted}
				class="flex cursor-pointer items-center gap-4 {full ? 'px-4 py-3' : 'px-4 py-2'} {i === highlighted ? 'bg-line' : 'hover:bg-line'}"
				onpointerdown={(e) => e.preventDefault()}
				onclick={() => search(row.text)}
			>
				<Icon name={row.recent ? 'history' : 'search'} size={22} class="shrink-0 text-muted" />
				<span class="min-w-0 flex-1 truncate {full ? 'text-base' : ''}">{typed}<b class="font-bold">{rest}</b></span>
				{#if row.recent && !text.trim()}
					<button class="-my-2 rounded-full p-2 text-muted hover:text-white" aria-label="Remove {row.text} from recent searches" onpointerdown={(e) => e.preventDefault()} onclick={(e) => (e.stopPropagation(), forget(row.text))}>
						<Icon name="close" size={18} />
					</button>
				{:else if full}
					<button class="-my-2 rounded-full p-2 text-muted hover:text-white" aria-label="Edit {row.text}" onpointerdown={(e) => e.preventDefault()} onclick={(e) => (e.stopPropagation(), fill(row.text))}>
						<Icon name="fill" size={20} />
					</button>
				{/if}
			</li>
		{/each}
	</ul>
{/snippet}

{#if wide.current}
	<div class="relative flex min-w-0 flex-1 sm:max-w-xl">
		<label class="flex min-w-0 flex-1 items-center gap-3 bg-raised px-4 py-2 {active && rows.length ? 'rounded-t-lg' : 'rounded-lg'}">
			<Icon name="search" size={22} class="shrink-0 text-muted" />
			{@render field(false)}
		</label>
		{#if active && rows.length}
			{@render list(false)}
		{/if}
	</div>
{:else}
	<!-- Phones: the header only has a button; searching happens on its own page. -->
	<button class="flex min-w-0 flex-1 items-center gap-2 rounded-full bg-raised px-3 py-2 text-left" onclick={open}>
		<Icon name="search" size={20} class="shrink-0 text-muted" />
		<span class="truncate {query ? '' : 'text-muted'}">{query || 'Search'}</span>
	</button>
	{#if active}
		<div class="fixed inset-0 z-50 flex flex-col bg-bg" use:portal>
			<div class="flex items-center gap-2 border-b border-line px-2 py-2">
				<button class="rounded-full p-2 hover:bg-raised" aria-label="Close search" onclick={close}>
					<Icon name="back" size={24} />
				</button>
				{@render field(true)}
			</div>
			{@render list(true)}
		</div>
	{/if}
{/if}

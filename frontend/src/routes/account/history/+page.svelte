<script lang="ts">
	// Your listening history: search, delete, export, import from Google Takeout, and the song
	// graph it makes. See docs/features/history-and-recommendations.md.
	import { onMount } from 'svelte';
	import { authServer, session } from '../../../lib/account';
	import Art from '../../../lib/components/Art.svelte';
	import Icon from '../../../lib/components/Icon.svelte';
	import { artistNames, formatTime } from '../../../lib/format';
	import GraphView from '../../../lib/components/GraphView.svelte';
	import GraphBuildBar from '../../../lib/components/GraphBuildBar.svelte';
	import type { AccountSettings, HistoryEntry, HistoryImport } from '../../../lib/protocol.gen';

	let plays = $state<HistoryEntry[]>([]);
	let more = $state(false);
	let query = $state('');
	let loading = $state(true);
	let error = $state<string | null>(null);
	let settings = $state<AccountSettings | null>(null);
	let deleting = $state(false);
	let from = $state('');
	let to = $state('');
	let importing = $state(false);
	let imports = $state<HistoryImport[]>([]);
	let uploading = $state(false);
	let importNote = $state<string | null>(null);
	let graph = $state(false);
	/** Bumped when a rebuild was just asked for: the progress bar looks. */
	let rebuilding = $state(0);

	async function openImports() {
		importing = !importing;
		if (importing) imports = await authServer.imports().catch(() => []);
	}

	async function upload(file: File | undefined) {
		if (!file) return;
		uploading = true;
		importNote = null;
		try {
			const made = await authServer.importTakeout(file, (sent, of) => (importNote = sent < of ? `Uploading… ${Math.round((sent / of) * 100)}%` : 'Reading the export…'));
			uploading = false;
			importNote = made.plays
				? `Added ${made.plays.toLocaleString()} plays${made.duplicates ? ` (${made.duplicates.toLocaleString()} were in your history already)` : ''}.`
				: 'Nothing new: all of it is in your history already.';
			if (!made.plays) return;
			rebuilding++;
			imports = await authServer.imports();
			await load(true);
		} catch (e) {
			importNote = e instanceof Error ? e.message : "Couldn't import it";
		} finally {
			uploading = false;
		}
	}

	async function removeImport(i: HistoryImport) {
		if (!confirm(`Take the ${i.plays.toLocaleString()} imported plays out of your history again?`)) return;
		await authServer.deleteImport(i.id);
		imports = imports.filter((x) => x.id !== i.id);
		rebuilding++;
		await load(true);
	}

	const shortDay = (ms: number) => new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });

	const loggedIn = !!session.current;

	onMount(async () => {
		if (!loggedIn) return;
		settings = await authServer.settings().catch(() => null);
		await load(true);
	});

	let searchTimer: ReturnType<typeof setTimeout> | undefined;
	function onSearch() {
		clearTimeout(searchTimer);
		searchTimer = setTimeout(() => load(true), 300);
	}

	async function load(fresh: boolean) {
		loading = true;
		error = null;
		try {
			const page = await authServer.history(fresh ? null : (plays.at(-1)?.playedAt ?? null), query);
			plays = fresh ? page.plays : [...plays, ...page.plays];
			more = page.more;
		} catch (e) {
			error = e instanceof Error ? e.message : "Couldn't load your history";
		} finally {
			loading = false;
		}
	}

	async function remove(play: HistoryEntry) {
		await authServer.deletePlay(play.id);
		rebuilding++;
		plays = plays.filter((p) => p.id !== play.id);
	}

	async function removeRange() {
		const start = from ? new Date(from).getTime() : null;
		// "To" is a day: include all of it.
		const end = to ? new Date(to).getTime() + 86_400_000 : null;
		const what = start === null && end === null ? 'your whole history' : `your history ${from ? `from ${from}` : ''} ${to ? `up to ${to}` : ''}`;
		if (!confirm(`Delete ${what.trim()}? This can't be undone.`)) return;
		await authServer.deleteHistory(start, end);
		rebuilding++;
		deleting = false;
		await load(true);
	}

	function day(ms: number) {
		return new Date(ms).toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
	}
	function time(ms: number) {
		return new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
	}
	const days = $derived.by(() => {
		const groups: { day: string; plays: HistoryEntry[] }[] = [];
		for (const play of plays) {
			const d = day(play.playedAt);
			if (groups.at(-1)?.day !== d) groups.push({ day: d, plays: [] });
			groups.at(-1)!.plays.push(play);
		}
		return groups;
	});
</script>

<svelte:head>
	<title>History · YTMP</title>
</svelte:head>

<main class="mx-auto flex min-h-full max-w-2xl flex-col gap-4 px-4 py-8">
	<header class="flex flex-wrap items-center gap-3">
		<a href="/account" class="rounded-full p-1.5 text-muted hover:bg-raised hover:text-white" aria-label="Back">
			<svg viewBox="0 0 24 24" class="h-6 w-6"><path fill="currentColor" d="M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20z" /></svg>
		</a>
		<h1 class="flex-1 text-2xl font-bold whitespace-nowrap">Listening history</h1>
		{#if loggedIn}
			<div class="flex flex-wrap gap-2">
			<button class="rounded-full bg-raised px-4 py-2 text-sm hover:bg-line" onclick={() => (graph = true)}>Song graph</button>
			<button class="rounded-full bg-raised px-4 py-2 text-sm hover:bg-line" onclick={openImports}>Import…</button>
			<button class="rounded-full bg-raised px-4 py-2 text-sm hover:bg-line" onclick={() => authServer.exportHistory(session.current!.account.username)}>Export</button>
			<button class="rounded-full bg-raised px-4 py-2 text-sm hover:bg-line" onclick={() => (deleting = !deleting)}>Delete…</button>
			</div>
		{/if}
	</header>

	{#if !loggedIn}
		<p class="text-muted">Log in on <a href="/account" class="underline">your account page</a> first.</p>
	{:else}
		{#if settings && !settings.tracking}
			<p class="rounded-lg bg-raised px-4 py-3 text-sm">History is <b>paused</b>: new songs aren't kept. Turn it on under <a href="/account" class="underline">Account</a>.</p>
		{/if}

		<GraphBuildBar load={authServer.graphStatus} kick={rebuilding} />

		{#if importing}
			<section class="flex flex-col gap-3 rounded-xl bg-surface p-4 ring-1 ring-line">
				<p class="font-medium">Import from YouTube Music</p>
				<p class="text-sm text-muted">
					In <a class="underline" href="https://takeout.google.com" target="_blank" rel="noopener">Google Takeout</a>, pick only “YouTube and YouTube Music” with its history, and
					upload the zip (or the watch-history file in it). Its YouTube Music plays join your history, marked as imported; plays that are already here (YTMP adds yours to
					YouTube Music) are left out. Takeout doesn't say how much of a song was heard: the time until the next one counts.
				</p>
				<label class="self-start rounded-full bg-white px-4 py-2 text-sm font-medium text-black {uploading ? 'opacity-50' : 'cursor-pointer'}">
					{uploading ? 'Importing…' : 'Choose the export'}
					<input type="file" accept=".zip,.html,.json" class="hidden" disabled={uploading} onchange={(e) => upload(e.currentTarget.files?.[0])} />
				</label>
				{#if importNote}<p class="text-sm">{importNote}</p>{/if}
				{#each imports as i (i.id)}
					<div class="flex items-center gap-3 border-t border-line/60 pt-2 text-sm">
						<div class="min-w-0 flex-1">
							<p>{i.plays.toLocaleString()} plays, {shortDay(i.firstAt)} – {shortDay(i.lastAt)}</p>
							<p class="text-xs text-muted">Imported {shortDay(i.at)}</p>
						</div>
						<button class="rounded-full px-3 py-1.5 text-sm text-muted hover:bg-raised hover:text-white" onclick={() => removeImport(i)}>Remove</button>
					</div>
				{/each}
			</section>
		{/if}

		{#if deleting}
			<section class="flex flex-col gap-3 rounded-xl bg-surface p-4 ring-1 ring-line">
				<p class="text-sm text-muted">Delete everything between these days (leave one empty for "from the start" or "until now"; both empty deletes everything).</p>
				<div class="flex flex-wrap items-center gap-2">
					<input type="date" class="rounded-lg bg-raised px-3 py-2" bind:value={from} aria-label="From" />
					<span class="text-muted">to</span>
					<input type="date" class="rounded-lg bg-raised px-3 py-2" bind:value={to} aria-label="To" />
					<button class="ml-auto rounded-full bg-accent px-4 py-2 text-sm font-medium" onclick={removeRange}>
						{from || to ? 'Delete these days' : 'Delete everything'}
					</button>
				</div>
			</section>
		{/if}

		<label class="flex items-center gap-2 rounded-lg bg-raised px-3 py-2">
			<Icon name="search" size={20} class="text-muted" />
			<input class="min-w-0 flex-1 bg-transparent outline-none" placeholder="Search your history" bind:value={query} oninput={onSearch} />
		</label>

		{#each days as group (group.day)}
			<section>
				<h2 class="sticky top-0 bg-bg/95 px-1 py-2 text-sm font-medium tracking-wide text-muted uppercase backdrop-blur">{group.day}</h2>
				{#each group.plays as play (play.id)}
					<div class="group flex items-center gap-3 rounded-md px-2 py-2 hover:bg-raised">
						<Art song={play.song} size={48} class="h-12 w-12" />
						<div class="min-w-0 flex-1">
							<p class="truncate font-medium">{play.song.title}</p>
							<p class="truncate text-sm text-muted">{artistNames(play.song)}</p>
							<p class="truncate text-xs text-muted">
								{time(play.playedAt)} · {play.roomName}
								{#if play.listenedWith.length}· with {play.listenedWith.map((p) => p.name).join(', ')}{:else if play.shared}· with others{/if}
								{#if !play.addedByMe}· added by {play.addedByName}{/if}
								{#if play.imported}· imported{#if play.skipped} · skipped{/if}{:else if play.skipped}· skipped at {formatTime(play.heardMs)}{/if}
							</p>
						</div>
						<button
							class="rounded-full p-1.5 text-muted opacity-0 group-hover:opacity-100 hover:bg-line hover:text-white max-md:opacity-100"
							aria-label="Delete from history"
							title="Delete from history"
							onclick={() => remove(play)}
						>
							<Icon name="close" size={18} />
						</button>
					</div>
				{/each}
			</section>
		{:else}
			{#if !loading}
				<p class="py-12 text-center text-muted">{query.trim() ? 'Nothing matches.' : 'Nothing here yet. Songs you hear in rooms show up here.'}</p>
			{/if}
		{/each}

		{#if more}
			<button class="self-center rounded-full bg-raised px-6 py-2 text-sm hover:bg-line disabled:opacity-40" disabled={loading} onclick={() => load(false)}>Load more</button>
		{/if}
		{#if error}<p class="text-center text-accent">{error}</p>{/if}
	{/if}
</main>

{#if graph}
	<GraphView onClose={() => (graph = false)} onToast={(text) => (importNote = text)} />
{/if}

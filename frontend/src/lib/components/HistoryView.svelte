<script lang="ts">
	// Your YTMP listening history in the room (Library → History), newest first and by day, like
	// YouTube Music's. Its songs work like any other list: tap, swipe, hold and the song menu,
	// which can also remove a song from your history. Exporting it and deleting days of it is
	// on your account page on your server.
	import { authServer, session } from '../account';
	import { formatTime } from '../format';
	import { listening } from '../listening.svelte';
	import type { AccountSettings, HistoryEntry } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import { genreShares } from '../genres';
	import Icon from './Icon.svelte';
	import SongList from './SongList.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let { room, onToast, onMenu }: { room: RoomConnection; onToast: (text: string) => void; onMenu: (target: MenuTarget) => void } = $props();

	let plays = $state<HistoryEntry[]>([]);
	let more = $state(false);
	let loaded = $state(false);
	let loading = $state(false);
	let error = $state<string | null>(null);
	let query = $state('');
	let settings = $state<AccountSettings | null>(null);
	/** Counts searches: an answer to an older one is dropped. */
	let asked = 0;

	$effect(() => {
		if (listening.onServer && session.current) authServer.settings().then((s) => (settings = s)).catch(() => {});
	});

	async function load(fresh: boolean) {
		const mine = ++asked;
		loading = true;
		error = null;
		try {
			const page = await listening.page(fresh ? null : (plays.at(-1)?.playedAt ?? null), query);
			if (mine !== asked) return;
			plays = fresh ? page.plays : [...plays, ...page.plays];
			more = page.more;
		} catch (e) {
			if (mine === asked) error = e instanceof Error ? e.message : "Couldn't load your history";
		} finally {
			if (mine === asked) {
				loading = false;
				loaded = true;
			}
		}
	}
	load(true);

	let searchTimer: ReturnType<typeof setTimeout> | undefined;
	function onSearch() {
		clearTimeout(searchTimer);
		searchTimer = setTimeout(() => load(true), 300);
	}

	/** At the end of the list: the songs before. */
	let end = $state<HTMLElement>();
	$effect(() => {
		if (!end) return;
		const watcher = new IntersectionObserver((entries) => {
			if (entries.some((e) => e.isIntersecting) && more && !loading && !error) void load(false);
		}, { rootMargin: '800px 0px' });
		watcher.observe(end);
		return () => watcher.disconnect();
	});

	function dayName(ms: number): string {
		const day = new Date(ms);
		const today = new Date();
		const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
		if (day.toDateString() === today.toDateString()) return 'Today';
		if (day.toDateString() === yesterday.toDateString()) return 'Yesterday';
		const thisYear = day.getFullYear() === today.getFullYear();
		return day.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long', ...(thisYear ? {} : { year: 'numeric' }) });
	}

	/** When, where and with whom, under the song. */
	function details(play: HistoryEntry): string {
		const parts = [new Date(play.playedAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }), play.roomName];
		if (play.listenedWith.length) parts.push(`with ${play.listenedWith.map((p) => p.name).join(', ')}`);
		else if (play.shared) parts.push('with others');
		if (!play.addedByMe && play.addedByName) parts.push(`added by ${play.addedByName}`);
		if (play.imported) parts.push(play.skipped ? 'imported · skipped' : 'imported');
		else if (play.skipped) parts.push(`skipped at ${formatTime(play.heardMs)}`);
		return parts.join(' · ');
	}

	/** Only this genre's songs (the chips); null: all. */
	let genre = $state<string | null>(null);
	const WEEK_MS = 7 * 24 * 3600_000;
	/** Your genres in the last 7 days, of what's loaded. */
	const week = $derived(genreShares(plays.filter((p) => p.playedAt > Date.now() - WEEK_MS).map((p) => p.song)).slice(0, 3));
	/** The chips: the genres in what's loaded, most played first. */
	const chips = $derived(genreShares(plays.map((p) => p.song)).slice(0, 8).map((g) => g.genre));

	const days = $derived.by(() => {
		const groups: { day: string; plays: HistoryEntry[] }[] = [];
		for (const play of plays) {
			if (genre && play.song.genre !== genre) continue;
			const day = dayName(play.playedAt);
			if (groups.at(-1)?.day !== day) groups.push({ day, plays: [] });
			groups.at(-1)!.plays.push(play);
		}
		return groups;
	});

	function removed(id: string) {
		plays = plays.filter((p) => p.id !== id);
	}
</script>

<div class="flex flex-col gap-4 pb-4">
	<div class="flex flex-wrap items-center gap-3 px-2">
		<h1 class="flex-1 text-2xl font-bold sm:text-3xl">History</h1>
		{#if listening.onServer}
			<a href="/account/history" class="rounded-full bg-raised px-4 py-1.5 text-sm hover:bg-line">Export or delete days</a>
		{/if}
	</div>

	{#if settings && !settings.tracking}
		<p class="mx-2 rounded-lg bg-raised px-4 py-3 text-sm">Your history is <b>paused</b>: new songs aren't kept. Turn it on in Settings → Account.</p>
	{/if}

	<label class="mx-2 flex items-center gap-3 rounded-lg bg-raised px-4 py-2 sm:max-w-md">
		<Icon name="search" size={22} class="shrink-0 text-muted" />
		<input
			class="min-w-0 flex-1 bg-transparent py-1 outline-none placeholder:text-muted"
			type="text"
			bind:value={query}
			oninput={onSearch}
			placeholder="Search your history"
			aria-label="Search your history"
			autocapitalize="none"
			spellcheck="false"
			enterkeyhint="search"
		/>
		{#if query}
			<button class="-my-1 -mr-2 rounded-full p-1.5 text-muted hover:text-white" aria-label="Clear" onclick={() => ((query = ''), load(true))}><Icon name="close" size={20} /></button>
		{/if}
	</label>

	{#if week.length && !query.trim()}
		<div class="mx-2 rounded-lg bg-raised px-4 py-3 sm:max-w-md">
			<p class="mb-2 text-sm text-muted">Your genres this week</p>
			{#each week as g (g.genre)}
				<div class="flex items-center gap-3 py-0.5 text-sm">
					<span class="w-28 truncate">{g.genre}</span>
					<div class="h-1.5 flex-1 overflow-hidden rounded-full bg-white/10"><div class="h-full rounded-full bg-white" style:width="{Math.round(g.share * 100)}%"></div></div>
					<span class="w-9 text-right text-muted">{Math.round(g.share * 100)}%</span>
				</div>
			{/each}
		</div>
	{/if}
	{#if chips.length > 1}
		<div class="flex gap-2 overflow-x-auto px-2" style:scrollbar-width="none">
			<button class="shrink-0 rounded-lg px-3 py-1.5 text-sm {genre === null ? 'bg-white text-black' : 'bg-white/10 hover:bg-white/20'}" onclick={() => (genre = null)}>All</button>
			{#each chips as g (g)}
				<button class="shrink-0 rounded-lg px-3 py-1.5 text-sm {genre === g ? 'bg-white text-black' : 'bg-white/10 hover:bg-white/20'}" onclick={() => (genre = genre === g ? null : g)}>{g}</button>
			{/each}
		</div>
	{/if}

	{#if !loaded}
		<div class="flex animate-pulse flex-col gap-3 px-2">
			{#each [0, 1, 2, 3, 4, 5] as i (i)}
				<div class="flex items-center gap-3">
					<div class="h-12 w-12 rounded bg-raised"></div>
					<div class="h-4 flex-1 rounded bg-raised"></div>
				</div>
			{/each}
		</div>
	{:else}
		{#each days as group (group.day)}
			<section class="flex flex-col gap-1">
				<h2 class="px-2 pt-2 text-lg font-bold">{group.day}</h2>
				<SongList
					{room}
					songs={group.plays.map((p) => p.song)}
					details={group.plays.map(details)}
					listened={{ ids: group.plays.map((p) => p.id), onRemoved: (i) => removed(group.plays[i].id) }}
					{onToast}
					{onMenu}
				/>
			</section>
		{:else}
			{#if !error}
				<p class="px-2 py-12 text-center text-muted">
					{query.trim() ? `Nothing in your history matches “${query.trim()}”.` : 'Nothing here yet. Songs you hear in rooms show up here.'}
				</p>
			{/if}
		{/each}
		<div bind:this={end}></div>
		{#if error}
			<p class="px-2 text-muted">{error}</p>
			<button class="self-start rounded-full bg-raised px-4 py-1.5 text-sm hover:bg-line mx-2" onclick={() => load(plays.length === 0)}>Try again</button>
		{:else if more}
			<p class="px-2 py-2 text-sm text-muted">Loading more…</p>
		{/if}
	{/if}
</div>

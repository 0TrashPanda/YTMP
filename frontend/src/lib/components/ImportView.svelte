<script lang="ts">
	// Importing playlists (Library → Import, docs/features/importing-playlists.md): your imports,
	// a new one from a Spotify data export (what's in it, where each list goes, the settings),
	// and one import's page, with the songs to check. YTMP looks the songs up on the host with
	// your YouTube Music sign-in, in the background; these pages ask how far it got.
	import {
		getImport,
		getImportItems,
		getImports,
		getMyPlaylists,
		importAction,
		pickImportSong,
		proxiedAudioUrl,
		readImport,
		removeImport,
		search,
		startImport
	} from '../api';
	import { artistNames, formatTime } from '../format';
	import type {
		ConvertItemRow,
		ConvertItemState,
		ConvertJob,
		ConvertListKind,
		ConvertPreview,
		ConvertSettings,
		ConvertTarget,
		PlaylistSummary,
		SearchItem,
		Song
	} from '../protocol.gen';
	import { youtube } from '../youtube.svelte';
	import { hold } from '../hold';
	import { menuAt } from '../songActions';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let {
		jobId,
		onToast,
		onJob,
		onPlaylist,
		onMenu
	}: {
		jobId?: string;
		onToast: (text: string) => void;
		/** The song menu (holding an option's cover). */
		onMenu: (target: MenuTarget) => void;
		/** Opens an import's own page. */
		onJob: (id: string) => void;
		/** Opens a YouTube Music playlist (one of yours). */
		onPlaylist: (p: { id: string; title: string }) => void;
	} = $props();

	const message = (e: unknown, fallback: string) => (e instanceof Error ? e.message : fallback);

	// --- the list of imports, or one import, asked again every few seconds while it goes ---

	let jobs = $state<ConvertJob[] | null>(null);
	let job = $state<ConvertJob | null>(null);
	let error = $state<string | null>(null);
	let lastCounts: string | null = null;

	async function refresh() {
		try {
			if (jobId) {
				job = await getImport(jobId);
				// More songs looked up: the list of songs to check again (not while you're picking one).
				const c = job.counts;
				const seen = `${c.pending}/${c.unsure}/${c.notFound}/${c.leftOut}`;
				if (seen !== lastCounts && lastCounts !== null && open === null) loadRows(true);
				lastCounts = seen;
			} else {
				jobs = await getImports();
			}
			error = null;
		} catch (e) {
			error = message(e, "Couldn't load your imports");
		}
	}

	$effect(() => {
		void jobId;
		if (!youtube.account) return;
		refresh();
		const timer = setInterval(() => {
			const going = jobId ? job?.status === 'matching' : jobs?.some((j) => j.status === 'matching');
			if (going && !document.hidden) refresh();
		}, 3000);
		return () => clearInterval(timer);
	});

	const statusText: Record<ConvertJob['status'], string> = {
		matching: 'Looking up songs',
		paused: 'Paused',
		needs_you: 'Some songs to check',
		done: 'Done',
		cancelled: 'Cancelled'
	};

	function progress(j: ConvertJob): string {
		const c = j.counts;
		const done = c.total - c.pending;
		const parts = [`${done.toLocaleString()} of ${c.total.toLocaleString()} songs`];
		if (j.status === 'matching' && j.leftMs && c.pending > 0) parts.push(`about ${duration(j.leftMs)} left`);
		return parts.join(' · ');
	}

	function duration(ms: number): string {
		const minutes = Math.max(1, Math.round(ms / 60000));
		if (minutes < 60) return `${minutes} min`;
		const hours = Math.floor(minutes / 60);
		return `${hours} h ${minutes % 60 ? `${minutes % 60} min` : ''}`.trim();
	}

	function waitingText(j: ConvertJob): string | null {
		if (!j.waiting) return null;
		if (!j.waitingUntil) return j.waiting;
		const at = new Date(j.waitingUntil).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
		return `${j.waiting}: again at ${at}`;
	}

	// --- a new import ---

	let choosing = $state(false);
	let reading = $state(false);
	let preview = $state<ConvertPreview | null>(null);
	/** Per list of the preview: whether it's imported, and where to. */
	let picks = $state<{ on: boolean; target: ConvertTarget }[]>([]);
	let settings = $state<ConvertSettings>({ addUnsure: false, videos: true });
	let playlists = $state<PlaylistSummary[]>([]);
	let starting = $state(false);

	function defaultTarget(kind: ConvertListKind, name: string): ConvertTarget {
		if (kind === 'followed_shows') return { kind: 'follow', title: null, playlistId: null };
		return { kind: 'new_playlist', title: kind === 'liked_songs' ? 'Liked on Spotify' : name, playlistId: null };
	}

	async function pickFile(event: Event) {
		const input = event.currentTarget as HTMLInputElement;
		const file = input.files?.[0];
		input.value = '';
		if (!file) return;
		reading = true;
		try {
			const read = await readImport(file);
			preview = read;
			picks = read.lists.map((l) => ({ on: true, target: defaultTarget(l.kind, l.name) }));
			getMyPlaylists()
				.then((p) => (playlists = p))
				.catch(() => {});
		} catch (e) {
			onToast(message(e, "Couldn't read that file"));
		} finally {
			reading = false;
		}
	}

	async function start() {
		if (!preview) return;
		starting = true;
		try {
			const lists = picks.flatMap((p, index) => (p.on ? [{ index, target: p.target }] : []));
			const made = await startImport({ readId: preview.readId, lists, settings });
			preview = null;
			choosing = false;
			onJob(made.id);
		} catch (e) {
			onToast(message(e, "Couldn't start the import"));
		} finally {
			starting = false;
		}
	}

	const kindLabel: Record<ConvertListKind, string> = {
		playlist: 'Playlist',
		liked_songs: 'Liked songs',
		saved_episodes: 'Saved episodes',
		followed_shows: 'Podcasts you follow'
	};

	// --- one import's songs ---

	type Filter = 'unsure' | 'not_found' | 'all';
	let filter = $state<Filter>('unsure');
	let rows = $state<ConvertItemRow[]>([]);
	let next = $state<string | null>(null);
	let loadingRows = $state(false);
	/** The row whose choices are open. */
	let open = $state<string | null>(null);
	let query = $state('');
	let found = $state<Song[]>([]);
	let searching = $state(false);

	async function loadRows(fresh: boolean) {
		if (!jobId) return;
		loadingRows = true;
		try {
			const page = await getImportItems(jobId, filter === 'all' ? null : (filter as ConvertItemState), fresh ? null : next);
			rows = fresh ? page.items : [...rows, ...page.items];
			next = page.next;
		} catch (e) {
			onToast(message(e, "Couldn't load the songs"));
		} finally {
			loadingRows = false;
		}
	}

	$effect(() => {
		void filter;
		void jobId;
		if (jobId && youtube.account) loadRows(true);
	});

	function toggle(row: ConvertItemRow) {
		stopListening();
		open = open === row.key ? null : row.key;
		query = [row.item.artists[0], row.item.title].filter(Boolean).join(' ');
		found = [];
	}

	async function choose(row: ConvertItemRow, song: Song | null) {
		if (!jobId) return;
		try {
			const picked = await pickImportSong(jobId, row.key, song ? { song, leftOut: false } : { song: null, leftOut: true });
			// Out of this filter now (unless it's All).
			stopListening();
			rows = filter === 'all' ? rows.map((r) => (r.key === row.key ? picked : r)) : rows.filter((r) => r.key !== row.key);
			open = null;
			refresh();
		} catch (e) {
			onToast(message(e, "Couldn't save that"));
		}
	}

	async function searchYourself() {
		const q = query.trim();
		if (!q) return;
		searching = true;
		try {
			const page = await search(q, 'songs');
			found = page.sections.flatMap((s) => s.items).flatMap((i: SearchItem) => (i.kind === 'song' ? [i.song] : []));
			if (!found.length) onToast('Nothing found');
		} catch (e) {
			onToast(message(e, "Couldn't search"));
		} finally {
			searching = false;
		}
	}

	// Listening to an option: its audio through the host, here on this device only (not in the room).
	let listening = $state<string | null>(null);
	let sample: HTMLAudioElement | null = null;

	function listen(song: Song) {
		sample?.pause();
		if (listening === song.id) {
			listening = null;
			return;
		}
		const audio = new Audio(proxiedAudioUrl(song.id));
		// Past the intro, where a song is most itself.
		if (song.durationMs > 90_000) audio.addEventListener('loadedmetadata', () => (audio.currentTime = 30), { once: true });
		audio.addEventListener('ended', () => {
			if (sample === audio) listening = null;
		});
		audio.play().catch(() => {
			if (sample === audio) listening = null;
			onToast("Couldn't play it here");
		});
		sample = audio;
		listening = song.id;
	}

	/** A followed podcast (kept as a song with its own ID): not something to play or queue. */
	const isShow = (song: Song) => song.podcast?.id === song.id;

	/** Holding an option's cover: the song menu, to queue it and listen with seeking. */
	function menuFor(song: Song) {
		stopListening();
		onMenu({ song, place: 'search', ...(pressed ? menuAt(pressed) : { x: 16, y: 120 }) });
	}

	/** The cover pressed last, to show the menu next to. */
	let pressed: HTMLElement | null = null;

	function stopListening() {
		sample?.pause();
		sample = null;
		listening = null;
	}

	$effect(() => () => sample?.pause());

	async function act(action: 'accept-unsure' | 'pause' | 'resume' | 'cancel') {
		if (!jobId) return;
		try {
			job = await importAction(jobId, action);
			if (action === 'accept-unsure') loadRows(true);
		} catch (e) {
			onToast(message(e, "Couldn't do that"));
		}
	}

	async function remove(id: string) {
		try {
			await removeImport(id);
			jobs = jobs?.filter((j) => j.id !== id) ?? null;
		} catch (e) {
			onToast(message(e, "Couldn't remove it"));
		}
	}

	const stateText: Record<ConvertItemState, string> = {
		pending: 'Not looked up yet',
		matched: 'Found',
		unsure: 'Not sure',
		not_found: 'Not found',
		left_out: 'Left out'
	};

	function original(row: ConvertItemRow): string {
		const i = row.item;
		return [i.artists.join(', '), i.album ?? (i.show !== i.artists[0] ? i.show : null)].filter(Boolean).join(' · ');
	}
</script>

{#snippet bar(j: ConvertJob)}
	{@const c = j.counts}
	<div class="flex h-1.5 overflow-hidden rounded-full bg-raised" aria-hidden="true">
		<div class="bg-good" style="width: {(c.matched / Math.max(1, c.total)) * 100}%"></div>
		<div class="bg-yellow-500" style="width: {(c.unsure / Math.max(1, c.total)) * 100}%"></div>
		<div class="bg-muted/40" style="width: {((c.notFound + c.leftOut) / Math.max(1, c.total)) * 100}%"></div>
	</div>
{/snippet}

{#snippet option(row: ConvertItemRow, song: Song, chosen: boolean)}
	<div class="flex items-center gap-1 rounded {chosen ? 'bg-line/60' : 'hover:bg-line'}">
		<button
			class="relative shrink-0 rounded p-1"
			aria-label={listening === song.id ? 'Stop' : `Listen to ${song.title}`}
			onclick={() => listen(song)}
			onpointerdown={(e) => (pressed = e.currentTarget)}
			use:hold={isShow(song) ? null : () => menuFor(song)}
		>
			<Art {song} size={40} class="h-10 w-10" />
			<span class="absolute inset-1 grid place-items-center rounded bg-black/50"><Icon name={listening === song.id ? 'pause' : 'play'} size={22} /></span>
		</button>
		<button class="flex min-w-0 flex-1 items-center gap-2 p-1 text-left" disabled={chosen} onclick={() => choose(row, song)}>
			<span class="min-w-0 flex-1">
				<span class="block truncate">{song.title}</span>
				<span class="block truncate text-sm text-muted">{artistNames(song)}{song.album ? ` · ${song.album.name}` : ''}{song.durationMs ? ` · ${formatTime(song.durationMs)}` : ''}</span>
			</span>
			{#if chosen}<Icon name="check" size={20} class="shrink-0 text-good" />{/if}
		</button>
	</div>
{/snippet}

{#snippet counts(j: ConvertJob)}
	<p class="text-sm text-muted">
		{j.counts.matched.toLocaleString()} found · {j.counts.unsure.toLocaleString()} not sure · {j.counts.notFound.toLocaleString()} not found{j.counts.leftOut ? ` · ${j.counts.leftOut} left out` : ''}
	</p>
{/snippet}

<div class="flex flex-col gap-6 px-2 pb-6">
	{#if !youtube.account}
		<p class="text-muted">Sign in to YouTube Music (in Library) to import playlists into it.</p>
	{:else if jobId}
		<!-- One import -->
		{#if !job}
			<p class="text-muted">{error ?? 'Loading…'}</p>
		{:else}
			<header class="flex flex-col gap-2">
				<h1 class="text-2xl font-bold">{job.title}</h1>
				<p class="font-medium">{statusText[job.status]}</p>
				{@render bar(job)}
				<p class="text-sm text-muted">{progress(job)}</p>
				{@render counts(job)}
				{#if waitingText(job)}
					<p class="text-sm text-yellow-400">{waitingText(job)}</p>
				{/if}
				<div class="flex flex-wrap gap-2 pt-1">
					{#if job.status === 'paused'}
						<button class="rounded-full bg-white px-4 py-2 text-sm font-medium text-black" onclick={() => act('resume')}>Go on</button>
					{:else if job.status === 'matching' || job.status === 'needs_you'}
						<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={() => act('pause')}>Pause</button>
					{/if}
					{#if job.counts.unsure > 0 && job.status !== 'cancelled'}
						<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line" onclick={() => act('accept-unsure')}>Accept all {job.counts.unsure} not sure</button>
					{/if}
					{#if job.status !== 'cancelled' && job.status !== 'done'}
						<button class="rounded-full px-4 py-2 text-sm font-medium text-muted hover:text-white" onclick={() => act('cancel')}>Cancel</button>
					{/if}
				</div>
			</header>

			<section class="flex flex-col gap-2">
				<h2 class="text-lg font-bold">Lists</h2>
				{#each job.lists as list, i (i)}
					<div class="flex items-center gap-3 rounded-lg bg-raised px-3 py-2">
						<div class="min-w-0 flex-1">
							<p class="truncate font-medium">{list.target.title ?? list.name}</p>
							<p class="truncate text-sm text-muted">
								{kindLabel[list.kind]} · {list.target.kind === 'likes' ? 'liked' : list.target.kind === 'follow' ? 'followed' : 'in YouTube Music'}: {list.written.toLocaleString()} of {list.count.toLocaleString()}
							</p>
							{#if list.error}<p class="text-sm text-yellow-400">{list.error}</p>{/if}
						</div>
						{#if list.playlistId}
							<button class="shrink-0 rounded-full p-2 text-muted hover:text-white" aria-label="Open the playlist" onclick={() => onPlaylist({ id: list.playlistId!, title: list.target.title ?? list.name })}>
								<Icon name="open" size={22} />
							</button>
						{/if}
					</div>
				{/each}
			</section>

			<section class="flex flex-col gap-3">
				<h2 class="text-lg font-bold">Checking songs</h2>
				<div class="flex gap-2">
					{#each [['unsure', 'Not sure'], ['not_found', 'Not found'], ['all', 'All']] as [value, label] (value)}
						<button
							class="rounded-lg px-3 py-1.5 text-sm font-medium {filter === value ? 'bg-white text-black' : 'bg-raised hover:bg-line'}"
							onclick={() => (filter = value as Filter)}>{label}</button
						>
					{/each}
				</div>
				{#each rows as row (row.key)}
					<div class="flex flex-col gap-2 rounded-lg bg-raised/50 p-2">
						<button class="flex items-center gap-3 text-left" onclick={() => toggle(row)}>
							<Art song={row.song} size={48} class="h-12 w-12" />
							<div class="min-w-0 flex-1">
								<p class="truncate font-medium">{row.item.title}</p>
								<p class="truncate text-sm text-muted">{original(row)}</p>
								<p class="truncate text-sm {row.state === 'matched' ? 'text-good' : row.state === 'unsure' ? 'text-yellow-400' : 'text-muted'}">
									{#if row.song && row.state !== 'not_found' && row.state !== 'left_out'}
										→ {row.song.title} · {artistNames(row.song)}{row.song.durationMs ? ` · ${formatTime(row.song.durationMs)}` : ''}
									{:else}
										{stateText[row.state]}
									{/if}
								</p>
							</div>
							<Icon name={open === row.key ? 'expand' : 'collapse'} size={22} class="shrink-0 text-muted" />
						</button>
						{#if open === row.key}
							{@const current = row.song && row.state !== 'not_found' && row.state !== 'left_out' ? row.song : null}
							{@const others = [...row.candidates, ...found].filter((s, i, all) => s.id !== current?.id && all.findIndex((o) => o.id === s.id) === i)}
							<div class="flex flex-col gap-1 pl-2">
								<p class="pt-1 text-xs font-medium uppercase tracking-wide text-muted">On Spotify</p>
								<p class="px-1">
									<span class="font-medium">{row.item.title}</span>
									<span class="block text-sm text-muted">{[original(row), row.item.durationMs ? formatTime(row.item.durationMs) : null].filter(Boolean).join(' · ') || 'No artist or album'}</span>
								</p>
								<p class="pt-2 text-xs font-medium uppercase tracking-wide text-muted">{row.state === 'unsure' ? "YTMP's guess" : 'Matched to'}</p>
								{#if current}
									{@render option(row, current, true)}
									{#if row.state === 'unsure'}
										<button class="flex items-center gap-2 self-start rounded-full bg-white px-4 py-1.5 text-sm font-medium text-black" onclick={() => choose(row, current)}>
											<Icon name="check" size={18} />This one is right
										</button>
									{/if}
								{:else}
									<p class="px-1 text-sm text-muted">{row.state === 'left_out' ? 'Left out' : 'Nothing yet: pick one below, or search'}</p>
								{/if}
								{#if others.length}
									<p class="pt-2 text-xs font-medium uppercase tracking-wide text-muted">{current ? 'Or one of these' : 'Maybe one of these'}</p>
									{#each others as song (song.id)}
										{@render option(row, song, false)}
									{/each}
								{/if}
								<form
									class="flex items-center gap-2 pt-2"
									onsubmit={(e) => {
										e.preventDefault();
										searchYourself();
									}}
								>
									<input class="min-w-0 flex-1 rounded-lg bg-raised px-3 py-2 outline-none" bind:value={query} placeholder="Search YouTube Music" aria-label="Search YouTube Music" enterkeyhint="search" />
									<button class="rounded-full bg-raised p-2 hover:bg-line disabled:opacity-50" aria-label="Search" disabled={searching}><Icon name="search" size={20} /></button>
								</form>
								{#if row.state !== 'left_out'}
									<button class="self-start rounded-full px-3 py-1.5 text-sm text-muted hover:text-white" onclick={() => choose(row, null)}>Leave it out</button>
								{/if}
							</div>
						{/if}
					</div>
				{:else}
					<p class="text-muted">{loadingRows ? 'Loading…' : filter === 'unsure' ? 'No songs to check.' : filter === 'not_found' ? 'Every song was found.' : 'No songs.'}</p>
				{/each}
				{#if next}
					<button class="self-center rounded-full bg-raised px-4 py-2 text-sm hover:bg-line disabled:opacity-50" disabled={loadingRows} onclick={() => loadRows(false)}>More</button>
				{/if}
			</section>
		{/if}
	{:else if choosing}
		<!-- A new import -->
		<h1 class="text-2xl font-bold">Import from Spotify</h1>
		{#if !preview}
			<div class="flex flex-col gap-3 text-muted">
				<p>
					On <a class="underline" href="https://www.spotify.com/account/privacy/" target="_blank" rel="noopener">spotify.com → Account → Privacy settings</a>, request
					<strong class="text-white">Download your data → Account data</strong>. Spotify emails you a zip within a few days. Pick that zip here (or Playlist1.json from it).
				</p>
				<p>YTMP only reads your playlists, liked songs and podcasts from it, and doesn't keep the file.</p>
			</div>
			<label class="flex cursor-pointer items-center justify-center gap-2 self-start rounded-full bg-white px-6 py-2.5 font-medium text-black {reading ? 'opacity-50' : ''}">
				<Icon name="playlistAdd" size={20} />
				{reading ? 'Reading…' : 'Pick the export'}
				<input class="hidden" type="file" accept=".zip,.json,application/zip,application/json" disabled={reading} onchange={pickFile} />
			</label>
			<button class="self-start text-sm text-muted hover:text-white" onclick={() => (choosing = false)}>Back</button>
		{:else}
			<section class="flex flex-col gap-2">
				<h2 class="text-lg font-bold">What to import</h2>
				{#each preview.lists as list, i (i)}
					{@const pick = picks[i]}
					<div class="flex flex-col gap-2 rounded-lg bg-raised px-3 py-2">
						<label class="flex items-center gap-3">
							<input type="checkbox" class="h-5 w-5 accent-accent" bind:checked={pick.on} />
							<span class="min-w-0 flex-1">
								<span class="block truncate font-medium">{list.name}</span>
								<span class="block text-sm text-muted">{kindLabel[list.kind]} · {list.count.toLocaleString()}</span>
							</span>
						</label>
						{#if pick.on && list.kind !== 'followed_shows'}
							<div class="flex flex-col gap-2 pl-8">
								<select
									class="rounded-lg bg-surface px-2 py-1.5"
									aria-label="Where to"
									value={pick.target.kind === 'existing_playlist' ? `pl:${pick.target.playlistId}` : pick.target.kind}
									onchange={(e) => {
										const v = e.currentTarget.value;
										pick.target = v.startsWith('pl:')
											? { kind: 'existing_playlist', title: null, playlistId: v.slice(3) }
											: v === 'likes'
												? { kind: 'likes', title: null, playlistId: null }
												: defaultTarget(list.kind, list.name);
									}}
								>
									<option value="new_playlist">A new playlist</option>
									{#if list.kind === 'liked_songs'}<option value="likes">Like them on YouTube Music (slowly)</option>{/if}
									{#each playlists as p (p.id)}<option value="pl:{p.id}">Add to {p.title}</option>{/each}
								</select>
								{#if pick.target.kind === 'new_playlist'}
									<input class="rounded-lg bg-surface px-3 py-1.5 outline-none" bind:value={pick.target.title} maxlength="150" aria-label="Playlist name" />
								{/if}
							</div>
						{:else if pick.on}
							<p class="pl-8 text-sm text-muted">Followed on YouTube Music</p>
						{/if}
					</div>
				{/each}
				{#if preview.skipped}<p class="text-sm text-muted">{preview.skipped} rows aren't songs or podcasts (audiobooks, empty rows) and are left out.</p>{/if}
			</section>
			<section class="flex flex-col gap-3">
				<h2 class="text-lg font-bold">Settings</h2>
				<label class="flex items-start gap-3">
					<input type="checkbox" class="mt-1 h-5 w-5 accent-accent" bind:checked={settings.addUnsure} />
					<span>Add songs YTMP isn't sure about without asking<span class="block text-sm text-muted">Otherwise they wait for you to check.</span></span>
				</label>
				<label class="flex items-start gap-3">
					<input type="checkbox" class="mt-1 h-5 w-5 accent-accent" bind:checked={settings.videos} />
					<span>Music videos when there's no song version</span>
				</label>
			</section>
			<p class="text-sm text-muted">Songs are looked up a few seconds apart, so YouTube doesn't block your network: a thousand songs take about an hour. Each playlist is made as soon as its songs are looked up.</p>
			<div class="flex gap-2">
				<button class="rounded-full bg-white px-6 py-2.5 font-medium text-black disabled:opacity-50" disabled={starting || !picks.some((p) => p.on)} onclick={start}>
					{starting ? 'Starting…' : 'Start'}
				</button>
				<button class="rounded-full px-4 py-2.5 text-muted hover:text-white" onclick={() => (preview = null)}>Another file</button>
			</div>
		{/if}
	{:else}
		<!-- Your imports -->
		<div class="flex items-center justify-between gap-2">
			<h1 class="text-2xl font-bold">Import playlists</h1>
			<button class="flex items-center gap-2 rounded-full bg-white px-4 py-2 text-sm font-medium text-black" onclick={() => (choosing = true)}>
				<Icon name="add" size={20} />
				From Spotify
			</button>
		</div>
		{#if error && !jobs}
			<p class="text-muted">{error}</p>
		{:else if jobs && !jobs.length}
			<p class="text-muted">Bring your playlists from Spotify into YouTube Music: YTMP finds each song and makes the playlists for you.</p>
		{/if}
		{#each jobs ?? [] as j (j.id)}
			<div class="flex items-center gap-2 rounded-lg bg-raised">
				<button class="flex min-w-0 flex-1 flex-col gap-1.5 p-3 text-left" onclick={() => onJob(j.id)}>
					<span class="truncate font-medium">{j.title}</span>
					<span class="text-sm text-muted">{statusText[j.status]} · {progress(j)}</span>
					{@render bar(j)}
					{#if waitingText(j)}<span class="text-sm text-yellow-400">{waitingText(j)}</span>{/if}
				</button>
				{#if j.status === 'done' || j.status === 'cancelled'}
					<button class="shrink-0 p-3 text-muted hover:text-white" aria-label="Remove from the list" onclick={() => remove(j.id)}><Icon name="close" size={22} /></button>
				{/if}
			</div>
		{/each}
	{/if}
</div>

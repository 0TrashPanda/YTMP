<script lang="ts">
	import { modal } from '../modal';
	// Play, Play next and Add to queue for a whole album or playlist. A playlist goes into
	// autoplay instead of the queue ([toAutoplay]): it plays when the queue is empty.
	// A long one adds at most the set number of songs, or asks first above another, with the
	// set choices (Settings → Adding songs); loading it says so when it takes a moment. The page
	// plays it from a chosen song through [playFrom] (tapping a song there), with the same limits.
	import type { Snippet } from 'svelte';
	import { addChoice, pickSongs, songsFrom } from '../addLimit';
	import { whileBusy } from '../busy.svelte';
	import type { Song } from '../protocol.gen';
	import { ADD_LIMITS, settings, type PlaylistPick } from '../settings.svelte';
	import { addToQueue, playNext } from '../songActions';
	import type { RoomConnection } from '../room.svelte';
	import Icon from './Icon.svelte';

	let {
		room,
		songs,
		name,
		total = null,
		toAutoplay = false,
		whole,
		onToast,
		children
	}: {
		room: RoomConnection;
		/** Null while loading. */
		songs: Song[] | null;
		/** At least [count] songs (or all there are), when [songs] is only the part loaded so far (long playlists). */
		whole?: (count: number) => Promise<Song[]>;
		/** How many songs it has in all, when [songs] is only a part (null: not known). */
		total?: number | null;
		/** The album's or playlist's title, for the toasts. */
		name: string;
		/** "Add to autoplay" instead of "Add to queue" (playlists). */
		toAutoplay?: boolean;
		onToast: (text: string) => void;
		/** More buttons at the end of the row. */
		children?: Snippet;
	} = $props();

	type Amount = { count: number; how: PlaylistPick };

	/**
	 * Asking how many of [total] (from a chosen song on, [fromHere]: in order, no random pick): one
	 * of [counts], all of it ([everything]) or a typed number; answered with it, or null (cancelled).
	 */
	let asking = $state<{ verb: string; total: number | null; fromHere: boolean; counts: number[]; everything: boolean; answer: (amount: Amount | null) => void } | null>(null);
	/** In the prompt: the first ones or at random (this time only), and a typed number. */
	let pick = $state<PlaylistPick>('first');
	let typed = $state<number | null>(null);

	/** How many to add (from song [start] on), and which: within the set most, or asked first when it's long. Null: cancelled. */
	async function howMany(verb: string, start = 0): Promise<Amount | null> {
		const all = whole ? total : (songs?.length ?? 0);
		const known = all === null ? null : Math.max(0, all - start);
		const fromHere = start > 0;
		const choice = addChoice(known, settings.playlistMax.value, settings.playlistAskOver.value, settings.playlistAskCounts.value, {
			all: settings.playlistAskAll.value,
			custom: settings.playlistAskCustom.value
		});
		// From a chosen song: that song first, and the ones after it in order.
		const how = fromHere ? 'first' : settings.playlistPick.value;
		if (!choice.ask) return { count: choice.count, how };
		pick = how;
		typed = null;
		return new Promise((answer) => (asking = { verb, total: known, fromHere, counts: choice.counts, everything: choice.everything, answer }));
	}

	function answer(count: number | null) {
		asking?.answer(count ? { count, how: pick } : null);
		asking = null;
	}

	function answerTyped(event: SubmitEvent) {
		event.preventDefault();
		const n = Math.round(typed ?? 0);
		if (n >= 1) answer(Math.min(n, asking?.total ?? n));
	}

	/**
	 * Loads the [count] songs to add (from song [start] on; saying so when it takes a moment, as
	 * [doing] does) and hands them to [use] with how a message names them.
	 */
	async function withSongs({ count, how }: Amount, doing: (name: string) => string, use: (list: Song[], label: string) => Promise<unknown>, start = 0) {
		await whileBusy(doing(name), async () => {
			// A random pick takes from all of it.
			const loaded = whole ? await whole(start + (how === 'random' ? Math.max(count, ADD_LIMITS.max) : count)) : (songs ?? []);
			const all = loaded.slice(start);
			const list = pickSongs(all, count, how);
			if (!list.length) return;
			// All of it: YouTube's count can include songs that can't be played.
			const rest = total === null ? null : total - start;
			const length = count >= (rest ?? Infinity) ? list.length : Math.max(rest ?? 0, all.length);
			await use(list, songsFrom(list.length, length, name));
		});
	}

	async function addToAutoplay() {
		const amount = await howMany('Add');
		if (amount)
			await withSongs(amount, (n) => `Adding ${n} to autoplay…`, async (list, label) => {
				const e = await room.run({ kind: 'AutoplaySongs', songs: list });
				onToast(e ? e.message : `${label} ${list.length === 1 || label === name ? 'plays' : 'play'} when the queue is empty`);
			});
	}

	async function addAll(position: 'next' | 'end') {
		const amount = await howMany('Add');
		if (amount) await withSongs(amount, (n) => `Adding ${n}…`, (list, label) => (position === 'next' ? playNext : addToQueue)(room, list, label, onToast));
	}

	/** Plays it (from song [start] on: the ones before it are left out, like YouTube Music). */
	async function playNow(start = 0) {
		const amount = await howMany('Play', start);
		if (amount)
			await withSongs(
				amount,
				(n) => `Starting ${n}…`,
				async (list) => {
					const [first, ...rest] = list;
					// A party is asked first (the room's "Ask before interrupting"); cancelled, nothing is added.
					let e = await room.run({ kind: 'PlayNow', song: first });
					if (!e && rest.length) e = await room.run({ kind: 'AddSongs', songs: rest, position: 'next' });
					if (e && !room.wasNoticed(e)) onToast(e.message);
				},
				start
			);
	}

	/** Plays it from the song at [index] on (tapping a song on the page). */
	export function playFrom(index: number): Promise<void> {
		return playNow(index);
	}
</script>

<div class="mt-3 flex flex-wrap gap-2">
	{#if room.can('play_now')}
		<button class="flex items-center gap-2 rounded-full bg-white px-4 py-2 text-sm font-medium text-black disabled:opacity-40" disabled={!songs?.length} onclick={() => playNow()}>
			<Icon name="play" size={18} /> Play
		</button>
	{/if}
	{#if room.can('add_songs')}
		<button class="flex items-center gap-2 rounded-full bg-raised px-4 py-2 text-sm hover:bg-line disabled:opacity-40" disabled={!songs?.length} onclick={() => addAll('next')}>
			<Icon name="playNext" size={18} /> Play next
		</button>
		{#if !toAutoplay || !room.can('autoplay_from_here')}
			<button class="flex items-center gap-2 rounded-full bg-raised px-4 py-2 text-sm hover:bg-line disabled:opacity-40" disabled={!songs?.length} onclick={() => addAll('end')}>
				<Icon name="playlistAdd" size={18} /> Add to queue
			</button>
		{/if}
	{/if}
	{#if toAutoplay && room.can('autoplay_from_here')}
		<button class="flex items-center gap-2 rounded-full bg-raised px-4 py-2 text-sm hover:bg-line disabled:opacity-40" disabled={!songs?.length} onclick={addToAutoplay}>
			<Icon name="autoplay" size={18} /> Add to autoplay
		</button>
	{/if}
	{@render children?.()}
</div>

{#if asking}
	<div class="fixed inset-0 z-[70] flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={() => answer(null)}>
		<!-- Keys: use:modal (Escape, Tab). The click only keeps it from reaching the backdrop. -->
		<!-- svelte-ignore a11y_click_events_have_key_events -->
		<div
			class="flex max-h-screen-90 w-full max-w-sm flex-col gap-4 overflow-y-auto rounded-t-2xl bg-surface p-6 ring-1 ring-line sm:rounded-2xl"
			role="dialog"
			aria-label="How many songs"
			tabindex="-1"
			onclick={(e) => e.stopPropagation()}
			use:modal={() => answer(null)}
		>
			<!-- The name can be long: the number of songs gets its own line. -->
			<h2 class="line-clamp-2 text-lg font-bold">{name}</h2>
			<p class="-mt-2 text-sm text-muted">
				<span class="font-medium text-white">{asking.total !== null ? `${asking.total.toLocaleString()} ${asking.fromHere ? 'songs from there on.' : 'songs.'}` : 'A long playlist.'}</span>
				How many?{#if asking.fromHere}{' From the song you picked.'}{:else if !settings.playlistAskPick.value}{pick === 'random' ? ' They are picked at random.' : ' From the top of the list.'}{/if}
			</p>
			{#if settings.playlistAskPick.value && !asking.fromHere}
				<!-- Which songs, for this time (it starts as set in Settings → Adding songs). -->
				<div class="flex rounded-full bg-raised p-1" role="radiogroup" aria-label="Which songs">
					{#each [['first', 'From the top'], ['random', 'At random']] as [option, text] (option)}
						<button
							class="flex-1 rounded-full py-1.5 text-sm {pick === option ? 'bg-white font-medium text-black' : 'text-muted'}"
							role="radio"
							aria-checked={pick === option}
							onclick={() => (pick = option as PlaylistPick)}>{text}</button
						>
					{/each}
				</div>
			{/if}
			<div class="flex flex-col gap-2">
				{#each asking.counts as count, i (count)}
					<button class="rounded-full px-4 py-2.5 text-sm {i === 0 ? 'bg-white font-medium text-black' : 'bg-raised hover:bg-line'}" onclick={() => answer(count)}>
						{asking.verb} {count.toLocaleString()}
					</button>
				{/each}
				{#if asking.everything}
					<!-- All of it, however long (a long one takes a while to load). -->
					<button class="rounded-full px-4 py-2.5 text-sm {asking.counts.length ? 'bg-raised hover:bg-line' : 'bg-white font-medium text-black'}" onclick={() => answer(asking!.total ?? Infinity)}>
						{asking.total !== null ? `${asking.verb} all ${asking.total.toLocaleString()}` : `${asking.verb} everything`}
					</button>
				{/if}
				{#if settings.playlistAskCustom.value}
					<!-- Another number: typed. -->
					<form class="flex items-center gap-2" onsubmit={answerTyped}>
						<input
							class="min-w-0 flex-1 rounded-full bg-raised px-4 py-2.5 text-sm tabular-nums outline-none ring-white/40 [appearance:textfield] focus:ring-2 [&::-webkit-inner-spin-button]:appearance-none"
							type="number"
							inputmode="numeric"
							min="1"
							max={asking.total ?? undefined}
							placeholder="Another number"
							aria-label="How many songs"
							bind:value={typed}
						/>
						<button class="shrink-0 rounded-full bg-raised px-4 py-2.5 text-sm hover:bg-line disabled:opacity-40" disabled={!typed || typed < 1}>{asking.verb}</button>
					</form>
				{/if}
				<button class="rounded-full px-4 py-2 text-sm text-muted hover:bg-raised" onclick={() => answer(null)}>Cancel</button>
			</div>
		</div>
	</div>
{/if}

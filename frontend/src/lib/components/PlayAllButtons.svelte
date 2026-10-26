<script lang="ts">
	// Play, Play next and Add to queue for a whole album or playlist. A playlist goes into
	// autoplay instead of the queue ([toAutoplay]): it plays when the queue is empty.
	// A long one adds at most the set number of songs, or asks first above another, with the
	// set choices (Settings → Adding songs); loading it says so when it takes a moment.
	import type { Snippet } from 'svelte';
	import { addChoice, pickSongs, songsFrom } from '../addLimit';
	import { whileBusy } from '../busy.svelte';
	import type { Song } from '../protocol.gen';
	import { ADD_LIMITS, settings } from '../settings.svelte';
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

	/** Asking how many: one of [counts], or all of it ([everything]); answered with the number, or null (cancelled). */
	let asking = $state<{ verb: string; counts: number[]; everything: boolean; answer: (count: number | null) => void } | null>(null);

	/** How many to add: within the set most, or asked first when it's long. Null: cancelled. */
	async function howMany(verb: string): Promise<number | null> {
		const known = whole ? total : (songs?.length ?? 0);
		const choice = addChoice(known, settings.playlistMax.value, settings.playlistAskOver.value, settings.playlistAskCounts.value, settings.playlistAskAll.value);
		if (!choice.ask) return choice.count;
		return new Promise((answer) => (asking = { verb, counts: choice.counts, everything: choice.everything, answer }));
	}

	function answer(count: number | null) {
		asking?.answer(count);
		asking = null;
	}

	/**
	 * Loads the [count] songs to add (saying so when it takes a moment, as [doing] does) and
	 * hands them to [use] with how a message names them.
	 */
	async function withSongs(count: number, doing: (name: string) => string, use: (list: Song[], label: string) => Promise<unknown>) {
		await whileBusy(doing(name), async () => {
			const how = settings.playlistPick.value;
			// A random pick takes from all of it.
			const all = whole ? await whole(how === 'random' ? Math.max(count, ADD_LIMITS.max) : count) : (songs ?? []);
			const list = pickSongs(all, count, how);
			if (!list.length) return;
			// All of it: YouTube's count can include songs that can't be played.
			const length = count >= (total ?? Infinity) ? list.length : Math.max(total ?? 0, all.length);
			await use(list, songsFrom(list.length, length, name));
		});
	}

	async function addToAutoplay() {
		const count = await howMany('Add');
		if (count)
			await withSongs(count, (n) => `Adding ${n} to autoplay…`, async (list, label) => {
				const e = await room.run({ kind: 'AutoplaySongs', songs: list });
				onToast(e ? e.message : `${label} ${list.length === 1 || label === name ? 'plays' : 'play'} when the queue is empty`);
			});
	}

	async function addAll(position: 'next' | 'end') {
		const count = await howMany('Add');
		if (count) await withSongs(count, (n) => `Adding ${n}…`, (list, label) => (position === 'next' ? playNext : addToQueue)(room, list, label, onToast));
	}

	async function playNow() {
		const count = await howMany('Play');
		if (count)
			await withSongs(count, (n) => `Starting ${n}…`, async (list) => {
				const [first, ...rest] = list;
				let e = await room.run({ kind: 'PlayNow', song: first });
				if (!e && rest.length) e = await room.run({ kind: 'AddSongs', songs: rest, position: 'next' });
				if (e && !room.wasNoticed(e)) onToast(e.message);
			});
	}
</script>

<div class="mt-3 flex flex-wrap gap-2">
	{#if room.can('play_now')}
		<button class="flex items-center gap-2 rounded-full bg-white px-4 py-2 text-sm font-medium text-black disabled:opacity-40" disabled={!songs?.length} onclick={playNow}>
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
		<div
			class="flex w-full max-w-sm flex-col gap-4 rounded-t-2xl bg-surface p-6 ring-1 ring-line sm:rounded-2xl"
			role="dialog"
			aria-label="How many songs"
			tabindex="-1"
			onclick={(e) => e.stopPropagation()}
			onkeydown={(e) => e.key === 'Escape' && answer(null)}
		>
			<!-- The name can be long: the number of songs gets its own line. -->
			<h2 class="line-clamp-2 text-lg font-bold">{name}</h2>
			<p class="-mt-2 text-sm text-muted">
				<span class="font-medium text-white">{total !== null ? `${total.toLocaleString()} songs.` : 'A long playlist.'}</span>
				How many? {settings.playlistPick.value === 'random' ? 'They are picked at random.' : 'From the top of the list.'}
			</p>
			<div class="flex flex-col gap-2">
				{#each asking.counts as count, i (count)}
					<button class="rounded-full px-4 py-2.5 text-sm {i === 0 ? 'bg-white font-medium text-black' : 'bg-raised hover:bg-line'}" onclick={() => answer(count)}>
						{asking.verb} {count.toLocaleString()}
					</button>
				{/each}
				{#if asking.everything}
					<!-- All of it, however long (a long one takes a while to load). -->
					<button class="rounded-full px-4 py-2.5 text-sm {asking.counts.length ? 'bg-raised hover:bg-line' : 'bg-white font-medium text-black'}" onclick={() => answer(total ?? Infinity)}>
						{total !== null ? `${asking.verb} all ${total.toLocaleString()}` : `${asking.verb} everything`}
					</button>
				{/if}
				<button class="rounded-full px-4 py-2 text-sm text-muted hover:bg-raised" onclick={() => answer(null)}>Cancel</button>
			</div>
		</div>
	</div>
{/if}

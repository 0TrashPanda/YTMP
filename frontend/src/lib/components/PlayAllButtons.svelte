<script lang="ts">
	// Play, Play next and Add to queue for a whole album or playlist. A playlist goes into
	// autoplay instead of the queue ([toAutoplay]): it plays when the queue is empty.
	import type { Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Icon from './Icon.svelte';

	let {
		room,
		songs,
		name,
		toAutoplay = false,
		onToast
	}: {
		room: RoomConnection;
		/** Null while loading. */
		songs: Song[] | null;
		/** The album's or playlist's title, for the toasts. */
		name: string;
		/** "Add to autoplay" instead of "Add to queue" (playlists). */
		toAutoplay?: boolean;
		onToast: (text: string) => void;
	} = $props();

	async function addToAutoplay() {
		if (!songs?.length) return;
		const e = await room.run({ kind: 'AutoplaySongs', songs });
		onToast(e ? e.message : `${name} plays when the queue is empty`);
	}

	async function addAll(position: 'next' | 'end') {
		if (!songs?.length) return;
		const e = await room.run({ kind: 'AddSongs', songs, position });
		onToast(e ? e.message : position === 'next' ? `Playing ${name} next` : `Added ${name} to the queue`);
	}

	async function playNow() {
		if (!songs?.length) return;
		const [first, ...rest] = songs;
		let e = await room.run({ kind: 'PlayNow', song: first });
		if (!e && rest.length) e = await room.run({ kind: 'AddSongs', songs: rest, position: 'next' });
		if (e) onToast(e.message);
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
</div>

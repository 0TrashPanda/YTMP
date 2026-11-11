<script lang="ts">
	// A song shared with you (docs/features/sharing.md): another phone held against this one, or
	// Share → YTMP from YouTube Music. Shows the song with what you can do with it, like YouTube
	// Music: Play now, Play next, Add to queue, Start radio, Like, Save to playlist, Open in
	// YouTube Music, Share. In a room it acts on that room; without one ([room] null) the
	// playing choices ask where first ([onPickRoom]). A full page or a sheet (Settings → Sharing).
	import { fade, fly } from 'svelte/transition';
	import { getSong } from '../api';
	import { artistNames } from '../format';
	import { likes } from '../likes.svelte';
	import { modal } from '../modal';
	import { motionMs } from '../motion.svelte';
	import { isYoutubeSong } from '../ownModule.svelte';
	import type { Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import { settings } from '../settings.svelte';
	import { songLink, type SharedAction } from '../share.svelte';
	import { addToQueue, playNext, playNow, quoted, startRadio, type Toast } from '../songActions';
	import { youtube } from '../youtube.svelte';
	import Art from './Art.svelte';
	import Icon, { type IconName } from './Icon.svelte';

	interface Props {
		songId: string;
		room: RoomConnection | null;
		onClose: () => void;
		onToast: Toast;
		/** No room open: pick one (or a new solo room) to do [action] in. */
		onPickRoom?: (song: Song, action: SharedAction) => void;
		/** Save to one of your YouTube Music playlists (SaveSheet); only when signed in. */
		onSave?: (song: Song) => void;
		onShare?: (song: Song) => void;
	}
	let { songId, room, onClose, onToast, onPickRoom, onSave, onShare }: Props = $props();

	let song = $state<Song | null>(null);
	let error = $state<string | null>(null);
	function load() {
		error = null;
		getSong(songId)
			.then((s) => (song = s))
			.catch((e) => (error = e instanceof Error ? e.message : "Couldn't load the song"));
	}
	load();
	$effect(() => likes.load(songId));

	const sheet = $derived(settings.sharedSongLook.value === 'sheet');
	const liked = $derived(likes.get(songId));
	const may = (permission: Parameters<RoomConnection['can']>[0]) => !room || room.can(permission);

	/** Plays it here, or (no room) asks where first. */
	function act(action: SharedAction) {
		const s = song;
		if (!s) return;
		onClose();
		if (!room) return onPickRoom?.(s, action);
		if (action === 'play') void playNow(room, s, onToast);
		else if (action === 'next') void playNext(room, [s], quoted(s), onToast);
		else if (action === 'end') void addToQueue(room, [s], quoted(s), onToast);
		else void startRadio(room, s, onToast);
	}

	interface Row {
		icon: IconName;
		label: string;
		run: () => void;
		href?: string;
	}
	const rows = $derived.by(() => {
		const s = song;
		if (!s) return [];
		const list: Row[] = [];
		if (!s.podcast && may('start_radio')) list.push({ icon: 'radio', label: 'Start radio', run: () => act('radio') });
		if (youtube.account && isYoutubeSong(s.id)) list.push({ icon: liked ? 'liked' : 'like', label: liked ? 'Remove like' : 'Like', run: () => likes.set(s.id, !liked, onToast) });
		if (onSave && youtube.account && isYoutubeSong(s.id)) list.push({ icon: 'save', label: 'Save to playlist', run: () => (onClose(), onSave(s)) });
		const link = songLink(s);
		if (link) list.push({ icon: 'open', label: 'Open in YouTube Music', href: link, run: onClose });
		if (onShare && link) list.push({ icon: 'share', label: 'Share', run: () => (onClose(), onShare(s)) });
		return list;
	});
	const canPlay = $derived(may('play_now'));
	const canAdd = $derived(may('add_songs'));
</script>

{#snippet actions(big: boolean)}
	{#if canPlay}
		<button class="flex w-full items-center justify-center gap-2 rounded-full bg-white py-3 font-medium text-black active:bg-white/80" onclick={() => act('play')}>
			<Icon name="play" /> Play now
		</button>
	{/if}
	{#if canAdd}
		<div class="grid grid-cols-2 gap-2">
			<button class="flex items-center justify-center gap-2 rounded-full bg-white/10 py-2.5 text-sm font-medium hover:bg-white/20" onclick={() => act('next')}>
				<Icon name="playNext" size={20} /> Play next
			</button>
			<button class="flex items-center justify-center gap-2 rounded-full bg-white/10 py-2.5 text-sm font-medium hover:bg-white/20" onclick={() => act('end')}>
				<Icon name="playlistAdd" size={20} /> Add to queue
			</button>
		</div>
	{/if}
	{#if rows.length}
		<div class="-mx-4 border-t border-line pt-1 sm:-mx-6">
			{#each rows as r (r.label)}
				{#if r.href}
					<a class="flex w-full items-center {big ? 'gap-5 py-3.5' : 'gap-4 py-3'} px-4 text-left hover:bg-white/10 sm:px-6" href={r.href} target="_blank" rel="noopener" onclick={r.run}>
						<Icon name={r.icon} size={24} class="shrink-0" />{r.label}
					</a>
				{:else}
					<button class="flex w-full items-center {big ? 'gap-5 py-3.5' : 'gap-4 py-3'} px-4 text-left hover:bg-white/10 sm:px-6" onclick={r.run}>
						<Icon name={r.icon} size={24} class="shrink-0" />{r.label}
					</button>
				{/if}
			{/each}
		</div>
	{/if}
{/snippet}

{#snippet loading()}
	{#if error}
		<div class="flex flex-col items-center gap-3 py-8 text-center">
			<p class="text-muted">{error}</p>
			<button class="rounded-full bg-white px-5 py-2 text-sm font-medium text-black" onclick={load}>Try again</button>
		</div>
	{:else}
		<p class="py-8 text-center text-muted">Loading the song…</p>
	{/if}
{/snippet}

{#if sheet}
	<div class="fixed inset-0 z-[55] bg-black/60" role="presentation" transition:fade|global={{ duration: motionMs(150) }} onclick={onClose}></div>
	<div
		class="fixed inset-x-0 bottom-0 z-[56] mx-auto flex max-h-screen-85 w-full max-w-lg flex-col gap-3 overflow-y-auto rounded-t-2xl bg-raised px-4 pb-[max(1rem,env(safe-area-inset-bottom))] shadow-2xl sm:px-6"
		role="dialog"
		aria-label="Shared song"
		tabindex="-1"
		use:modal={() => onClose()}
		transition:fly|global={{ y: 500, duration: motionMs(200), opacity: 1 }}
	>
		<div class="mx-auto mt-2 h-1 w-9 shrink-0 rounded-full bg-white/30"></div>
		<p class="text-xs text-muted">Shared with you</p>
		{#if song}
			<div class="flex items-center gap-3">
				<Art {song} size={96} class="h-14 w-14" />
				<div class="min-w-0 flex-1">
					<p class="truncate font-medium">{song.title}</p>
					<p class="truncate text-sm text-muted">{song.podcast?.name ?? artistNames(song)}{song.album ? ` • ${song.album.name}` : ''}</p>
				</div>
			</div>
			{@render actions(false)}
		{:else}
			{@render loading()}
		{/if}
	</div>
{:else}
	<div
		class="fixed inset-0 z-[56] flex flex-col overflow-y-auto bg-bg pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]"
		role="dialog"
		aria-label="Shared song"
		tabindex="-1"
		use:modal={() => onClose()}
		transition:fly|global={{ y: 80, duration: motionMs(200) }}
	>
		<div class="flex shrink-0 items-center gap-2 px-2 py-2">
			<button class="rounded-full p-2 hover:bg-raised" aria-label="Close" onclick={onClose}><Icon name="close" /></button>
			<p class="flex-1 text-center text-sm text-muted">Shared with you</p>
			<span class="w-10"></span>
		</div>
		<div class="mx-auto flex w-full max-w-md flex-1 flex-col gap-4 px-4 pb-6 sm:px-6">
			{#if song}
				<Art {song} size={544} class="aspect-square w-full max-w-[min(100%,24rem)] self-center rounded-lg" />
				<div class="min-w-0">
					<h1 class="text-2xl font-bold break-words">{song.title}</h1>
					<p class="text-muted">{song.podcast?.name ?? artistNames(song)}{song.album ? ` • ${song.album.name}` : ''}</p>
				</div>
				{@render actions(true)}
			{:else}
				{@render loading()}
			{/if}
		</div>
	</div>
{/if}

<script lang="ts">
	// "Save to playlist", like YTM's: your own YouTube Music playlists, or a new one.
	import { thumbUrl } from '../images.svelte';
	import { createPlaylist, getMyPlaylists, saveToPlaylist } from '../api';
	import type { PlaylistSummary, Song } from '../protocol.gen';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';

	let { song, onClose, onToast }: { song: Song; onClose: () => void; onToast: (text: string) => void } = $props();

	let playlists = $state<PlaylistSummary[] | null>(null);
	let error = $state<string | null>(null);
	let busy = $state(false);
	let naming = $state(false);
	let name = $state('');

	getMyPlaylists()
		.then((p) => (playlists = p))
		.catch((e) => (error = e instanceof Error ? e.message : "Couldn't load your playlists"));

	async function save(playlist: PlaylistSummary) {
		busy = true;
		try {
			await saveToPlaylist(playlist.id, [song.id]);
			onToast(`Saved to ${playlist.title}`);
			onClose();
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't save it");
		} finally {
			busy = false;
		}
	}

	async function create(event: SubmitEvent) {
		event.preventDefault();
		const title = name.trim();
		if (!title) return;
		busy = true;
		try {
			await createPlaylist(title, [song.id]);
			onToast(`Saved to ${title}`);
			onClose();
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't make the playlist");
		} finally {
			busy = false;
		}
	}
</script>

<div class="fixed inset-0 z-[60] flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={onClose}>
	<div
		class="flex max-h-[80vh] w-full max-w-sm flex-col rounded-t-2xl bg-surface ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label="Save to playlist"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		onkeydown={(e) => e.key === 'Escape' && onClose()}
	>
		<div class="flex items-center gap-3 border-b border-line p-4">
			<Art {song} size={96} class="h-10 w-10" />
			<div class="min-w-0 flex-1">
				<p class="text-xs text-muted">Save to playlist</p>
				<p class="truncate font-medium">{song.title}</p>
			</div>
			<button class="rounded-full p-1 text-muted hover:bg-raised" aria-label="Close" onclick={onClose}><Icon name="close" /></button>
		</div>

		<div class="min-h-0 flex-1 overflow-y-auto py-2">
			{#if naming}
				<form class="flex gap-2 px-4 py-2" onsubmit={create}>
					<!-- svelte-ignore a11y_autofocus -->
					<input class="min-w-0 flex-1 rounded-lg bg-raised px-3 py-2 outline-none ring-white/40 focus:ring-2" bind:value={name} maxlength="150" placeholder="Playlist name" autofocus />
					<button class="rounded-full bg-white px-4 font-medium text-black disabled:opacity-40" disabled={busy || !name.trim()}>Create</button>
				</form>
			{:else}
				<button class="flex w-full items-center gap-3 px-4 py-2.5 text-left hover:bg-raised" onclick={() => (naming = true)}>
					<span class="grid h-10 w-10 place-items-center rounded bg-raised"><Icon name="save" size={22} /></span>
					<span class="font-medium">New playlist</span>
				</button>
			{/if}
			{#if error}
				<p class="px-4 py-2 text-sm text-muted">{error}</p>
			{:else if !playlists}
				<p class="px-4 py-2 text-sm text-muted">Loading your playlists…</p>
			{:else}
				{#each playlists as playlist (playlist.id)}
					<button class="flex w-full items-center gap-3 px-4 py-2.5 text-left hover:bg-raised disabled:opacity-50" disabled={busy} onclick={() => save(playlist)}>
						<span class="h-10 w-10 shrink-0 overflow-hidden rounded bg-raised">
							{#if playlist.thumbnails.at(-1)}<img src={thumbUrl(playlist.thumbnails, 120)} alt="" referrerpolicy="no-referrer" class="h-full w-full object-cover" />{/if}
						</span>
						<span class="min-w-0 flex-1 truncate">{playlist.title}</span>
					</button>
				{/each}
			{/if}
		</div>
	</div>
</div>

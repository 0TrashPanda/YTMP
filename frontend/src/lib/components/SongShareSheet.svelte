<script lang="ts">
	// Share a song (the song menu's Share): hold another phone against this one, or send its link
	// with the phone's share sheet, or copy it. The link is the song's YouTube Music link
	// (share.svelte.ts): a friend with YTMP gets its song screen, one without it YouTube Music.
	import { modal } from '../modal';
	import { artistNames } from '../format';
	import type { Song } from '../protocol.gen';
	import { copyLink, shareLink, songLink } from '../share.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import TapShareHint from './TapShareHint.svelte';

	let { song, onClose, onToast }: { song: Song; onClose: () => void; onToast: (text: string) => void } = $props();

	const link = $derived(songLink(song) ?? '');
</script>

<div class="fixed inset-0 z-[60] flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={onClose}>
	<!-- Keys: use:modal (Escape, Tab). The click only keeps it from reaching the backdrop. -->
	<!-- svelte-ignore a11y_click_events_have_key_events -->
	<div
		class="flex max-h-screen-90 w-full max-w-sm flex-col gap-4 overflow-y-auto rounded-t-2xl bg-surface p-4 pb-[max(1rem,env(safe-area-inset-bottom))] ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label="Share song"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		use:modal={() => onClose()}
	>
		<div class="flex items-center justify-between">
			<h2 class="font-bold">Share song</h2>
			<button class="rounded-full p-1 text-muted hover:bg-raised" aria-label="Close" onclick={onClose}><Icon name="close" /></button>
		</div>
		<div class="flex items-center gap-3">
			<Art {song} size={96} class="h-12 w-12" />
			<div class="min-w-0 flex-1">
				<p class="truncate font-medium">{song.title}</p>
				<p class="truncate text-sm text-muted">{song.podcast?.name ?? artistNames(song)}</p>
			</div>
		</div>
		{#if link}
			<TapShareHint url={link} what="this song" />
			<div class="grid grid-cols-2 gap-2">
				<button class="flex items-center justify-center gap-2 rounded-full bg-raised py-2.5 text-sm font-medium hover:bg-line" onclick={() => (onClose(), void shareLink(song.title, link, onToast))}>
					<Icon name="share" size={20} /> Send…
				</button>
				<button class="flex items-center justify-center gap-2 rounded-full bg-raised py-2.5 text-sm font-medium hover:bg-line" onclick={() => (onClose(), void copyLink(link, onToast))}>
					<Icon name="copy" size={20} /> Copy link
				</button>
			</div>
		{:else}
			<p class="text-sm text-muted">This song is only on this device, so it can't be shared.</p>
		{/if}
	</div>
</div>

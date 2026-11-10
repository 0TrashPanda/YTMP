<script lang="ts">
	import Icon from './Icon.svelte';
	import { artUrl } from '../format';
	import { images, smallUrl } from '../images.svelte';
	import Picture from './Picture.svelte';
	import type { Song } from '../protocol.gen';

	let {
		song,
		size,
		class: className = '',
		playing = null
	}: {
		song: Song | null;
		size: number;
		class?: string;
		/** This is the song playing in the room (playingMark.ts): bars over it, moving while it plays, like YTM. */
		playing?: 'moving' | 'still' | null;
	} = $props();
	const url = $derived(song ? artUrl(song, size) : null);
	const small = $derived(song ? smallUrl(song.thumbnails, size) : undefined);
	/** The picture that didn't load (local music without a cover): the note instead. */
	let broken = $state<string | null>(null);
	// Songs from a music video only have a wide picture: cropped to the square, or (setting "Video pictures") whole, with bars.
	const fit = $derived(images.videoArt === 'fit' && !!song?.thumbnails.some((t) => t.width > t.height * 1.2));
</script>

<div class="relative grid shrink-0 place-items-center overflow-hidden rounded text-muted {fit ? 'bg-black' : 'bg-raised'} {className}">
	{#if url && broken !== url}
		<Picture src={url} {small} class="h-full w-full {fit ? 'object-contain' : 'object-cover'}" onbroken={() => (broken = url)} />
	{:else}
		<Icon name="note" size={Math.round(size / 2.5)} />
	{/if}
	{#if playing}
		<span class="playing-bars absolute inset-0 grid place-items-center bg-black/55 {playing}" aria-hidden="true">
			<span class="flex h-[40%] max-h-5 items-end gap-[3px]">
				<i class="block h-full w-[3px] rounded-sm bg-white"></i><i class="block h-full w-[3px] rounded-sm bg-white"></i><i class="block h-full w-[3px] rounded-sm bg-white"></i>
			</span>
		</span>
	{/if}
</div>

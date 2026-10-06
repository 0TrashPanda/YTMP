<script lang="ts">
	import Icon from './Icon.svelte';
	import { artUrl } from '../format';
	import { images } from '../images.svelte';
	import type { Song } from '../protocol.gen';

	let { song, size, class: className = '' }: { song: Song | null; size: number; class?: string } = $props();
	const url = $derived(song ? artUrl(song, size) : null);
	// Songs from a music video only have a wide picture: cropped to the square, or (setting "Video pictures") whole, with bars.
	const fit = $derived(images.videoArt === 'fit' && !!song?.thumbnails.some((t) => t.width > t.height * 1.2));
</script>

<div class="grid shrink-0 place-items-center overflow-hidden rounded text-muted {fit ? 'bg-black' : 'bg-raised'} {className}">
	{#if url}
		<img src={url} alt="" class="h-full w-full {fit ? 'object-contain' : 'object-cover'}" loading="lazy" referrerpolicy="no-referrer" />
	{:else}
		<Icon name="note" size={Math.round(size / 2.5)} />
	{/if}
</div>

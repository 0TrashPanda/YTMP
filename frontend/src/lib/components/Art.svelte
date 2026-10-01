<script lang="ts">
	import Icon from './Icon.svelte';
	import { artUrl } from '../format';
	import type { Song } from '../protocol.gen';

	let { song, size, class: className = '' }: { song: Song | null; size: number; class?: string } = $props();
	const url = $derived(song ? artUrl(song, size) : null);
</script>

<div class="grid shrink-0 place-items-center overflow-hidden rounded bg-raised text-muted {className}">
	{#if url}
		<img src={url} alt="" class="h-full w-full object-cover" loading="lazy" referrerpolicy="no-referrer" />
	{:else}
		<Icon name="note" size={Math.round(size / 2.5)} />
	{/if}
</div>

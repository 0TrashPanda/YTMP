<script lang="ts">
	import type { AlbumSummary } from '../protocol.gen';

	let { albums, onOpen }: { albums: AlbumSummary[]; onOpen: (album: AlbumSummary) => void } = $props();

	const art = (a: AlbumSummary) => a.thumbnails.at(-1)?.url;
</script>

<div class="grid grid-cols-[repeat(auto-fill,minmax(9rem,1fr))] gap-4 px-2">
	{#each albums as album (album.id)}
		<button class="group flex min-w-0 flex-col gap-2 text-left" onclick={() => onOpen(album)}>
			<div class="aspect-square w-full overflow-hidden rounded-md bg-raised">
				{#if art(album)}
					<img src={art(album)} alt="" loading="lazy" class="h-full w-full object-cover transition-transform group-hover:scale-105" />
				{/if}
			</div>
			<div class="min-w-0">
				<p class="truncate text-sm font-medium">{album.title}</p>
				<p class="truncate text-xs text-muted">{[album.kind, album.year].filter(Boolean).join(' • ')}</p>
			</div>
		</button>
	{/each}
</div>

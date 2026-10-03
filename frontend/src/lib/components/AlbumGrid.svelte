<script lang="ts">
	import { prefetch } from '../api';
	import type { AlbumSummary } from '../protocol.gen';
	import CardGrid from './CardGrid.svelte';

	let { albums, onOpen }: { albums: AlbumSummary[]; onOpen: (album: AlbumSummary) => void } = $props();

	const cards = $derived(
		albums.map((album) => ({
			key: album.id,
			title: album.title,
			subtitle: [album.kind, album.year].filter(Boolean).join(' • '),
			art: album.thumbnails.at(-1)?.url,
			open: () => onOpen(album),
			prefetch: () => prefetch('album', album.id)
		}))
	);
</script>

<CardGrid {cards} />

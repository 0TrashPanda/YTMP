<script lang="ts">
	import { thumbUrl } from '../images.svelte';
	import { prefetch } from '../api';
	import type { AlbumSummary } from '../protocol.gen';
	import CardGrid from './CardGrid.svelte';

	let { albums, onOpen }: { albums: AlbumSummary[]; onOpen: (album: AlbumSummary) => void } = $props();

	const cards = $derived(
		albums.map((album) => ({
			key: album.id,
			title: album.title,
			subtitle: [album.kind, album.year].filter(Boolean).join(' • '),
			art: thumbUrl(album.thumbnails, 400),
			open: () => onOpen(album),
			prefetch: () => prefetch('album', album.id)
		}))
	);
</script>

<CardGrid {cards} />

<script lang="ts">
	import { smallUrl, thumbUrl } from '../images.svelte';
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
			small: smallUrl(album.thumbnails, 400),
			open: () => onOpen(album),
			prefetch: () => prefetch('album', album.id),
			// Right-click or hold: the album's menu, like everywhere else.
			menu: { kind: 'album' as const, id: album.id, title: album.title, subtitle: [album.kind, album.year].filter(Boolean).join(' • '), art: thumbUrl(album.thumbnails, 120) }
		}))
	);
</script>

<CardGrid {cards} />

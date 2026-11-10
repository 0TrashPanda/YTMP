<script lang="ts">
	// Settings → Artist pages: the order of the sections on an artist's page (sectionOrder.svelte.ts).
	import { artistOrder } from '../../sectionOrder.svelte';
	import SectionOrderList from './SectionOrderList.svelte';
</script>

<SectionOrderList
	order={artistOrder}
	label="Order of an artist's page"
	hint="The sections under the artist's name, from top to bottom. A section with nothing to show is left out. From your library has their songs you liked and their playlists and podcasts you saved; it needs your YouTube Music sign-in."
/>

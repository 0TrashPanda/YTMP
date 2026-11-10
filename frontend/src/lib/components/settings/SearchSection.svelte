<script lang="ts">
	// Settings → Search: what the search bar on the Library tab does, and the order of the
	// sections on the search page (sectionOrder.svelte.ts).
	import { searchOrder } from '../../sectionOrder.svelte';
	import { settings } from '../../settings.svelte';
	import SectionOrderList from './SectionOrderList.svelte';
	import Toggle from './Toggle.svelte';
</script>

<div class="flex flex-col gap-8">
	<Toggle
		label="Library tab searches your library"
		hint="On the Library tab the search bar only looks in your liked songs, playlists and podcasts. You can still search everywhere from the results."
		setting={settings.libraryTabSearch}
	/>

	<SectionOrderList
		order={searchOrder}
		label="Order of the results"
		hint="The order of the sections on the search page. A section with nothing to show is left out; From your library needs your YouTube Music sign-in."
	/>
</div>

<script lang="ts">
	// Settings → Search: what the search bar on the Library tab does, and the order of the
	// sections on the search page (searchOrder.svelte.ts).
	import { isDefaultSearchOrder, moveSearchSection, resetSearchOrder, searchSectionList } from '../../searchOrder.svelte';
	import { settings, type Setting } from '../../settings.svelte';
	import Icon from '../Icon.svelte';
	import DeviceNote from './DeviceNote.svelte';
	import DeviceToggle from './DeviceToggle.svelte';
	import Toggle from './Toggle.svelte';

	const list = $derived(searchSectionList());
	const iconButton = 'rounded-full p-1.5 text-muted hover:bg-raised hover:text-white disabled:opacity-30';
	const order = settings.searchOrder as Setting<unknown>;
</script>

<div class="flex flex-col gap-8">
	<Toggle
		label="Library tab searches your library"
		hint="On the Library tab the search bar only looks in your liked songs, playlists and podcasts. You can still search everywhere from the results."
		setting={settings.libraryTabSearch}
	/>

	<section class="flex flex-col gap-3">
		<div class="flex items-end gap-2">
			<div class="min-w-0 flex-1">
				<p class="text-sm font-medium">Order of the results</p>
				<p class="text-xs text-muted">The order of the sections on the search page. A section with nothing to show is left out; From your library needs your YouTube Music sign-in.</p>
			</div>
			<DeviceToggle setting={order} label="Order of the results" />
			{#if !isDefaultSearchOrder()}
				<button class="shrink-0 rounded-full px-3 py-1 text-xs text-muted hover:bg-raised hover:text-white" onclick={resetSearchOrder}>Defaults</button>
			{/if}
		</div>
		{#each list as { key, name }, i (key)}
			<div class="flex items-center gap-1">
				<span class="min-w-0 flex-1 truncate text-sm">{name}</span>
				<button class={iconButton} aria-label="Move {name} up" disabled={i === 0} onclick={() => moveSearchSection(key, list[i - 1].key)}>
					<Icon name="expand" size={20} />
				</button>
				<button class={iconButton} aria-label="Move {name} down" disabled={i === list.length - 1} onclick={() => moveSearchSection(key, list[i + 1].key)}>
					<Icon name="collapse" size={20} />
				</button>
			</div>
		{/each}
		<DeviceNote setting={order} />
	</section>
</div>

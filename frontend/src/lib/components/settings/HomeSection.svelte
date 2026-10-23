<script lang="ts">
	// Settings → Home: which of YouTube Music's sections Home shows, in which order, and each as a
	// carousel or a list (see home.svelte.ts). Sections are listed once Home has shown them.
	// And your pins: their order, and unpinning.
	import { home, movePin, moveSection, resetHome, setHidden, setHidePodcasts, setLayout, togglePin } from '../../home.svelte';
	import Icon from '../Icon.svelte';
	import Choice from './Choice.svelte';

	type Look = 'carousel' | 'list' | 'hidden';
	const look = (title: string): Look => (home.hidden.includes(title) ? 'hidden' : home.lists.includes(title) ? 'list' : 'carousel');
	function setLook(title: string, value: Look) {
		setHidden(title, value === 'hidden');
		if (value !== 'hidden') setLayout(title, value);
	}
	const KINDS = { playlist: 'Playlist', album: 'Album', artist: 'Artist', podcast: 'Podcast' };
	const changed = $derived(home.hidden.length > 0 || home.lists.length > 0 || home.hidePodcasts);
</script>

<div class="flex flex-col gap-8">
	<section class="flex flex-col gap-3">
		<div>
			<p class="text-sm font-medium">Pinned</p>
			<p class="text-xs text-muted">At the top of Home. Pin a playlist (Liked music too), album, artist or podcast with Pin to Home on its page.</p>
		</div>
		{#each home.pins as pin, i (pin.kind + pin.id)}
			<div class="flex items-center gap-1">
				<span class="min-w-0 flex-1 truncate text-sm">{pin.title} <span class="text-muted">· {KINDS[pin.kind]}</span></span>
				<button class="rounded-full p-1.5 text-muted hover:bg-raised hover:text-white disabled:opacity-30" aria-label="Move {pin.title} up" disabled={i === 0} onclick={() => movePin(i, i - 1)}>
					<Icon name="expand" size={20} />
				</button>
				<button
					class="rounded-full p-1.5 text-muted hover:bg-raised hover:text-white disabled:opacity-30"
					aria-label="Move {pin.title} down"
					disabled={i === home.pins.length - 1}
					onclick={() => movePin(i, i + 1)}
				>
					<Icon name="collapse" size={20} />
				</button>
				<button class="rounded-full p-1.5 text-muted hover:bg-raised hover:text-white" aria-label="Unpin {pin.title}" onclick={() => togglePin(pin)}>
					<Icon name="close" size={20} />
				</button>
			</div>
		{:else}
			<p class="text-sm text-muted">Nothing pinned yet.</p>
		{/each}
	</section>

	<Choice
		label="Podcast sections"
		hint="Sections of podcasts and episodes that YouTube Music suggests."
		options={[
			['show', 'Show'],
			['hide', 'Hide']
		] as ['show' | 'hide', string][]}
		value={home.hidePodcasts ? 'hide' : 'show'}
		onchange={(v) => setHidePodcasts(v === 'hide')}
	/>

	<section class="flex flex-col gap-3">
		<div class="flex items-end gap-2">
			<div class="min-w-0 flex-1">
				<p class="text-sm font-medium">Sections</p>
				<p class="text-xs text-muted">
					In the order Home shows them. A list shows the songs one under the other, where tapping and swiping do what Taps and swipes says.
				</p>
			</div>
			{#if changed}
				<button class="shrink-0 rounded-full px-3 py-1 text-xs text-muted hover:bg-raised hover:text-white" onclick={resetHome}>Defaults</button>
			{/if}
		</div>
		{#each home.order as title, i (title)}
			{@const value = look(title)}
			<div class="flex items-center gap-1">
				<span class="min-w-0 flex-1 truncate text-sm {value === 'hidden' ? 'text-muted line-through' : ''}">
					{title}{home.podcastSections.includes(title) ? ' (podcasts)' : ''}
				</span>
				<button class="rounded-full p-1.5 text-muted hover:bg-raised hover:text-white disabled:opacity-30" aria-label="Move {title} up" disabled={i === 0} onclick={() => moveSection(i, i - 1)}>
					<Icon name="expand" size={20} />
				</button>
				<button
					class="rounded-full p-1.5 text-muted hover:bg-raised hover:text-white disabled:opacity-30"
					aria-label="Move {title} down"
					disabled={i === home.order.length - 1}
					onclick={() => moveSection(i, i + 1)}
				>
					<Icon name="collapse" size={20} />
				</button>
				<select
					class="rounded-lg bg-raised px-2 py-1.5 text-sm outline-none"
					aria-label="How Home shows {title}"
					{value}
					onchange={(e) => setLook(title, e.currentTarget.value as Look)}
				>
					<option value="carousel">Carousel</option>
					<option value="list">List</option>
					<option value="hidden">Hidden</option>
				</select>
			</div>
		{:else}
			<p class="text-sm text-muted">Open Home once, and its sections show here.</p>
		{/each}
	</section>
	<p class="text-xs text-muted">
		YouTube Music picks the sections and can rename them; a new one shows where YouTube Music puts it. Every section's ⋮ on Home does the same. Saved on this device.
	</p>
</div>

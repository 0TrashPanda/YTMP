<script lang="ts">
	// Settings → Home: your pins (a tile in Pinned, or a section with their songs) and which
	// sections Home shows, in which order, and each as a carousel or a list (see home.svelte.ts).
	// YouTube Music's sections are listed once Home has shown them.
	import { home, movePin, moveSection, PINNED, resetHome, sectionList, setHidden, setHidePodcasts, setLayout, setPinContents, togglePin } from '../../home.svelte';
	import Icon from '../Icon.svelte';
	import Choice from './Choice.svelte';

	type Look = 'carousel' | 'list' | 'hidden';
	const look = (key: string): Look => (home.hidden.includes(key) ? 'hidden' : home.lists.includes(key) ? 'list' : 'carousel');
	function setLook(key: string, value: Look) {
		setHidden(key, value === 'hidden');
		if (value !== 'hidden') setLayout(key, value);
	}
	const KINDS = { playlist: 'Playlist', album: 'Album', artist: 'Artist', podcast: 'Podcast' };
	const list = $derived.by(() => {
		void [home.order, home.pins];
		return sectionList();
	});
	const changed = $derived(home.hidden.length > 0 || home.lists.length > 0 || home.hidePodcasts);
	const iconButton = 'rounded-full p-1.5 text-muted hover:bg-raised hover:text-white disabled:opacity-30';
	const select = 'rounded-lg bg-raised px-2 py-1.5 text-sm outline-none';
</script>

<div class="flex flex-col gap-8">
	<section class="flex flex-col gap-3">
		<div>
			<p class="text-sm font-medium">Pinned</p>
			<p class="text-xs text-muted">
				Pin a playlist (Liked music too), album, artist or podcast with Pin to Home on its page. A tile goes in the Pinned section; a section shows its songs (a
				podcast: its newest episodes) as a section of its own.
			</p>
		</div>
		{#each home.pins as pin, i (pin.kind + pin.id)}
			<div class="flex items-center gap-1">
				<span class="min-w-0 flex-1 truncate text-sm">{pin.title} <span class="text-muted">· {KINDS[pin.kind]}</span></span>
				{#if !pin.contents}
					<button class={iconButton} aria-label="Move {pin.title} up" disabled={i === 0} onclick={() => movePin(i, i - 1)}>
						<Icon name="expand" size={20} />
					</button>
					<button class={iconButton} aria-label="Move {pin.title} down" disabled={i === home.pins.length - 1} onclick={() => movePin(i, i + 1)}>
						<Icon name="collapse" size={20} />
					</button>
				{/if}
				<select
					class={select}
					aria-label="How Home shows {pin.title}"
					value={pin.contents ? 'section' : 'tile'}
					onchange={(e) => setPinContents(pin, e.currentTarget.value === 'section')}
				>
					<option value="tile">Tile</option>
					<option value="section">Section</option>
				</select>
				<button class={iconButton} aria-label="Unpin {pin.title}" onclick={() => togglePin(pin)}>
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
		{#each list as { key, title, pin }, i (key)}
			{@const value = look(key)}
			<div class="flex items-center gap-1">
				<span class="min-w-0 flex-1 truncate text-sm {value === 'hidden' ? 'text-muted line-through' : ''}">
					{title}{pin ? ' (pinned)' : home.podcastSections.includes(key) ? ' (podcasts)' : ''}
				</span>
				<button class={iconButton} aria-label="Move {title} up" disabled={i === 0} onclick={() => moveSection(key, list[i - 1].key)}>
					<Icon name="expand" size={20} />
				</button>
				<button class={iconButton} aria-label="Move {title} down" disabled={i === list.length - 1} onclick={() => moveSection(key, list[i + 1].key)}>
					<Icon name="collapse" size={20} />
				</button>
				<select class={select} aria-label="How Home shows {title}" {value} onchange={(e) => setLook(key, e.currentTarget.value as Look)}>
					<option value="carousel">{key === PINNED ? 'Tiles' : 'Carousel'}</option>
					<option value="list">List</option>
					<option value="hidden">Hidden</option>
				</select>
			</div>
		{:else}
			<p class="text-sm text-muted">Open Home once, and its sections show here.</p>
		{/each}
	</section>
	<p class="text-xs text-muted">
		YouTube Music picks its sections and can rename them; a new one shows where YouTube Music puts it. Every section's ⋮ on Home does the same. Saved on this device.
	</p>
</div>

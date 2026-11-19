<script lang="ts">
	// A room's last covers as a small collage, like a mix in YouTube Music: four in a square,
	// or the newest one alone. Without covers, the room's icon. A badge in the corner tells party
	// rooms (red, people) from solo rooms (dark, headphones). `class` sets the size (a small square
	// by default; the home screen's tiles on wide screens make it fill the tile).
	import Icon from './Icon.svelte';
	import { hideIfBroken } from '../pictures';

	let { covers, party, class: size = 'h-11 w-11' }: { covers: string[]; party: boolean; class?: string } = $props();
	const shown = $derived(covers.length >= 4 ? covers.slice(0, 4) : covers.slice(0, 1));
</script>

<span class="relative shrink-0 rounded {size}">
	<span class="grid h-full w-full overflow-hidden rounded-[inherit] {party ? 'bg-accent-strong/25' : 'bg-raised'} {shown.length === 4 ? 'grid-cols-2' : ''}">
		<span data-placeholder class="absolute inset-0 flex items-center justify-center {party ? 'text-accent' : 'text-muted'}">
			<Icon name={party ? 'people' : 'headphones'} size={20} />
		</span>
		{#each shown as cover (cover)}
			<img src={cover} alt="" use:hideIfBroken loading="lazy" referrerpolicy="no-referrer" class="relative h-full w-full object-cover" />
		{/each}
	</span>
	{#if shown.length > 0}
		<span
			class="absolute -right-1 -bottom-1 flex h-5 w-5 items-center justify-center rounded-full ring-2 ring-surface lg:right-2 lg:bottom-2 lg:h-auto lg:w-auto lg:gap-1 lg:px-2.5 lg:py-1 lg:ring-0 {party
				? 'bg-accent-strong text-white'
				: 'bg-black/80 text-white'}"
		>
			<Icon name={party ? 'people' : 'headphones'} size={13} />
			<span class="hidden text-xs font-medium lg:inline">{party ? 'Party' : 'Solo'}</span>
		</span>
	{/if}
</span>

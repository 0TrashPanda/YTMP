<script lang="ts">
	// A room's last covers as a small collage, like a mix in YouTube Music: four in a square,
	// or the newest one alone. Without covers, the room's icon.
	import Icon from './Icon.svelte';
	import { hideIfBroken } from '../pictures';

	let { covers, party }: { covers: string[]; party: boolean } = $props();
	const shown = $derived(covers.length >= 4 ? covers.slice(0, 4) : covers.slice(0, 1));
</script>

<span class="relative grid h-11 w-11 shrink-0 overflow-hidden rounded bg-raised {shown.length === 4 ? 'grid-cols-2' : ''}">
	<span class="absolute inset-0 flex items-center justify-center text-muted">
		<Icon name={party ? 'people' : 'headphones'} size={20} />
	</span>
	{#each shown as cover (cover)}
		<img src={cover} alt="" use:hideIfBroken loading="lazy" referrerpolicy="no-referrer" class="relative h-full w-full object-cover" />
	{/each}
</span>

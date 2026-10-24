<script lang="ts">
	// Settings → Solo rooms: the party and sharing bits a solo room leaves out (see soloLook.svelte.ts).
	import { setSoloHidden, SOLO_PARTS, soloHidden } from '../../soloLook.svelte';
	import Switch from './Switch.svelte';
</script>

<div class="flex flex-col gap-4">
	<p class="text-sm text-muted">What a solo room shows. Switch off what you don't need when you listen alone; parties always show everything.</p>
	{#each SOLO_PARTS as [part, label, hint] (part)}
		<div class="flex items-center gap-4">
			<div class="min-w-0 flex-1">
				<p class="text-sm">{label}</p>
				<p class="text-xs text-muted">{hint}</p>
			</div>
			<Switch {label} checked={!soloHidden(part)} onchange={(on) => setSoloHidden(part, !on)} />
		</div>
	{/each}
</div>

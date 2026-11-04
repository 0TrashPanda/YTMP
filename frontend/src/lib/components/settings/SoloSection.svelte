<script lang="ts">
	// Settings → Solo rooms: the party and sharing bits a solo room leaves out (see soloLook.svelte.ts).
	import { settings } from '../../settings.svelte';
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
	<!-- Only does something while the room code is off. -->
	<div class="flex items-center gap-4 {soloHidden('code') ? '' : 'opacity-50'}">
		<div class="min-w-0 flex-1">
			<p class="text-sm">Wide search box</p>
			<p class="text-xs text-muted">Without the room code, search fills the top, also on phones.</p>
		</div>
		<Switch label="Wide search box" checked={settings.soloSearchField.value} onchange={(on) => settings.soloSearchField.set(on)} />
	</div>
</div>

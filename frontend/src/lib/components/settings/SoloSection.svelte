<script lang="ts">
	// Settings → Solo rooms: the party and sharing bits a solo room leaves out (see soloLook.svelte.ts).
	import { preferences } from '../../preferences.svelte';
	import { setSoloHidden, SOLO_PARTS, soloHidden } from '../../soloLook.svelte';
	import Switch from './Switch.svelte';

	$effect(() => void preferences.load());
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
	<p class="text-xs {preferences.error ? 'text-accent' : 'text-muted'}">
		{#if preferences.error}
			Couldn't save it to your account; it's saved on this device for now.
		{:else if preferences.onAccount}
			Saved to your YTMP account: the same on all your devices.
		{:else}
			Saved on this device. Log in with your YTMP account to keep it there.
		{/if}
	</p>
</div>

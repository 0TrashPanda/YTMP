<script lang="ts">
	// The phone icon next to a setting (settings.svelte.ts): dim while the setting follows your
	// account, "This phone" while this device keeps its own. Tapping switches between the two.
	// Only with an account (without one, everything is this device's anyway).
	import { nativeBridge } from '../../native';
	import { onAccount, type Setting } from '../../settings.svelte';
	import Icon from '../Icon.svelte';

	let { setting, label }: { setting: Setting<unknown>; label: string } = $props();

	const here = nativeBridge ? 'phone' : 'device';
</script>

{#if onAccount() || setting.onlyHere}
	{#if setting.onlyHere}
		<button
			class="flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium text-link hover:bg-raised"
			aria-label="{label}: only on this {here}. Follow your account again"
			title="Only on this {here}. Tap to follow your account again."
			onclick={() => setting.keepHere(false)}
		>
			<Icon name="phone" size={14} /> This {here}
		</button>
	{:else}
		<button
			class="shrink-0 rounded-full p-1 text-muted/40 hover:bg-raised hover:text-muted"
			aria-label="{label}: keep it only on this {here}"
			title="Same on all your devices. Tap to keep it different on this {here}."
			onclick={() => setting.keepHere(true)}
		>
			<Icon name="phone" size={16} />
		</button>
	{/if}
{/if}

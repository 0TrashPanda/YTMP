<script lang="ts">
	// Under a setting this device keeps its own (DeviceToggle): what your account has, to go back to it.
	import type { Setting } from '../../settings.svelte';

	let { setting, account }: { setting: Setting<unknown>; /** The account's value, in words; none: just "Use your account's". */ account?: string } = $props();
</script>

{#if setting.onlyHere}
	<p class="text-xs text-muted">
		{#if account}Your account: {account} ·{/if}
		<button class="text-link hover:underline" onclick={() => setting.keepHere(false)}>{account ? 'Use that' : "Use your account's"}</button>
	</p>
{/if}

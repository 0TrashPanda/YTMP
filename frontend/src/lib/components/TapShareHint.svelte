<script lang="ts">
	// "Hold a phone against this one" in a Share sheet: while it shows, holding another phone
	// against this one hands over [url] (share.svelte.ts). Only in the app on a phone with NFC,
	// with Settings → Sharing on. NFC switched off: a button to switch it on.
	import { onMount } from 'svelte';
	import { nativeBridge } from '../native';
	import { shareByTap, tapShareState } from '../share.svelte';
	import Icon from './Icon.svelte';

	let { url, what }: { url: string; /** What the other phone opens: "this room", "this song". */ what: string } = $props();

	let state = $state(tapShareState());
	// Back from Android's settings with NFC on (or off).
	onMount(() => {
		const recheck = () => document.visibilityState === 'visible' && (state = tapShareState());
		document.addEventListener('visibilitychange', recheck);
		return () => document.removeEventListener('visibilitychange', recheck);
	});
	$effect(() => (state === 'on' && url ? shareByTap(url) : undefined));
</script>

{#if state === 'on'}
	<div class="flex flex-col items-center gap-1 py-1 text-center">
		<Icon name="nfc" size={36} />
		<p class="font-medium">Hold a phone against this one</p>
		<p class="text-xs text-muted">Their phone opens {what}. Keep this screen on.</p>
	</div>
{:else if state === 'off'}
	<button class="flex w-full items-center gap-3 rounded-lg bg-raised px-3 py-2 text-left text-sm hover:bg-line" onclick={() => nativeBridge?.openNfcSettings?.()}>
		<Icon name="nfc" size={24} class="shrink-0 text-muted" />
		<span class="flex-1">Switch on NFC to share by holding phones together</span>
	</button>
{/if}

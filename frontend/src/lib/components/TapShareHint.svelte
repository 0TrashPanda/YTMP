<script lang="ts">
	// "Hold a phone against this one" in a Share sheet: while it shows, holding another phone
	// against this one hands over [url] (share.svelte.ts). Only in the app on a phone with NFC,
	// with Settings → Sharing on. NFC switched off: a button to switch it on.
	import { onMount } from 'svelte';
	import { nativeBridge } from '../native';
	import { shareByTap, tapShareState } from '../share.svelte';
	import { saved } from '../storage';
	import Icon from './Icon.svelte';
	import Switch from './settings/Switch.svelte';

	let { url, what }: { url: string; /** What the other phone opens: "this room", "this song". */ what: string } = $props();

	let nfc = $state(tapShareState());
	// Back from Android's settings with NFC on (or off).
	onMount(() => {
		const recheck = () => document.visibilityState === 'visible' && (nfc = tapShareState());
		document.addEventListener('visibilitychange', recheck);
		return () => document.removeEventListener('visibilitychange', recheck);
	});
	// Name YTMP on the tag: their phone opens it in YTMP, even where YouTube Music claims the
	// link; an Android phone without YTMP then gets a store page instead. Older apps: always on.
	const canChoose = !!nativeBridge?.setTapShareFor;
	let inApp = $state(saved.tapShareInApp);
	$effect(() => (nfc === 'on' && url ? shareByTap(url, inApp) : undefined));
</script>

{#if nfc === 'on'}
	<div class="flex flex-col items-center gap-1 py-1 text-center">
		<Icon name="nfc" size={36} />
		<p class="font-medium">Hold a phone against this one</p>
		<p class="text-xs text-muted">Their phone opens {what}. Keep this screen on.</p>
		{#if canChoose}
			<div class="mt-2 flex w-full items-center gap-3 text-left">
				<div class="flex-1">
					<p class="text-sm">Open in YTMP on their phone</p>
					<p class="text-xs text-muted">
						{inApp ? 'For phones with YTMP. An Android phone without it gets a store page.' : `Opens where the link goes: ${what === 'this song' ? 'YouTube Music' : 'the browser'}, also on phones with YTMP.`}
					</p>
				</div>
				<Switch label="Open in YTMP on their phone" checked={inApp} onchange={(on) => (saved.tapShareInApp = inApp = on)} />
			</div>
		{/if}
	</div>
{:else if nfc === 'off'}
	<button class="flex w-full items-center gap-3 rounded-lg bg-raised px-3 py-2 text-left text-sm hover:bg-line" onclick={() => nativeBridge?.openNfcSettings?.()}>
		<Icon name="nfc" size={24} class="shrink-0 text-muted" />
		<span class="flex-1">Switch on NFC to share by holding phones together</span>
	</button>
{/if}

<script lang="ts">
	// The choices for a radio (docs/features/history-and-recommendations.md#radio--mix): where it
	// comes from, songs you know or new ones, with or without your YouTube Music account, and in
	// parties whose taste. In the hold sheet of Start radio (for one radio) and in Settings → Radio
	// (the defaults). [show] says which of them (Settings → Song menu can hide some).
	import type { RadioOptions } from '../protocol.gen';
	import { RADIO_FROM, discoverLabel } from '../radio.svelte';

	let {
		value = $bindable(),
		show = ['from', 'discover', 'account', 'taste'],
		hasAccount = false,
		party = false
	}: {
		value: RadioOptions;
		show?: string[];
		/** Your YouTube Music account is linked here: the radio can be made as you, or without it. */
		hasAccount?: boolean;
		/** Others are in the room: their listening, or yours. */
		party?: boolean;
	} = $props();

	const chip = (on: boolean) => `shrink-0 rounded-lg px-3 py-1.5 text-sm ${on ? 'bg-white text-black' : 'bg-white/10 hover:bg-white/20'}`;
	const hint = $derived(RADIO_FROM.find((f) => f.value === value.from)?.hint ?? '');
</script>

<div class="flex flex-col gap-4">
	{#if show.includes('from')}
		<div>
			<p class="mb-2 text-sm text-muted">Radio from</p>
			<div class="flex flex-wrap gap-2">
				{#each RADIO_FROM as f (f.value)}
					<button type="button" class={chip(value.from === f.value)} aria-pressed={value.from === f.value} onclick={() => (value = { ...value, from: f.value })}>{f.label}</button>
				{/each}
			</div>
			<p class="mt-1.5 text-xs text-muted">{hint}</p>
		</div>
	{/if}
	{#if show.includes('discover')}
		<label class="block">
			<span class="mb-1 flex justify-between text-sm text-muted"><span>Songs you know</span><span>New to you</span></span>
			<input
				type="range"
				min="-1"
				max="1"
				step="0.25"
				class="w-full accent-white"
				value={value.discover}
				oninput={(e) => (value = { ...value, discover: Number(e.currentTarget.value) })}
			/>
			<span class="text-xs text-muted">{discoverLabel(value.discover)}</span>
		</label>
	{/if}
	{#if show.includes('account') && hasAccount && (value.from === 'mix' || value.from === 'ytm')}
		<div>
			<p class="mb-2 text-sm text-muted">YouTube Music's radio</p>
			<div class="flex flex-wrap gap-2">
				<button type="button" class={chip(value.withAccount === null)} onclick={() => (value = { ...value, withAccount: null })}>As you set it</button>
				<button type="button" class={chip(value.withAccount === true)} onclick={() => (value = { ...value, withAccount: true })}>As you</button>
				<button type="button" class={chip(value.withAccount === false)} onclick={() => (value = { ...value, withAccount: false })}>Without your account</button>
			</div>
		</div>
	{/if}
	{#if show.includes('taste') && party && value.from !== 'ytm'}
		<div>
			<p class="mb-2 text-sm text-muted">From whose listening</p>
			<div class="flex flex-wrap gap-2">
				<button type="button" class={chip(value.myTaste)} onclick={() => (value = { ...value, myTaste: true })}>Yours</button>
				<button type="button" class={chip(!value.myTaste)} onclick={() => (value = { ...value, myTaste: false })}>Everyone's on your server</button>
			</div>
		</div>
	{/if}
</div>

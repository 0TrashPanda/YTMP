<script lang="ts">
	import { renderSVG } from 'uqr';
	import type { HostInfo } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Icon from './Icon.svelte';

	let {
		room,
		host,
		onClose,
		onToast
	}: { room: RoomConnection; host: HostInfo | null; onClose: () => void; onToast: (text: string) => void } = $props();

	const info = $derived(room.state?.room);
	const isPrivate = $derived(info?.visibility === 'private');
	const isOwner = $derived(room.me?.isOwner ?? false);
	// Friends need an address they can reach: the phone's Wi-Fi address, or this page's own.
	const link = $derived(info ? `${host?.shareUrl ?? location.origin}/room/${info.code}` : '');
	const qr = $derived(link ? renderSVG(link, { whiteColor: '#ffffff', blackColor: '#000000', border: 2 }) : '');

	async function copy(text: string, what: string) {
		try {
			await navigator.clipboard.writeText(text);
			onToast(`${what} copied`);
		} catch {
			onToast(text);
		}
	}

	async function makePublic() {
		const error = await room.run({ kind: 'SetVisibility', visibility: 'public' });
		if (error) onToast(error.message);
	}
</script>

<div class="fixed inset-0 z-40 flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={onClose}>
	<div
		class="flex w-full max-w-sm flex-col items-center gap-4 rounded-t-2xl bg-surface p-6 ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label="Share room"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		onkeydown={(e) => e.key === 'Escape' && onClose()}
	>
		<div class="flex w-full items-center justify-between">
			<h2 class="text-lg font-bold">{info?.name}</h2>
			<button class="rounded-full p-1 text-muted hover:bg-raised" aria-label="Close" onclick={onClose}><Icon name="close" /></button>
		</div>

		{#if isPrivate}
			<p class="text-center text-muted">This is a <strong class="text-white">solo room</strong>: only you can listen here.</p>
			{#if isOwner}
				<button class="w-full rounded-full bg-accent py-3 font-medium" onclick={makePublic}>
					Make public, so friends on this Wi-Fi can join
				</button>
			{/if}
		{:else}
			<!-- eslint-disable-next-line svelte/no-at-html-tags -- SVG generated locally from the link -->
			<div class="w-56 overflow-hidden rounded-xl">{@html qr}</div>
			<p class="text-center text-sm text-muted">Scan with a phone camera, or open the link in a browser.</p>
			<button class="w-full truncate rounded-lg bg-raised px-3 py-2 font-mono text-sm" onclick={() => copy(link, 'Link')}>{link}</button>
			<button class="flex items-center gap-2 font-mono text-3xl tracking-[0.3em]" onclick={() => copy(info?.code ?? '', 'Room code')}>
				{info?.code}
				<Icon name="copy" size={20} class="text-muted" />
			</button>
			{#if host?.kind === 'phone'}
				<p class="text-center text-xs text-muted">Friends must be on the same Wi-Fi as this phone.</p>
			{/if}
		{/if}
	</div>
</div>

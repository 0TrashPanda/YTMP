<script lang="ts">
	import { renderSVG } from 'uqr';
	import type { HostInfo } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Icon from './Icon.svelte';

	let {
		room,
		host,
		onClose,
		onToast,
		onClone
	}: { room: RoomConnection; host: HostInfo | null; onClose: () => void; onToast: (text: string) => void; onClone?: () => void } = $props();

	const info = $derived(room.state?.room);
	const isPrivate = $derived(info?.visibility === 'private');
	const isOwner = $derived(room.me?.isOwner ?? false);
	// Friends need an address they can reach: the phone's Wi-Fi address (or its hotspot, a VPN,
	// … when it has several: pick one), or this page's own.
	const local = location.hostname === '127.0.0.1' || location.hostname === 'localhost';
	const addresses = $derived.by(() => {
		const list = [...(host?.shareAddresses ?? [])];
		const add = (url: string | null | undefined, label: string) => url && !list.some((a) => a.url === url) && list.push({ url, label });
		add(host?.shareUrl, 'Network');
		if (!local) add(location.origin, new URL(location.origin).host);
		return list;
	});
	let picked = $state<string | null>(read());
	function read(): string | null {
		try {
			return localStorage.getItem('ytmp.shareAddress');
		} catch {
			return null;
		}
	}
	function pick(url: string) {
		picked = url;
		try {
			localStorage.setItem('ytmp.shareAddress', url);
		} catch {
			// Picked again next time.
		}
	}
	const base = $derived(addresses.find((a) => a.url === picked)?.url ?? addresses[0]?.url ?? location.origin);
	const link = $derived(info ? `${base}/room/${info.code}` : '');
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
			{#if addresses.length > 1}
				<div class="flex w-full flex-wrap justify-center gap-2" role="radiogroup" aria-label="Address">
					{#each addresses as address (address.url)}
						<button
							class="rounded-full px-3 py-1 text-sm {address.url === base ? 'bg-white font-medium text-black' : 'bg-raised text-muted hover:bg-line'}"
							role="radio"
							aria-checked={address.url === base}
							title={address.url}
							onclick={() => pick(address.url)}
							>{address.label}{addresses.filter((a) => a.label === address.label).length > 1 ? ` ${new URL(address.url).hostname}` : ''}</button
						>
					{/each}
				</div>
			{/if}
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
			{#if info?.hasPassword}
				<p class="text-center text-xs text-muted">🔒 Joining needs the room's password (Room settings → Overview).</p>
			{/if}
		{/if}
		{#if onClone}
			<button class="text-sm text-muted underline hover:text-white" onclick={onClone}>Clone this room somewhere else</button>
		{/if}
	</div>
</div>

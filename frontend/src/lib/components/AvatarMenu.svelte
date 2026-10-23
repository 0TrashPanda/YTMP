<script lang="ts">
	// The avatar (top right): who you are, what you do often in this room (share, play on,
	// clone, its settings, leave), and Settings for the rest. Like YouTube Music's account menu.
	import { identity } from '../account';
	import type { HostInfo } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import { shows } from '../soloLook.svelte';
	import { youtube } from '../youtube.svelte';
	import Icon, { type IconName } from './Icon.svelte';

	let {
		host,
		room = null,
		onClose,
		onSettings,
		onShare,
		onPlayOn,
		onClone,
		onRoomSettings,
		onPartyScreen,
		onShowOnTv,
		onLeave
	}: {
		host: HostInfo | null;
		/** In a room: its actions show too. */
		room?: RoomConnection | null;
		onClose: () => void;
		/** Opens Settings (at the Account section when [account]). */
		onSettings: (section?: 'account' | 'youtube') => void;
		onShare?: () => void;
		onPlayOn?: () => void;
		onClone?: () => void;
		/** Only when you may change the room's settings. */
		onRoomSettings?: () => void;
		/** The party screen on this device. */
		onPartyScreen?: () => void;
		/** Link a TV as the party screen. */
		onShowOnTv?: () => void;
		onLeave?: () => void;
	} = $props();

	const me = $derived(host ? identity.get(host.authServers) : identity.stored);
	const info = $derived(room?.state?.room);

	function run(action?: () => void) {
		onClose();
		action?.();
	}
</script>

{#snippet item(icon: IconName, label: string, action: (() => void) | undefined, danger = false)}
	{#if action}
		<button class="flex items-center gap-4 rounded-lg px-3 py-2.5 text-left hover:bg-raised {danger ? 'text-accent' : ''}" onclick={() => run(action)}>
			<Icon name={icon} size={20} class={danger ? '' : 'text-muted'} />
			<span class="flex-1">{label}</span>
		</button>
	{/if}
{/snippet}

<div class="fixed inset-0 z-50 flex items-end justify-center bg-black/60 sm:items-start sm:justify-end sm:bg-transparent" role="presentation" onclick={onClose}>
	<div
		class="flex max-h-[90%] w-full flex-col gap-1 overflow-y-auto rounded-t-2xl bg-surface p-3 ring-1 ring-line sm:mt-14 sm:mr-3 sm:w-80 sm:rounded-xl sm:shadow-2xl"
		role="dialog"
		aria-label="Account and settings"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		onkeydown={(e) => e.key === 'Escape' && onClose()}
	>
		<!-- Who you are: your YTMP account and your YouTube Music. -->
		<button class="flex items-center gap-3 rounded-lg px-3 py-2 text-left hover:bg-raised" onclick={() => run(() => onSettings('account'))}>
			{#if youtube.account?.photoUrl}
				<img src={youtube.account.photoUrl} alt="" referrerpolicy="no-referrer" class="h-10 w-10 rounded-full" />
			{:else}
				<span class="grid h-10 w-10 place-items-center rounded-full bg-raised"><Icon name="person" class="text-muted" /></span>
			{/if}
			<span class="min-w-0 flex-1">
				<span class="block truncate font-medium">{me?.account.displayName ?? (room?.me?.name || 'Guest')}</span>
				<span class="block truncate text-xs text-muted">
					{me ? me.account.id : host?.authServers.length ? 'Not logged in · Log in' : 'Guest'}
					{#if youtube.account}· YouTube Music: {youtube.account.name}{/if}
				</span>
			</span>
		</button>

		{#if room && info}
			<div class="my-1 h-px bg-line"></div>
			<p class="truncate px-3 pt-1 text-xs font-medium tracking-wide text-muted uppercase">{info.name} · {info.code}</p>
			{@render item('share', info.visibility === 'private' ? 'Solo room: make it public' : 'Share room', onShare)}
			{@render item('cast', 'Play on…', onPlayOn)}
			{#if shows(room, 'partyMenu')}
				{@render item('fullscreen', 'Party screen', onPartyScreen)}
				{@render item('tv', 'Show on a TV', onShowOnTv)}
				{@render item('copy', 'Clone room', room.can('clone_room') ? onClone : undefined)}
			{/if}
			{@render item('people', 'Room settings', onRoomSettings)}
			{@render item('logout', 'Leave room', onLeave, true)}
		{/if}

		<div class="my-1 h-px bg-line"></div>
		{@render item('settings', 'Settings', () => onSettings())}
	</div>
</div>

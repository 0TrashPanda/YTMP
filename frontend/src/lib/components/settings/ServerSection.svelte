<script lang="ts">
	// Settings → Server admin (the server's admins only): accounts and invite links, the server's
	// settings, its rooms, and versions (docs/features/server-admin.md).
	import type { AccountInfo, AuthServerInfo } from '../../protocol.gen';
	import AdminAbout from './AdminAbout.svelte';
	import AdminAccounts from './AdminAccounts.svelte';
	import AdminLog from './AdminLog.svelte';
	import AdminRooms from './AdminRooms.svelte';
	import AdminSettings from './AdminSettings.svelte';

	let { info, me, onToast }: { info: AuthServerInfo; me: AccountInfo; onToast: (text: string) => void } = $props();

	type Tab = 'accounts' | 'settings' | 'rooms' | 'log' | 'about';
	const tabs: [Tab, string][] = [
		['accounts', 'Accounts'],
		['settings', 'Settings'],
		['rooms', 'Rooms'],
		['log', 'Log'],
		['about', 'About']
	];
	let tab = $state<Tab>('accounts');
</script>

<div class="flex gap-2 overflow-x-auto [scrollbar-width:none]" role="tablist">
	{#each tabs as [value, label] (value)}
		<button
			class="shrink-0 rounded-lg px-3 py-1.5 text-sm {tab === value ? 'bg-white text-black' : 'bg-raised hover:bg-line'}"
			role="tab"
			aria-selected={tab === value}
			onclick={() => (tab = value)}>{label}</button
		>
	{/each}
</div>

{#if tab === 'accounts'}
	<AdminAccounts {info} {me} {onToast} />
{:else if tab === 'settings'}
	<AdminSettings {onToast} />
{:else if tab === 'rooms'}
	<AdminRooms {onToast} />
{:else if tab === 'log'}
	<AdminLog {onToast} />
{:else}
	<AdminAbout {onToast} />
{/if}

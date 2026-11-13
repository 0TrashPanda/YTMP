<script lang="ts" module>
	export type Tab = 'accounts' | 'settings' | 'rooms' | 'log' | 'about';
</script>

<script lang="ts">
	// Settings → Server admin (the server's admins only): accounts and invite links, the server's
	// settings, its rooms, and versions (docs/features/accounts/server-admin.md).
	import type { AccountInfo, AuthServerInfo } from '../../protocol.gen';
	import AdminAbout from './AdminAbout.svelte';
	import AdminAccounts from './AdminAccounts.svelte';
	import AdminLog from './AdminLog.svelte';
	import AdminRooms from './AdminRooms.svelte';
	import AdminSettings from './AdminSettings.svelte';

	let {
		info,
		me,
		all = false,
		start = 'accounts',
		onToast
	}: {
		info: AuthServerInfo;
		me: AccountInfo;
		/** The settings search: every tab with settings at once (each in a data-admin-tab). */
		all?: boolean;
		start?: Tab;
		onToast: (text: string) => void;
	} = $props();

	const tabs: [Tab, string][] = [
		['accounts', 'Accounts'],
		['settings', 'Settings'],
		['rooms', 'Rooms'],
		['log', 'Log'],
		['about', 'About']
	];
	// svelte-ignore state_referenced_locally
	let tab = $state<Tab>(start);
</script>

{#if all}
	<div class="flex flex-col gap-6" data-admin-tab="accounts"><AdminAccounts {info} {me} {onToast} /></div>
	<div class="flex flex-col gap-6" data-admin-tab="settings"><AdminSettings {onToast} /></div>
	<div class="flex flex-col gap-6" data-admin-tab="about"><AdminAbout {onToast} /></div>
{:else}
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
{/if}

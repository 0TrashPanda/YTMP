<script lang="ts">
	// Your account on this server: Settings, at Account (logging in or signing up there too,
	// also with an invite link: /account?invite=…). The server admin's tools are in Settings → Server.
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import SettingsScreen from '../../lib/components/settings/SettingsScreen.svelte';

	const invite = page.url.searchParams.get('invite');
	let toast = $state<string | null>(null);
</script>

<svelte:head>
	<title>Settings · YTMP</title>
</svelte:head>

<SettingsScreen
	start="account"
	{invite}
	onClose={() => goto('/')}
	onToast={(text) => {
		toast = text;
		setTimeout(() => toast === text && (toast = null), 3000);
	}}
/>

{#if toast}
	<p class="fixed inset-x-0 bottom-8 z-[80] mx-auto w-fit rounded-lg bg-white px-4 py-2 text-sm text-black shadow-lg">{toast}</p>
{/if}

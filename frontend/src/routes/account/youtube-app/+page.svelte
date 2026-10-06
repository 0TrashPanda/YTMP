<script lang="ts">
	// In the app, on your account's server: hands the YouTube Music sign-in kept in your account
	// to the phone (it asks you first, then signs in and goes back to its own pages). The
	// sign-in only comes with your login here, never to other pages (see YoutubeLinks.kt).
	import { onMount } from 'svelte';
	import { authServer, session } from '../../../lib/account';
	import LoginForm from '../../../lib/components/LoginForm.svelte';
	import { nativeBridge } from '../../../lib/native';
	import type { AuthServerInfo } from '../../../lib/protocol.gen';

	let info = $state<AuthServerInfo | null>(null);
	let loggedIn = $state(false);
	let problem = $state<string | null>(null);
	let waiting = $state(false);

	onMount(async () => {
		if (!nativeBridge?.useYoutubeCookie) {
			problem = 'Open this in the YTMP app.';
			return;
		}
		try {
			info = await authServer.info();
			loggedIn = !!(await authServer.refresh());
		} catch (e) {
			problem = e instanceof Error ? e.message : "Can't reach the server";
			return;
		}
		if (loggedIn) await handOver();
	});

	async function handOver() {
		problem = null;
		try {
			const cookie = await authServer.youtubeCookie();
			waiting = true;
			nativeBridge?.useYoutubeCookie?.(cookie);
		} catch (e) {
			problem = e instanceof Error ? e.message : "Couldn't get your YouTube Music sign-in";
		}
	}

	async function loggedInNow() {
		loggedIn = !!session.current;
		if (loggedIn) await handOver();
	}
</script>

<main class="mx-auto flex min-h-full max-w-md flex-col justify-center gap-6 px-4 py-12">
	<header class="text-center">
		<h1 class="text-5xl font-black tracking-tight">YT<span class="text-accent">MP</span></h1>
		<p class="mt-2 text-muted">YouTube Music on your phone</p>
	</header>

	{#if problem}
		<p class="text-center text-muted">{problem}</p>
		{#if problem.includes('no YouTube Music')}
			<p class="text-center text-sm text-muted">Connect it in the profile menu (your picture) on this server's pages first.</p>
		{/if}
	{:else if info && !loggedIn}
		<p class="text-center text-muted">Log in to use the YouTube Music sign-in of your account here.</p>
		<LoginForm {info} onDone={loggedInNow} />
	{:else if waiting}
		<p class="text-center text-muted">Confirm in the app…</p>
	{:else}
		<p class="text-center text-muted">Loading…</p>
	{/if}

	{#if nativeBridge}
		<button class="text-center text-sm text-muted underline" onclick={() => nativeBridge?.openHome()}>Back to the app</button>
	{/if}
</main>

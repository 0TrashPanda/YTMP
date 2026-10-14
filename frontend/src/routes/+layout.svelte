<script lang="ts">
	import '../app.css';
	import favicon from '../lib/assets/favicon.svg';
	import { connectQuietly, identity } from '../lib/account';
	import { getHost } from '../lib/api';
	import { appLink, webLink } from '../lib/openInApp';

	// Coming back from logging in on an auth server (/connect), or (the app) logged in on your server's pages?
	identity.takeFromUrl();
	identity.takeFromApp();

	// A room link in a phone's browser: the app opens it, if it's installed.
	const toApp = appLink();
	if (toApp) location.href = toApp;

	// A host that doesn't know you yet (your phone, in your laptop's browser): first see whether
	// you're logged in on your server, before the page asks your name.
	// A TV becoming a party screen (/tv) has no account, and doesn't need one.
	const known = !!identity.stored || location.pathname.startsWith('/tv');
	let ready = $state(known);
	if (!known && !toApp) {
		getHost()
			.then(connectQuietly)
			.then((going) => (ready = !going))
			.catch(() => (ready = true));
	}

	let { children } = $props();

	const CONTROLS = 'button, a[href], [role="button"], [role="radio"], [role="switch"], [role="tab"], input[type="checkbox"], input[type="radio"], input[type="range"]';

	/**
	 * A button clicked with the mouse or a finger doesn't stay focused: Space would press it
	 * again instead of playing or pausing. Reached with Tab, it stays focused.
	 */
	function dropMouseFocus(event: MouseEvent) {
		if (event.detail === 0) return; // Enter or Space on a focused button.
		const control = (event.target as Element | null)?.closest?.(CONTROLS);
		if (control instanceof HTMLElement && control === document.activeElement && !control.matches(':focus-visible')) control.blur();
	}
</script>

<svelte:window onclick={dropMouseFocus} />

<svelte:head>
	<link rel="icon" href={favicon} />
	<title>YTMP</title>
</svelte:head>

{#if toApp}
	<!-- Shown in the browser while (or if) the app opens; the browser can't tell whether it did. -->
	<main class="flex h-full flex-col items-center justify-center gap-4 p-6 text-center">
		<h1 class="text-xl font-medium">Opening in the YTMP app…</h1>
		<a href={toApp} class="rounded-full bg-white px-6 py-3 font-medium text-black">Open in the app</a>
		<a href={webLink()} class="text-sm text-muted underline">Continue in the browser</a>
	</main>
{:else if ready}
	{@render children()}
{/if}

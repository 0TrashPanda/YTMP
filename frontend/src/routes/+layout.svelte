<script lang="ts">
	import '../app.css';
	import favicon from '../lib/assets/favicon.svg';
	import { connectQuietly, identity } from '../lib/account';
	import { getHost } from '../lib/api';
	import { appUpdate, reloadOnUpdate } from '../lib/appUpdate.svelte';
	import { dev } from '$app/env';
	import { nativeBridge } from '../lib/native';
	import { checkOwnModule } from '../lib/ownModule.svelte';
	import { loadComputer } from '../lib/computer.svelte';
	import { appLink, webLink } from '../lib/openInApp';
	import { settings } from '../lib/settings.svelte';

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

	// Installable and quick to open (src/service-worker.ts): in a browser on https, not in the app's own
	// WebView, and not on a TV's party screen.
	if (!nativeBridge && !dev && !location.pathname.startsWith('/tv') && isSecureContext && 'serviceWorker' in navigator) {
		navigator.serviceWorker.register('/service-worker.js').catch(() => {});
	}

	// Your own module, if you set one up: does it answer? (Then every minute while it doesn't.)
	if (!nativeBridge && !location.pathname.startsWith('/tv')) void checkOwnModule();
	// The desktop app: its song cache and music (the player plays from them), and the server's requests for a song.
	void loadComputer();

	// The host has a newer version of this page (the server was updated): reload to get it.
	reloadOnUpdate();

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

	// Holding on a touch screen doesn't select text (Settings → Taps and swipes).
	$effect(() => {
		document.documentElement.classList.toggle('hold-no-select', settings.holdNoSelect.value);
	});
	// Reduce motion (Settings → Look): app.css follows the device unless this says always or never.
	$effect(() => {
		const set = settings.reduceMotion.value;
		document.documentElement.classList.toggle('motion-reduce', set === 'on');
		document.documentElement.classList.toggle('motion-full', set === 'off');
	});
	// Finger-sized tap areas on small icon buttons (Settings → Taps and swipes).
	$effect(() => {
		document.documentElement.classList.toggle('big-taps', settings.bigTaps.value);
	});
</script>

<svelte:window onclick={dropMouseFocus} />

<svelte:head>
	<link rel="icon" href={favicon} />
	<title>YTMP</title>
</svelte:head>

{#if toApp}
	<!-- Shown in the browser while (or if) the app opens; the browser can't tell whether it did. -->
	<main class="flex h-full flex-col items-center justify-center gap-4 p-6 text-center">
		<h1 class="text-xl font-medium">Open in the YTMP app?</h1>
		<p class="max-w-xs text-sm text-muted">No app? Continue in the browser: everything works there too.</p>
		<a href={toApp} class="w-full max-w-xs rounded-full bg-white px-6 py-3 font-medium text-black">Open in the app</a>
		<!-- A full page load: this page decides once, when it loads, whether to offer the app. -->
		<a href={webLink()} data-sveltekit-reload class="w-full max-w-xs rounded-full bg-raised px-6 py-3 font-medium hover:bg-line">Continue in the browser</a>
	</main>
{:else if ready}
	{@render children()}
{/if}

{#if appUpdate.waiting && !location.pathname.startsWith('/tv')}
	<!-- It reloads by itself once "Play here" is off, or between two songs when this browser may
	     play on by itself (see appUpdate); or now, with a tap (and a tap to play again).
	     A TV plays on by itself: it reloads between two songs. -->
	<div class="pointer-events-none fixed inset-x-0 top-3 z-50 flex justify-center px-4">
		<button class="pointer-events-auto rounded-lg bg-white px-4 py-2 text-sm text-black shadow-lg" onclick={appUpdate.reload}>
			A new version of YTMP is ready. Tap to reload
		</button>
	</div>
{/if}

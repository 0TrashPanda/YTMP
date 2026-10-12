<script lang="ts">
	import '../app.css';
	import favicon from '../lib/assets/favicon.svg';
	import { connectQuietly, identity } from '../lib/account';
	import { getHost } from '../lib/api';

	// Coming back from logging in on an auth server (/connect), or (the app) logged in on your server's pages?
	identity.takeFromUrl();
	identity.takeFromApp();

	// A host that doesn't know you yet (your phone, in your laptop's browser): first see whether
	// you're logged in on your server, before the page asks your name.
	const known = !!identity.stored;
	let ready = $state(known);
	if (!known) {
		getHost()
			.then(connectQuietly)
			.then((going) => (ready = !going))
			.catch(() => (ready = true));
	}

	let { children } = $props();
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
	<title>YTMP</title>
</svelte:head>

{#if ready}
	{@render children()}
{/if}

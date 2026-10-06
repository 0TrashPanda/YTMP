<script lang="ts">
	import '../app.css';
	import favicon from '../lib/assets/favicon.svg';
	import { identity } from '../lib/account';

	// Coming back from logging in on an auth server (/connect), or (the app) logged in on your server's pages?
	identity.takeFromUrl();
	identity.takeFromApp();

	let { children } = $props();
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
	<title>YTMP</title>
</svelte:head>

{@render children()}

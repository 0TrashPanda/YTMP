<script lang="ts">
	import '../app.css';
	import favicon from '../lib/assets/favicon.svg';
	import { identity } from '../lib/account';

	// Coming back from logging in on an auth server (/connect)?
	identity.takeFromUrl();

	let { children } = $props();
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
	<title>YTMP</title>
</svelte:head>

{@render children()}

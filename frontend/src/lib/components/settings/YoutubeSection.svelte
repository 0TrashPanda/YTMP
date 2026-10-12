<script lang="ts">
	// Settings → YouTube Music: your sign-in (on a phone, the phone's own; on a server, your
	// account's), what it's used for, and signing out (at the bottom, rarely needed).
	import { nativeBridge } from '../../native';
	import type { YoutubeHistory } from '../../protocol.gen';
	import { youtube } from '../../youtube.svelte';
	import Icon from '../Icon.svelte';
	import Choice from './Choice.svelte';

	let { onToast }: { onToast: (text: string) => void } = $props();

	async function signIn() {
		const problem = await youtube.signIn();
		// '': you cancelled.
		if (problem) onToast(problem);
	}

	// On a server: paste the sign-in from a browser.
	let pasting = $state(false);
	let pasted = $state('');
	async function signInWithText() {
		const problem = await youtube.signInWithText(pasted);
		if (problem) onToast(problem);
		else pasting = false;
	}

	async function signOut() {
		if (!confirm('Sign out of YouTube Music here? Your Home, Library and likes go away until you sign in again.')) return;
		try {
			await youtube.signOut();
			onToast('Signed out of YouTube Music');
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't sign out");
		}
	}

	const historyOptions: [YoutubeHistory, string][] = [
		['off', 'Off'],
		['solo', 'Solo rooms'],
		['all', 'All rooms']
	];
</script>

{#if youtube.account}
	{@const account = youtube.account}
	<div class="flex items-center gap-4">
		{#if account.photoUrl}
			<img src={account.photoUrl} alt="" referrerpolicy="no-referrer" class="h-14 w-14 rounded-full" />
		{:else}
			<div class="grid h-14 w-14 place-items-center rounded-full bg-raised"><Icon name="person" /></div>
		{/if}
		<div class="min-w-0">
			<p class="truncate text-lg font-bold">{account.name}</p>
			{#if account.handle}<p class="truncate text-sm text-muted">{account.handle}</p>{/if}
		</div>
	</div>
	<p class="text-sm text-muted">Your Home and Library come from this YouTube Music account. Only you see them, also when friends are in your rooms.</p>
	<Choice
		label="Add to your YouTube Music history"
		hint="Songs you play here show up in your history, and YouTube Music suggests from them."
		options={historyOptions}
		value={youtube.history}
		onchange={(v) => youtube.setHistory(v).catch((e) => onToast(e instanceof Error ? e.message : "Couldn't change it"))}
	/>
	<Choice
		label="Use your account for search and radio"
		hint="Search, suggestions, radio and autoplay fit your taste, like in YouTube Music: your own searches, and the radio in your rooms. Friends' searches never use it."
		options={[
			['on', 'On'],
			['off', 'Off']
		]}
		value={youtube.personalize ? 'on' : 'off'}
		onchange={(v) => youtube.setPersonalize(v === 'on').catch((e) => onToast(e instanceof Error ? e.message : "Couldn't change it"))}
	/>
	<div class="mt-4 border-t border-line pt-4">
		<button class="text-sm text-muted underline hover:text-white" onclick={signOut}>Sign out of YouTube Music</button>
	</div>
{:else if youtube.available && youtube.onServer}
	<p class="text-sm text-muted">
		Connect YouTube Music to your YTMP account on this server: your playlists, liked songs and podcasts, and your own suggestions on Home, in every browser. The
		server keeps the sign-in (encrypted); only you can use it.
	</p>
	{#if youtube.canUseApp}
		{@const phone = youtube.phoneAccount}
		<button class="rounded-full bg-white py-2.5 font-medium text-black disabled:opacity-50" disabled={youtube.busy} onclick={signIn}>
			{youtube.busy ? 'Signing in…' : phone ? `Use ${phone.name} from this phone` : 'Sign in with the YTMP app'}
		</button>
	{/if}
	{#if !pasting}
		<button class="rounded-full bg-raised py-2.5 font-medium hover:bg-line" onclick={() => (pasting = true)}>Paste from a browser</button>
	{:else}
		<ol class="list-decimal space-y-1 pl-5 text-sm text-muted">
			<li>On a computer, open <span class="text-white">music.youtube.com</span>, signed in.</li>
			<li>Open the developer tools (F12) and go to <span class="text-white">Network</span>.</li>
			<li>Click around once, then click a request to <span class="text-white">music.youtube.com</span> (like <span class="text-white">browse</span>).</li>
			<li>Copy its <span class="text-white">Cookie</span> request header, or right-click it → <span class="text-white">Copy as cURL</span>, and paste it here.</li>
		</ol>
		<textarea
			class="h-28 rounded-lg bg-raised p-3 font-mono text-xs outline-none ring-white/40 focus:ring-2"
			placeholder="cookie: …  or  curl 'https://music.youtube.com/…'"
			spellcheck="false"
			bind:value={pasted}
		></textarea>
		<p class="text-xs text-muted">This is your Google session for YouTube: only paste it on a server you trust. Signing out of YouTube in that browser ends it.</p>
		<button class="rounded-full bg-white py-2.5 font-medium text-black disabled:opacity-50" disabled={youtube.busy || !pasted.trim()} onclick={signInWithText}>
			{youtube.busy ? 'Checking…' : 'Connect'}
		</button>
	{/if}
{:else if youtube.available}
	<p class="text-sm text-muted">
		Sign in to see your playlists, liked songs and podcasts, and get your own suggestions on Home. You sign in on Google's own page; YTMP never sees your password, and
		the sign-in stays on this phone.
	</p>
	{#if youtube.linkedServer}
		<button class="rounded-full bg-white py-2.5 font-medium text-black" onclick={() => youtube.useServerSignIn()}>
			Use the one from your account on {new URL(youtube.linkedServer).host}
		</button>
	{/if}
	<button
		class="rounded-full py-2.5 font-medium disabled:opacity-50 {youtube.linkedServer ? 'bg-raised hover:bg-line' : 'bg-white text-black'}"
		disabled={youtube.busy}
		onclick={signIn}
	>
		{youtube.busy ? 'Signing in…' : 'Sign in with YouTube Music'}
	</button>
{:else if youtube.onServer}
	<p class="text-sm text-muted">Log in with your account on this server (Settings → Account) to connect your YouTube Music here.</p>
{:else if nativeBridge}
	<p class="text-sm text-muted">Signing in to YouTube Music works in rooms hosted on this phone, or with your account on a YTMP server.</p>
{:else}
	<p class="text-sm text-muted">
		This host's YouTube Music sign-in is only for its owner. Logged in with their account, you get it here too; or use your account on a YTMP server.
	</p>
{/if}

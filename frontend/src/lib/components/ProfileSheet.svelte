<script lang="ts">
	// The avatar in the header (top right): your YouTube Music sign-in, the room's settings,
	// and this phone's settings in the app.
	import { nativeBridge } from '../native';
	import type { YoutubeHistory } from '../protocol.gen';
	import { saved, type BackLimit, type HeadphonesAction } from '../storage';
	import { youtube } from '../youtube.svelte';
	import Icon from './Icon.svelte';

	let {
		onClose,
		onToast,
		onRoomSettings
	}: {
		onClose: () => void;
		onToast: (text: string) => void;
		/** Opens the room's settings; only when you may change them. */
		onRoomSettings?: () => void;
	} = $props();

	async function signIn() {
		const problem = await youtube.signIn();
		// '': you cancelled.
		if (problem === '') return;
		if (problem) onToast(problem);
		else onClose();
	}

	// On a server: paste the sign-in from a browser.
	let pasting = $state(false);
	let pasted = $state('');
	async function signInWithText() {
		const problem = await youtube.signInWithText(pasted);
		if (problem) onToast(problem);
		else onClose();
	}

	async function signOut() {
		try {
			await youtube.signOut();
			onToast('Signed out of YouTube Music');
			onClose();
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't sign out");
		}
	}

	let soloBack = $state(saved.backLimit(true));
	let partyBack = $state(saved.backLimit(false));
	let headphones = $state(saved.headphonesAction);

	const historyOptions: [YoutubeHistory, string][] = [
		['off', 'Off'],
		['solo', 'Solo rooms'],
		['all', 'All rooms']
	];
	const backOptions: [BackLimit, string][] = [
		['home', 'Home page'],
		['rooms', 'Room list']
	];
	const headphoneOptions: [HeadphonesAction, string][] = [
		['pause', 'Pause'],
		['stop', 'Stop here'],
		['keep', 'Keep playing']
	];
</script>

{#snippet choice(label: string, hint: string, options: [string, string][], value: string, set: (value: string) => void)}
	<div class="flex flex-col gap-2">
		<div>
			<p class="text-sm font-medium">{label}</p>
			<p class="text-xs text-muted">{hint}</p>
		</div>
		<div class="flex rounded-full bg-raised p-1" role="radiogroup" aria-label={label}>
			{#each options as [option, text] (option)}
				<button
					class="flex-1 rounded-full py-1.5 text-sm {value === option ? 'bg-white font-medium text-black' : 'text-muted'}"
					role="radio"
					aria-checked={value === option}
					onclick={() => set(option)}>{text}</button
				>
			{/each}
		</div>
	</div>
{/snippet}

<div class="fixed inset-0 z-50 flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={onClose}>
	<div
		class="flex max-h-[90%] w-full max-w-sm flex-col gap-4 overflow-y-auto rounded-t-2xl bg-surface p-6 ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label="Account and settings"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		onkeydown={(e) => e.key === 'Escape' && onClose()}
	>
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
			<p class="text-sm text-muted">Your Home and Library come from this YouTube Music account. Only you see them, also when friends are in a room on this phone.</p>
			{@render choice(
				'Add to your YouTube Music history',
				'Songs you play here show up in your history, and YouTube Music suggests from them.',
				historyOptions,
				youtube.history,
				(v) => youtube.setHistory(v as YoutubeHistory).catch((e) => onToast(e instanceof Error ? e.message : "Couldn't change it"))
			)}
			<button class="rounded-full bg-raised py-2.5 font-medium hover:bg-line" onclick={signOut}>Sign out of YouTube Music</button>
		{:else if youtube.available && youtube.onServer}
			<h2 class="text-lg font-bold">YouTube Music account</h2>
			<p class="text-sm text-muted">
				Connect YouTube Music to your YTMP account on this server: your playlists, liked songs and podcasts, and your own suggestions on Home, in every browser. The
				server keeps the sign-in (encrypted); only you can use it.
			</p>
			{#if youtube.canUseApp}
				<button class="rounded-full bg-white py-2.5 font-medium text-black disabled:opacity-50" disabled={youtube.busy} onclick={signIn}>
					{youtube.busy ? 'Signing in…' : 'Sign in with the YTMP app'}
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
			<h2 class="text-lg font-bold">YouTube Music account</h2>
			<p class="text-sm text-muted">
				Sign in to see your playlists, liked songs and podcasts, and get your own suggestions on Home. You sign in on Google's own page; YTMP never sees your password,
				and the sign-in stays on this phone.
			</p>
			<button class="rounded-full bg-white py-2.5 font-medium text-black disabled:opacity-50" disabled={youtube.busy} onclick={signIn}>
				{youtube.busy ? 'Signing in…' : 'Sign in with YouTube Music'}
			</button>
		{:else if youtube.onServer}
			<h2 class="text-lg font-bold">YouTube Music account</h2>
			<p class="text-sm text-muted">Log in with your account on this server (on the room list) to connect your YouTube Music here.</p>
		{:else if nativeBridge}
			<h2 class="text-lg font-bold">YouTube Music account</h2>
			<p class="text-sm text-muted">Signing in to YouTube Music works in rooms hosted on this phone, or with your account on a YTMP server.</p>
		{/if}

		{#if onRoomSettings}
			<button class="flex items-center gap-3 rounded-xl bg-raised px-4 py-3 text-left hover:bg-line" onclick={onRoomSettings}>
				<Icon name="settings" size={20} class="text-muted" />
				<span class="flex-1 font-medium">Room settings</span>
			</button>
		{/if}

		{#if nativeBridge}
			<section class="flex flex-col gap-4 border-t border-line pt-4">
				<h2 class="text-sm font-medium tracking-wide text-muted uppercase">On this phone</h2>
				{@render choice('Back in a solo room', "Where the Back button stops once you're on Home.", backOptions, soloBack, (v) => {
					soloBack = v as BackLimit;
					saved.setBackLimit(true, soloBack);
				})}
				{@render choice('Back in a party room', "Where the Back button stops once you're on Home.", backOptions, partyBack, (v) => {
					partyBack = v as BackLimit;
					saved.setBackLimit(false, partyBack);
				})}
				{@render choice(
					'Headphones disconnected',
					"Pause only pauses the room when you're the only one listening; otherwise it stops playing here.",
					headphoneOptions,
					headphones,
					(v) => {
						headphones = v as HeadphonesAction;
						saved.headphonesAction = headphones;
						window.dispatchEvent(new Event('ytmp:settings'));
					}
				)}
			</section>
		{/if}
	</div>
</div>

<script lang="ts">
	// Your YouTube Music sign-in (the avatar in the header): sign in, or who you are and sign out.
	import { youtube } from '../youtube.svelte';
	import Icon from './Icon.svelte';

	let { onClose, onToast }: { onClose: () => void; onToast: (text: string) => void } = $props();

	async function signIn() {
		const problem = await youtube.signIn();
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
</script>

<div class="fixed inset-0 z-50 flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={onClose}>
	<div
		class="flex w-full max-w-sm flex-col gap-4 rounded-t-2xl bg-surface p-6 ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label="YouTube Music account"
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
			<button class="rounded-full bg-raised py-2.5 font-medium hover:bg-line" onclick={signOut}>Sign out</button>
		{:else}
			<h2 class="text-lg font-bold">YouTube Music account</h2>
			<p class="text-sm text-muted">
				Sign in to see your playlists, liked songs and podcasts, and get your own suggestions on Home. You sign in on Google's own page; YTMP never sees your password,
				and the sign-in stays on this phone.
			</p>
			<button class="rounded-full bg-white py-2.5 font-medium text-black disabled:opacity-50" disabled={youtube.busy} onclick={signIn}>
				{youtube.busy ? 'Signing in…' : 'Sign in'}
			</button>
		{/if}
	</div>
</div>

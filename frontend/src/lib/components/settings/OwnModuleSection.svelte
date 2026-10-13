<script lang="ts">
	// Your own YouTube Music module on this computer (see ownModule.ts): where the player gets
	// stream links when the room's links don't work on your internet connection.
	import { testOwnModule } from '../../ownModule';
	import { saved } from '../../storage';

	let { onToast }: { onToast: (text: string) => void } = $props();

	const card = 'flex flex-col gap-3 rounded-xl bg-raised/60 p-4';
	const input = 'rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2';

	const current = saved.ownModule;
	let url = $state(current?.url ?? 'http://localhost:8401');
	let key = $state(current?.key ?? '');
	let on = $state(current !== null);
	let busy = $state(false);
	/** The last check: what answered, or why it didn't. */
	let status = $state<{ ok: boolean; text: string } | null>(null);

	async function save(event: SubmitEvent) {
		event.preventDefault();
		const module = { url: url.trim(), key: key.trim() };
		busy = true;
		try {
			status = { ok: true, text: await testOwnModule(module) };
			saved.ownModule = module;
			on = true;
			onToast('Your module is used when the room’s links don’t work here');
		} catch (e) {
			status = { ok: false, text: (e as Error).message };
		} finally {
			busy = false;
		}
	}

	function remove() {
		saved.ownModule = null;
		on = false;
		status = null;
	}
</script>

<form class={card} onsubmit={save}>
	<div>
		<h3 class="font-bold">Your own YouTube Music module</h3>
		<p class="text-xs text-muted">
			YouTube’s links only work on the internet connection that asked for them. When the room’s links don’t work here, this browser gets them from a
			YouTube Music module on your computer, so the music comes straight from YouTube instead of through the server.
		</p>
	</div>
	<input class={input} bind:value={url} placeholder="http://localhost:8401" autocapitalize="none" spellcheck="false" aria-label="Address" />
	<input class={input} type="password" bind:value={key} placeholder="Module key" autocomplete="off" aria-label="Module key" />
	{#if status}
		<p class="text-sm {status.ok ? 'text-muted' : 'text-accent'}">{status.ok ? `Works: ${status.text}` : `Doesn’t work: ${status.text}`}</p>
	{/if}
	<div class="flex gap-2">
		<button class="flex-1 rounded-full bg-raised py-2 font-medium hover:bg-line disabled:opacity-40" disabled={busy || !url.trim() || !key.trim()}>
			{on ? 'Save and check' : 'Use it'}
		</button>
		{#if on}
			<button type="button" class="rounded-full px-4 py-2 text-sm text-muted hover:bg-raised hover:text-white" onclick={remove}>Stop using it</button>
		{/if}
	</div>
	<p class="text-xs text-muted">
		Start it with Docker: <code class="font-mono">docker run -d -p 127.0.0.1:8401:8401 -e YTMP_MODULE_KEY=<i>a key you choose</i> ghcr.io/0trashpanda/ytmp-ytm</code>.
		Your browser may ask once whether this page may reach devices on your network.
	</p>
</form>

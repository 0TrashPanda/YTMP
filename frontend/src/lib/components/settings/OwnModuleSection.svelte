<script lang="ts">
	// Where this browser plays songs from (see songSources.ts): the order of the three ways, and
	// your own YouTube Music module on this computer (see ownModule.svelte.ts).
	import { checkOwnModule, moduleStatus, testOwnModule } from '../../ownModule.svelte';
	import { SOURCE_LABELS } from '../../songSources';
	import { saved, type SongSource } from '../../storage';
	import Icon from '../Icon.svelte';
	import Switch from './Switch.svelte';

	let { onToast }: { onToast: (text: string) => void } = $props();

	const card = 'flex flex-col gap-3 rounded-xl bg-raised/60 p-4';
	const input = 'rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2';

	const initial = saved.ownModule;
	let module = $state(initial);
	let url = $state(initial?.url ?? 'http://localhost:8401');
	let key = $state(initial?.key ?? '');
	let busy = $state(false);
	/** Why the last "Save and check" failed; the module's state otherwise comes from moduleStatus. */
	let failed = $state<string | null>(null);
	let order = $state(saved.songSources);

	async function save(event: SubmitEvent) {
		event.preventDefault();
		const next = { url: url.trim(), key: key.trim(), on: true };
		busy = true;
		failed = null;
		try {
			await testOwnModule(next);
			saved.ownModule = module = next;
			await checkOwnModule();
			onToast('Your module is used in the order below');
		} catch (e) {
			failed = (e as Error).message;
		} finally {
			busy = false;
		}
	}

	function setOn(on: boolean) {
		if (!module) return;
		saved.ownModule = module = { ...module, on };
		failed = null;
		void checkOwnModule();
	}

	function remove() {
		saved.ownModule = module = null;
		key = '';
		failed = null;
		void checkOwnModule();
		onToast('Your module is removed');
	}

	function move(index: number, by: number) {
		const next = [...order];
		[next[index], next[index + by]] = [next[index + by], next[index]];
		saved.songSources = order = next;
	}

	const statusText = $derived(
		!module
			? null
			: !module.on
				? { ok: true, text: 'Switched off: not used, the address and key are kept.' }
				: moduleStatus.state === 'works'
					? { ok: true, text: `Works: ${moduleStatus.text}` }
					: moduleStatus.state === 'down'
						? { ok: false, text: `Doesn’t answer: ${moduleStatus.text}. Trying again every minute.` }
						: { ok: true, text: 'Checking…' }
	);
	const sourceUnused = (source: SongSource) => source === 'own' && !module?.on;
</script>

<section class={card}>
	<div>
		<h3 class="font-bold">Where songs play from</h3>
		<p class="text-xs text-muted">
			In this browser, songs are tried in this order. When one way doesn’t work for a song, the next one is tried; Through the server always comes last if
			nothing else worked.
		</p>
	</div>
	<ol class="flex flex-col gap-1">
		{#each order as source, i (source)}
			<li class="flex items-center gap-3 rounded-lg bg-raised px-3 py-2 {sourceUnused(source) ? 'opacity-50' : ''}">
				<span class="w-4 text-center text-sm text-muted">{i + 1}</span>
				<div class="min-w-0 flex-1">
					<p class="text-sm font-medium">{SOURCE_LABELS[source].name}</p>
					<p class="text-xs text-muted">{sourceUnused(source) ? 'Not set up or switched off: skipped' : SOURCE_LABELS[source].about}</p>
				</div>
				<button class="rounded-full p-1 hover:bg-line disabled:opacity-30" aria-label="Move {SOURCE_LABELS[source].name} up" disabled={i === 0} onclick={() => move(i, -1)}>
					<Icon name="expand" size={20} />
				</button>
				<button
					class="rounded-full p-1 hover:bg-line disabled:opacity-30"
					aria-label="Move {SOURCE_LABELS[source].name} down"
					disabled={i === order.length - 1}
					onclick={() => move(i, 1)}
				>
					<Icon name="collapse" size={20} />
				</button>
			</li>
		{/each}
	</ol>
</section>

<form class={card} onsubmit={save}>
	<div class="flex items-start gap-3">
		<div class="flex-1">
			<h3 class="font-bold">Your own YouTube Music module</h3>
			<p class="text-xs text-muted">
				YouTube’s links only work on the internet connection that asked for them. A YouTube Music module on your computer gets them from your own connection, so
				the music comes straight from YouTube instead of through the server.
			</p>
		</div>
		{#if module}
			<Switch checked={module.on} label="Use your own module" onchange={setOn} />
		{/if}
	</div>
	<input class={input} bind:value={url} placeholder="http://localhost:8401" autocapitalize="none" spellcheck="false" aria-label="Address" />
	<input class={input} type="password" bind:value={key} placeholder="Module key" autocomplete="off" aria-label="Module key" />
	{#if failed}
		<p class="text-sm text-accent">Doesn’t work: {failed}</p>
	{:else if statusText}
		<p class="text-sm {statusText.ok ? 'text-muted' : 'text-accent'}">{statusText.text}</p>
	{/if}
	<div class="flex gap-2">
		<button class="flex-1 rounded-full bg-raised py-2 font-medium hover:bg-line disabled:opacity-40" disabled={busy || !url.trim() || !key.trim()}>
			{module ? 'Save and check' : 'Use it'}
		</button>
		{#if module}
			<button type="button" class="rounded-full px-4 py-2 text-sm text-muted hover:bg-raised hover:text-white" onclick={remove}>Remove</button>
		{/if}
	</div>
	<p class="text-xs text-muted">
		Start it with Docker: <code class="font-mono">docker run -d -p 127.0.0.1:8401:8401 -e YTMP_MODULE_KEY=<i>a key you choose</i> ghcr.io/0trashpanda/ytmp-ytm</code>.
		Your browser may ask once whether this page may reach devices on your network.
	</p>
</form>

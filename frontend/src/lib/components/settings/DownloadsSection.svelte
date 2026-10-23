<script lang="ts">
	// Settings → Playback → Downloads: the songs this phone is downloading, or keeps waiting
	// (for Wi-Fi, if you asked for that), and the ones that failed.
	import { onDestroy } from 'svelte';
	import { downloadAction, getDownloadQueue } from '../../api';
	import type { DownloadItem } from '../../protocol.gen';

	let { onToast }: { onToast: (text: string) => void } = $props();

	let items = $state<DownloadItem[]>([]);
	let busy = $state(false);
	let timer: ReturnType<typeof setTimeout> | undefined;

	async function refresh() {
		try {
			items = (await getDownloadQueue()).items;
		} catch {
			// Not on this phone's pages: nothing to show.
		}
		clearTimeout(timer);
		timer = setTimeout(refresh, items.length ? 2000 : 8000);
	}
	refresh();
	onDestroy(() => clearTimeout(timer));

	async function act(action: 'cancel' | 'retry' | 'now', item: DownloadItem) {
		busy = true;
		try {
			items = (await downloadAction(action, item.song.id)).items;
		} catch (e) {
			onToast(e instanceof Error ? e.message : 'Something went wrong');
		} finally {
			busy = false;
		}
	}

	const label = (item: DownloadItem) =>
		item.state === 'downloading' ? 'Downloading…' : item.state === 'failed' ? `Failed: ${item.error ?? 'unknown'}` : 'Waiting';
</script>

<section class="flex flex-col gap-3">
		<div>
			<p class="text-sm font-medium">Downloads</p>
			<p class="text-xs text-muted">A song waits here when downloads are set to wait for Wi-Fi (under Song cache). They carry on after the app is closed.</p>
		</div>
		{#if !items.length}
			<p class="text-xs text-muted">Nothing is downloading or waiting.</p>
		{/if}
		<ul class="flex flex-col gap-2">
			{#each items as item (item.song.id)}
				<li class="flex items-center gap-3 rounded-lg bg-raised p-3">
					<div class="min-w-0 flex-1">
						<p class="truncate text-sm">{item.song.title}</p>
						<p class="truncate text-xs text-muted">{item.song.artists.map((a) => a.name).join(', ')} · {label(item)}</p>
					</div>
					{#if item.state === 'waiting'}
						<button class="text-xs font-medium hover:text-white disabled:opacity-40" disabled={busy} onclick={() => act('now', item)}>Download now</button>
					{:else if item.state === 'failed'}
						<button class="text-xs font-medium hover:text-white disabled:opacity-40" disabled={busy} onclick={() => act('retry', item)}>Retry</button>
					{/if}
					<button class="text-xs font-medium text-muted hover:text-white disabled:opacity-40" disabled={busy} onclick={() => act('cancel', item)}>{item.state === 'failed' ? 'Remove' : 'Cancel'}</button>
				</li>
			{/each}
		</ul>
</section>

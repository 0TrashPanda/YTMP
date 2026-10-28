<script lang="ts">
	// Learning YouTube Music's radios of the songs here, slowly, for the song graph: on the phone
	// (its music and saved songs, kept on your server) and on the server's admin page (its local
	// music and song cache). See docs/features/history-and-recommendations.md#learning-from-youtube-music.
	import { onDestroy, onMount } from 'svelte';
	import type { RadioCrawlStatus } from '../../protocol.gen';
	import Choice from './Choice.svelte';
	import Toggle from './Toggle.svelte';

	let {
		load,
		save,
		hint,
		historyHint,
		onToast,
		card = false
	}: {
		load: () => Promise<RadioCrawlStatus>;
		save: (s: RadioCrawlStatus) => Promise<RadioCrawlStatus>;
		hint: string;
		/** Whose history *Also songs played in the last* means here. */
		historyHint: string;
		onToast: (text: string) => void;
		card?: boolean;
	} = $props();

	let crawl = $state<RadioCrawlStatus | null>(null);
	let timer: ReturnType<typeof setInterval> | null = null;
	onMount(() => {
		const read = () =>
			load()
				.then((c) => (crawl = c))
				.catch(() => {});
		void read();
		timer = setInterval(read, 15_000);
	});
	onDestroy(() => timer && clearInterval(timer));

	async function change(next: Partial<RadioCrawlStatus>) {
		if (!crawl) return;
		try {
			crawl = await save({ ...crawl, ...next });
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't change it");
		}
	}

	const until = (ms: number) => new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
</script>

{#if crawl}
	<div class="flex flex-col gap-3 {card ? '' : 'rounded-xl bg-raised/50 p-4'}">
		<h3 class="font-bold">Learn radios</h3>
		<p class="text-xs text-muted">{hint}</p>
		<Toggle label="Learn radios" checked={crawl.enabled} onchange={(on) => change({ enabled: on })} />
		{#if crawl.overnight != null}
			<Toggle
				label="Also overnight"
				hint="In the background while the phone charges on Wi-Fi. Otherwise it only learns while the app is open or playing."
				checked={crawl.overnight}
				disabled={!crawl.enabled}
				onchange={(on) => change({ overnight: on })}
			/>
		{/if}
		<Choice
			label="Also songs played in the last"
			hint={historyHint}
			value={crawl.historyDays}
			onchange={(v) => change({ historyDays: v })}
			options={[
				[0, 'Off'],
				[90, '3 months'],
				[365, '1 year'],
				[1095, '3 years'],
				[3650, 'All of it']
			]}
		/>
		{#if crawl.likes != null}
			<Choice
				label="Also your liked songs"
				hint="The newest songs you liked on YouTube Music, on this phone (looked up twice a day)."
				value={crawl.likes ? (crawl.likesCount ?? 1000) : 0}
				onchange={(v) => change(v === 0 ? { likes: false } : { likes: true, likesCount: v })}
				options={[
					[0, 'Off'],
					[250, '250'],
					[1000, '1,000'],
					[5000, '5,000']
				]}
			/>
		{/if}
		{#if crawl.playing != null}
			<Toggle
				label="First the songs you play"
				hint="A song that played here is learned next, once it ends, if its radio is older than Again after. Not a song you skipped in the first 30 seconds."
				checked={crawl.playing}
				onchange={(on) => change({ playing: on })}
			/>
		{/if}
		<Choice
			label="One radio every"
			hint="Faster than this, YouTube soon asks whether it's a bot, and stops answering this network for hours."
			value={crawl.paceSeconds}
			onchange={(v) => change({ paceSeconds: v })}
			options={[
				[60, '1 min'],
				[90, '1½ min'],
				[300, '5 min']
			]}
		/>
		<Choice
			label="Again after"
			hint="YouTube Music's radios change: each song's is fetched again after this."
			value={crawl.refreshDays}
			onchange={(v) => change({ refreshDays: v })}
			options={[
				[30, '1 month'],
				[90, '3 months'],
				[365, '1 year']
			]}
		/>
		<p class="text-sm">
			{crawl.done.toLocaleString()} of {crawl.total.toLocaleString()} songs learned
			{#if crawl.waiting}<span class="text-muted"> · {crawl.waiting}{crawl.until ? ` (until ${until(crawl.until)})` : ''}</span>{/if}
		</p>
	</div>
{/if}

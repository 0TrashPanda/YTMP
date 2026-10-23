<script lang="ts">
	// Settings → Playback → Song cache → Songs from Google Takeout: your whole YouTube Music
	// history from a Takeout export, and which of it to keep (how recent, how often played),
	// with how many songs that is while you choose.
	import { untrack } from 'svelte';
	import { forgetTakeout, importTakeout, previewTakeout } from '../../api';
	import type { SongCacheSettings, SongCacheStatus, TakeoutPreview } from '../../protocol.gen';
	import NumberField from '../NumberField.svelte';
	import Choice from './Choice.svelte';
	import Switch from './Switch.svelte';

	let {
		status,
		busy,
		patch,
		attempt,
		size
	}: {
		status: SongCacheStatus;
		busy: boolean;
		patch: (changes: Partial<SongCacheSettings>) => Promise<SongCacheStatus>;
		attempt: (action: () => Promise<SongCacheStatus>, done?: string) => Promise<void>;
		/** 1536 MB -> "1.5 GB". */
		size: (bytes: number) => string;
	} = $props();

	let reading = $state(false);
	let input = $state<HTMLInputElement>();
	// What's chosen right now: the count follows it at once, the setting is saved meanwhile.
	let days = $state(untrack(() => status.takeoutDays));
	let minPlays = $state(untrack(() => status.takeoutMinPlays));
	let preview = $state<TakeoutPreview | null>(null);
	let asked = 0;

	const dayOptions: [string, string][] = [
		['30', 'Month'],
		['90', '3 months'],
		['365', 'Year'],
		['0', 'All']
	];
	const playOptions: [string, string][] = [
		['2', '2×'],
		['3', '3×'],
		['5', '5×'],
		['10', '10×'],
		['20', '20×']
	];

	async function count() {
		if (!status.takeout) return;
		const mine = ++asked;
		try {
			const answer = await previewTakeout(days, minPlays);
			if (mine === asked) preview = answer;
		} catch {
			// The count stays as it was; the next change asks again.
		}
	}
	$effect(() => {
		// Again when a new export comes in.
		void status.takeout?.plays;
		count();
	});

	function choose(changes: { days?: number; minPlays?: number }) {
		if (changes.days != null) days = changes.days;
		if (changes.minPlays != null) minPlays = changes.minPlays;
		count();
		attempt(() => patch({ takeoutDays: days, takeoutMinPlays: minPlays }));
	}

	async function pick(event: Event) {
		const file = (event.currentTarget as HTMLInputElement).files?.[0];
		(event.currentTarget as HTMLInputElement).value = '';
		if (!file) return;
		reading = true;
		await attempt(() => importTakeout(file), 'Takeout history read');
		reading = false;
	}

	const month = (ms: number) => new Date(ms).toLocaleDateString(undefined, { month: 'short', year: 'numeric' });
	const number = (n: number) => n.toLocaleString();
	const tooBig = $derived(!!preview && status.maxBytes > 0 && preview.bytes > status.maxBytes * 0.9);
</script>

<div class="flex flex-col gap-3">
	<div class="flex items-center gap-4">
		<div class="min-w-0 flex-1">
			<p class="text-sm font-medium">Songs from Google Takeout</p>
			<p class="text-xs text-muted">
				Your whole YouTube Music history, years back, so you can keep the songs you play most. Get it at takeout.google.com (only <em>YouTube and YouTube Music</em>, with its history) and choose the zip here. It stays on this phone.
			</p>
		</div>
		{#if status.takeout}
			<Switch checked={status.fromTakeout} label="Keep songs from Takeout" disabled={busy} onchange={(on) => attempt(() => patch({ fromTakeout: on, takeoutDays: days, takeoutMinPlays: minPlays }))} />
		{/if}
	</div>
	<input bind:this={input} class="hidden" type="file" accept=".zip,.html,.json,application/zip,text/html,application/json" onchange={pick} />
	{#if status.takeout}
		<p class="text-xs text-muted">
			{number(status.takeout.songs)} songs, {number(status.takeout.plays)} plays, {month(status.takeout.first)} to {month(status.takeout.last)}
		</p>
		<Choice label="Played in the last" options={dayOptions} value={String(days)} onchange={(v) => choose({ days: Number(v) })}>
			<NumberField value={days} min={0} max={3650} step={1} unit="days" label="Days back (0: all of it)" onchange={(v) => choose({ days: Math.round(v) })} />
		</Choice>
		<Choice label="At least" options={playOptions} value={String(minPlays)} onchange={(v) => choose({ minPlays: Number(v) })}>
			<NumberField value={minPlays} min={1} max={1000} step={1} unit="plays" label="Plays at least" onchange={(v) => choose({ minPlays: Math.round(v) })} />
		</Choice>
		{#if preview}
			<div class="rounded-lg bg-raised p-3">
				<p class="text-sm font-medium">{number(preview.songs)} {preview.songs === 1 ? 'song' : 'songs'}, about {size(preview.bytes)}</p>
				<p class="text-xs text-muted">
					{#if preview.cached}{number(preview.cached)} of them are here already.{/if}
					{#if tooBig}
						That's more than the cache's {size(status.maxBytes)}: only what fits is kept, most played first. A bigger size keeps all of them.
					{:else if status.fromTakeout}
						They're downloaded one at a time, most played first{status.takeoutOf ? ` (${number(status.takeoutCached)} of ${number(status.takeoutOf)} kept)` : ''}.
					{:else}
						Switch it on to keep them.
					{/if}
				</p>
			</div>
		{/if}
	{/if}
	<div class="flex flex-wrap gap-2">
		<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-40" disabled={busy || reading} onclick={() => input?.click()}>
			{reading ? 'Reading…' : status.takeout ? 'Choose another export' : 'Choose Takeout export'}
		</button>
		{#if status.takeout}
			<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-40" disabled={busy || reading} onclick={() => attempt(forgetTakeout, 'Takeout history removed')}>Remove</button>
		{/if}
	</div>
</div>

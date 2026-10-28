<script lang="ts">
	// Settings → Playback → Song cache → Songs from Google Takeout: your whole YouTube Music
	// history from a Takeout export, and which of it to keep: one or more rules (how recent, how
	// often played; a song that fits several is kept once), with how many songs that is while you choose.
	import { untrack } from 'svelte';
	import { forgetTakeout, importTakeout, previewTakeout } from '../../api';
	import type { SongCacheSettings, SongCacheStatus, TakeoutPreview, TakeoutRule } from '../../protocol.gen';
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
	// Why the last export couldn't be read: it stays until the next one is chosen.
	let failed = $state<string | null>(null);
	let input = $state<HTMLInputElement>();
	// What's chosen right now: the count follows it at once, the setting is saved meanwhile.
	let rules = $state<TakeoutRule[]>(
		untrack(() => (status.takeoutRules.length ? status.takeoutRules : [{ days: status.takeoutDays, minPlays: status.takeoutMinPlays }]).map((r) => ({ ...r })))
	);
	const MAX_RULES = 6;
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
			const answer = await previewTakeout(rules);
			if (mine === asked) preview = answer;
		} catch {
			// The count stays as it was; the next change asks again.
		}
	}
	// Again when a new export comes in (not when a saved change brings the same one back).
	const exportPlays = $derived(status.takeout?.plays);
	$effect(() => {
		void exportPlays;
		untrack(count);
	});

	/** The rules to save: each once. */
	const saved = () => rules.filter((r, i) => rules.findIndex((o) => o.days === r.days && o.minPlays === r.minPlays) === i).map((r) => ({ ...r }));
	function changed() {
		count();
		attempt(() => patch({ takeoutRules: saved() }));
	}
	function choose(i: number, changes: Partial<TakeoutRule>) {
		rules[i] = { ...rules[i], ...changes };
		changed();
	}
	function addRule() {
		// Another way in: by default the last 3 months, at least 2 plays (or what isn't there yet).
		const taken = (r: TakeoutRule) => rules.some((o) => o.days === r.days && o.minPlays === r.minPlays);
		const next = [{ days: 90, minPlays: 2 }, { days: 30, minPlays: 2 }, { days: 0, minPlays: 10 }, { days: 365, minPlays: 3 }].find((r) => !taken(r)) ?? { days: 90, minPlays: 3 };
		rules = [...rules, next];
		changed();
	}
	function removeRule(i: number) {
		rules = rules.filter((_, k) => k !== i);
		changed();
	}

	async function pick(event: Event) {
		const file = (event.currentTarget as HTMLInputElement).files?.[0];
		(event.currentTarget as HTMLInputElement).value = '';
		if (!file) return;
		reading = true;
		failed = null;
		await attempt(async () => {
			try {
				return await importTakeout(file);
			} catch (e) {
				failed = e instanceof Error ? e.message : "Couldn't read this export";
				throw e;
			}
		}, 'Takeout history read');
		reading = false;
	}

	const month = (ms: number) => new Date(ms).toLocaleDateString(undefined, { month: 'short', year: 'numeric' });
	/** "21:09", or "Sat 09:30" when it isn't today. */
	function at(ms: number): string {
		const when = new Date(ms);
		const time = when.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
		return when.toDateString() === new Date().toDateString() ? time : `${when.toLocaleDateString([], { weekday: 'short' })} ${time}`;
	}

	/** What it's doing now, and why not when it's paused, in plain words. */
	const progress = $derived.by((): { text: string; paused: boolean } | null => {
		if (!status.fromTakeout) return null;
		const left = status.takeoutOf - status.takeoutCached;
		const next = status.fromYoutubeNextAt ? ` Tries again at ${at(status.fromYoutubeNextAt)}.` : '';
		switch (status.fromYoutubeWaiting) {
			case 'Wi-Fi':
				return { text: 'Paused: waiting for Wi-Fi (Keep songs in the background is set to Wi-Fi only). It goes on by itself on Wi-Fi.', paused: true };
			case 'full':
				return { text: `Paused: the cache is full. A bigger size keeps more.${next}`, paused: true };
			case 'error':
				return { text: `Paused: YouTube didn't answer${status.fromYoutubeError ? ` (${status.fromYoutubeError})` : ''}.${next}`, paused: true };
		}
		if (status.fromYoutubeBusy) return { text: `Downloading, one song every 20 seconds: ${number(status.takeoutCached)} of ${number(status.takeoutOf)} kept.`, paused: false };
		if (left <= 0) return { text: `All ${number(status.takeoutOf)} kept.${status.fromYoutubeNextAt ? ` Looks for new ones at ${at(status.fromYoutubeNextAt)}.` : ''}`, paused: false };
		return { text: `${number(status.takeoutCached)} of ${number(status.takeoutOf)} kept.${status.fromYoutubeNextAt ? ` Goes on at ${at(status.fromYoutubeNextAt)}.` : ''}`, paused: true };
	});
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
			<Switch checked={status.fromTakeout} label="Keep songs from Takeout" disabled={busy} onchange={(on) => attempt(() => patch({ fromTakeout: on, takeoutRules: saved() }))} />
		{/if}
	</div>
	<input bind:this={input} class="hidden" type="file" accept=".zip,.html,.json,application/zip,text/html,application/json" onchange={pick} />
	{#if status.takeout}
		<p class="text-xs text-muted">
			{number(status.takeout.songs)} songs, {number(status.takeout.plays)} plays, {month(status.takeout.first)} to {month(status.takeout.last)}
		</p>
		{#each rules as rule, i (i)}
			<div class="flex flex-col gap-3 {rules.length > 1 ? 'rounded-lg border border-line p-3' : ''}">
				{#if rules.length > 1}
					<div class="flex items-center gap-2">
						<p class="min-w-0 flex-1 text-xs text-muted">{i === 0 ? 'Keep songs' : 'Or'}</p>
						<button class="shrink-0 rounded-full px-2 py-1 text-xs text-muted hover:bg-line hover:text-white" aria-label="Remove this rule" onclick={() => removeRule(i)}>Remove</button>
					</div>
				{/if}
				<Choice label="Played in the last" options={dayOptions} value={String(rule.days)} onchange={(v) => choose(i, { days: Number(v) })}>
					<NumberField value={rule.days} min={0} max={3650} step={1} unit="days" label="Days back (0: all of it)" onchange={(v) => choose(i, { days: Math.round(v) })} />
				</Choice>
				<Choice label="At least" options={playOptions} value={String(rule.minPlays)} onchange={(v) => choose(i, { minPlays: Number(v) })}>
					<NumberField value={rule.minPlays} min={1} max={1000} step={1} unit="plays" label="Plays at least" onchange={(v) => choose(i, { minPlays: Math.round(v) })} />
				</Choice>
			</div>
		{/each}
		{#if rules.length < MAX_RULES}
			<button class="self-start rounded-full bg-raised px-3 py-1.5 text-xs font-medium hover:bg-line" onclick={addRule}>Add another rule</button>
		{/if}
		{#if preview}
			<div class="rounded-lg bg-raised p-3">
				<p class="text-sm font-medium">
					{number(preview.songs)} {preview.songs === 1 ? 'song' : 'songs'}, about {size(preview.bytes)}{rules.length > 1 ? ' (a song that fits more than one rule counts once)' : ''}
				</p>
				<p class="text-xs text-muted">
					{#if preview.cached}{preview.cached === preview.songs ? (preview.songs === 1 ? "It's" : 'All of them are') : `${number(preview.cached)} of them are`} here already.{/if}
					{#if tooBig}
						That's more than the cache's {size(status.maxBytes)}: only what fits is kept, most played first. A bigger size keeps all of them.
					{:else if status.fromTakeout}
						They're downloaded one at a time, most played first.
					{:else}
						Switch it on to keep them.
					{/if}
				</p>
				{#if progress}
					<div class="mt-2 flex items-center gap-3 border-t border-line pt-2">
						<p class="min-w-0 flex-1 text-xs {progress.paused ? 'text-white' : 'text-muted'}" role="status">
							{progress.text}
							{#if status.takeoutGone}
								{number(status.takeoutGone)} {status.takeoutGone === 1 ? "isn't" : "aren't"} on YouTube any more, so {status.takeoutGone === 1 ? "it's" : "they're"} skipped.
							{/if}
						</p>
						{#if !status.fromYoutubeBusy}
							<button
								class="shrink-0 rounded-full bg-surface px-3 py-1.5 text-xs font-medium hover:bg-line disabled:opacity-40"
								disabled={busy}
								onclick={() => attempt(() => patch({ fromTakeout: true, takeoutRules: saved() }))}>Retry now</button
							>
						{/if}
					</div>
				{/if}
			</div>
		{/if}
	{/if}
	{#if failed}
		<p class="text-sm text-red-400" role="alert">{failed}</p>
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

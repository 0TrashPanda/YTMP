<script lang="ts">
	// Settings → Song cache and downloads (the phone): your most played playlists kept offline by
	// themselves, and the playlists kept offline (Keep offline on a playlist's page), with how far
	// along they are.
	import { offline, offlineLine, offlineNote, setKept, watchOffline } from '../../offlinePlaylists.svelte';
	import type { SongCacheSettings, SongCacheStatus } from '../../protocol.gen';
	import { thumbUrl } from '../../images.svelte';
	import Icon from '../Icon.svelte';
	import NumberField from '../NumberField.svelte';
	import Choice from './Choice.svelte';

	let {
		status,
		busy,
		patch,
		attempt,
		onToast
	}: {
		status: SongCacheStatus;
		busy: boolean;
		patch: (changes: Partial<SongCacheSettings>) => Promise<SongCacheStatus>;
		attempt: (action: () => Promise<SongCacheStatus>, done?: string) => Promise<void>;
		onToast: (text: string) => void;
	} = $props();

	$effect(() => watchOffline());

	const counts: [string, string][] = [
		['0', 'Off'],
		['1', '1'],
		['3', '3'],
		['5', '5'],
		['10', '10']
	];
	const most = $derived(status.mostPlayedPlaylists ?? 0);
	const listed = $derived((offline.list?.playlists ?? []).filter((p) => p.kept || p.mostPlayed));
	const setMost = (n: number) => attempt(() => patch({ mostPlayedPlaylists: Math.max(0, Math.min(20, Math.round(n))) }));

	async function stop(id: string, title: string) {
		try {
			onToast(await setKept(id, false, title, []));
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't change it");
		}
	}
</script>

<Choice
	label="Keep your most played playlists"
	hint="The playlists in your library you played most in the last 30 days are kept offline and up to date, as long as they fit in the cache (other songs make room). Liked music isn't one of them: Keep your recent likes is for that."
	options={counts}
	value={String(most)}
	onchange={(v) => setMost(Number(v))}
>
	<NumberField value={most} min={0} max={20} step={1} unit="playlists" label="Number of playlists (0: off)" onchange={setMost} />
</Choice>

<div class="flex flex-col gap-2">
	<div>
		<p class="text-sm font-medium">Playlists kept offline</p>
		<p class="text-xs text-muted">
			Keep offline on a playlist's page keeps its songs on this phone, also songs added later. A playlist whose songs are all here (however they got here) shows in Local → Cached on this
			phone → Playlists, and plays from the phone, also without internet.
		</p>
	</div>
	{#if listed.length}
		<div class="flex flex-col gap-1">
			{#each listed as p (p.id)}
				<div class="flex items-center gap-3 rounded-md py-1">
					{#if thumbUrl(p.thumbnails, 120)}
						<img src={thumbUrl(p.thumbnails, 120)} alt="" class="h-10 w-10 shrink-0 rounded object-cover" loading="lazy" />
					{:else}
						<span class="grid h-10 w-10 shrink-0 place-items-center rounded bg-raised"><Icon name="library" size={20} class="text-muted" /></span>
					{/if}
					<span class="min-w-0 flex-1">
						<span class="block truncate text-sm">{p.title}{p.mostPlayed ? ' · most played' : ''}</span>
						<span class="block text-xs text-muted">{offlineLine(p)}</span>
					</span>
					{#if p.kept}
						<button class="rounded-full p-1.5 text-muted hover:bg-raised hover:text-white disabled:opacity-40" disabled={busy} aria-label="Stop keeping {p.title} offline" onclick={() => stop(p.id, p.title)}>
							<Icon name="close" size={20} />
						</button>
					{/if}
				</div>
			{/each}
		</div>
	{:else}
		<p class="text-xs text-muted">None yet.</p>
	{/if}
	{#if offlineNote(offline.list)}
		<p class="text-xs text-muted">{offlineNote(offline.list)}</p>
	{/if}
</div>

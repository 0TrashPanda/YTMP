<script lang="ts">
	// The song graph (docs/features/history-and-recommendations.md#the-graph-view): around a song
	// (its neighbours and theirs), or your most played songs. Lines are where YTMP learned that
	// two songs go together, coloured by where from; the sliders change how much each part
	// counts, at once, and "Use for my radios" keeps them for your radios.
	import { onMount } from 'svelte';
	import { fade } from 'svelte/transition';
	import { DEFAULT_TUNING, KINDS, kindOf, layout, radioFrom, weight } from '../graph';
	import { listening } from '../listening.svelte';
	import type { EdgeKind, GraphNode, GraphTuning, GraphView, Song } from '../protocol.gen';
	import { artistNames } from '../format';
	import Icon from './Icon.svelte';

	let { seed = null, onClose, onToast }: { seed?: Song | null; onClose: () => void; onToast: (text: string) => void } = $props();

	const SIZE = 600;
	let around = $state<string | null>(null);
	let view = $state<GraphView | null>(null);
	let error = $state<string | null>(null);
	let tuning = $state<GraphTuning>({ ...DEFAULT_TUNING });
	let saved = $state<GraphTuning>({ ...DEFAULT_TUNING });
	let picked = $state<string | null>(null);
	let tab = $state<'graph' | 'list'>('graph');

	async function load(songId: string | null) {
		error = null;
		try {
			const v = await listening.graph(songId);
			view = v;
			tuning = { ...v.tuning };
			saved = { ...v.tuning };
			picked = null;
		} catch (e) {
			error = e instanceof Error ? e.message : "Couldn't load the song graph";
		}
	}
	onMount(async () => {
		around = seed?.id ?? null;
		await listening.load();
		await load(around);
	});

	const placed = $derived(view ? layout(view.nodes, view.edges, view.seed, SIZE) : []);
	const at = $derived(new Map(placed.map((p) => [p.node.id, p])));
	const weighed = $derived(view ? view.edges.map((e) => ({ e, w: weight(e, tuning, view!.now) })).filter((x) => x.w > 0) : []);
	// One line per pair of songs: as thick as all it counts together, coloured by what counts most.
	const lines = $derived.by(() => {
		const pairs = new Map<string, { a: string; b: string; w: number; top: { kind: EdgeKind; w: number } }>();
		for (const { e, w } of weighed) {
			const [a, b] = e.from < e.to ? [e.from, e.to] : [e.to, e.from];
			const key = `${a}|${b}`;
			const pair = pairs.get(key);
			if (!pair) pairs.set(key, { a, b, w, top: { kind: e.kind, w } });
			else {
				pair.w += w;
				if (w > pair.top.w) pair.top = { kind: e.kind, w };
			}
		}
		return [...pairs.values()].sort((x, y) => x.w - y.w);
	});
	const strongest = $derived(Math.max(1e-6, ...lines.map((x) => x.w)));
	const radio = $derived(view?.seed ? radioFrom(view.seed, view.edges, tuning, view.now) : []);
	const nodeById = $derived(new Map((view?.nodes ?? []).map((n) => [n.id, n])));
	const mostPlays = $derived(Math.max(1, ...(view?.nodes ?? []).map((n) => n.plays)));
	// Names next to the seed and the songs its radio would bring first (not on every song).
	// …and only where they don't run into each other.
	const labelled = $derived.by(() => {
		const wanted = [picked, view?.seed, ...radio.slice(0, 6).map((r) => r.id)].filter(Boolean) as string[];
		const kept: { x: number; y: number }[] = [];
		const out = new Set<string>();
		for (const id of wanted) {
			const p = at.get(id);
			if (!p || kept.some((k) => Math.abs(k.x - p.x) < 150 && Math.abs(k.y - p.y) < 22)) continue;
			kept.push(p);
			out.add(id);
		}
		return out;
	});
	const changed = $derived(JSON.stringify(tuning) !== JSON.stringify(saved));
	const pickedNode = $derived(picked ? nodeById.get(picked) : null);
	const pickedEdges = $derived(picked ? weighed.filter(({ e }) => e.from === picked || e.to === picked).sort((a, b) => b.w - a.w).slice(0, 6) : []);

	const radius = (n: GraphNode) => 6 + 8 * Math.sqrt(n.plays / mostPlays);

	async function use() {
		try {
			saved = await listening.setTuning(tuning);
			tuning = { ...saved };
			onToast('Your radios use these now');
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't keep them");
		}
	}

	const sliders: { key: keyof GraphTuning; label: string; min: number; max: number; step: number; unit?: string }[] = [
		{ key: 'personal', label: 'Your listening', min: 0, max: 5, step: 0.1 },
		{ key: 'imported', label: 'Imported from Takeout', min: 0, max: 5, step: 0.1 },
		{ key: 'server', label: 'Everyone on your server', min: 0, max: 5, step: 0.1 },
		{ key: 'ytm', label: 'YouTube Music radios', min: 0, max: 5, step: 0.1 },
		{ key: 'ytmAccount', label: 'Radios with your account', min: 0, max: 5, step: 0.1 },
		{ key: 'radioNear', label: 'Songs close together in a radio', min: 0, max: 5, step: 0.1 },
		{ key: 'oneOff', label: 'Takeout links that happened once, unconfirmed', min: 0, max: 1, step: 0.05 },
		{ key: 'party', label: 'A party play, against one alone', min: 0, max: 2, step: 0.05 },
		{ key: 'skip', label: 'How much a skip takes off', min: 0, max: 5, step: 0.1 },
		{ key: 'reverse', label: 'Songs played before it', min: 0, max: 2, step: 0.05 },
		{ key: 'secondHop', label: 'Neighbours of neighbours', min: 0, max: 1, step: 0.05 },
		{ key: 'halfLifeDays', label: 'Counts half after', min: 0, max: 1095, step: 15, unit: 'days' },
		{ key: 'radioDepth', label: 'How far down a radio counts', min: 1, max: 50, step: 1, unit: 'songs' }
	];
	const shown = (key: keyof GraphTuning, v: number) => (key === 'halfLifeDays' && v === 0 ? 'never' : `${Number.isInteger(v) ? v : v.toFixed(2)}${sliders.find((s) => s.key === key)?.unit ? ` ${sliders.find((s) => s.key === key)!.unit}` : '×'}`);
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && onClose()} />

<div class="fixed inset-0 z-[65] flex flex-col bg-surface" role="dialog" aria-label="Song graph" transition:fade|global={{ duration: 120 }}>
	<header class="flex shrink-0 items-center gap-2 px-2 pt-[max(env(safe-area-inset-top),0.5rem)] pb-2">
		<button class="rounded-full p-2 hover:bg-raised" aria-label="Close" onclick={onClose}><Icon name="back" /></button>
		<div class="min-w-0 flex-1">
			<h2 class="truncate font-bold">Song graph</h2>
			<p class="truncate text-xs text-muted">
				{#if view?.seed}Around {nodeById.get(view.seed)?.song.title ?? 'this song'}{:else}Your most played songs{/if}
			</p>
		</div>
		{#if view?.seed}
			<button class="rounded-full px-3 py-1.5 text-sm text-muted hover:bg-raised hover:text-white" onclick={() => ((around = null), load(null))}>Most played</button>
		{/if}
	</header>

	<div class="flex min-h-0 flex-1 flex-col overflow-y-auto lg:flex-row lg:overflow-hidden">
		<div class="flex min-w-0 flex-col lg:flex-1">
			<div class="flex gap-2 px-4 pb-2" role="tablist">
				<button class="rounded-lg px-3 py-1.5 text-sm {tab === 'graph' ? 'bg-white text-black' : 'bg-raised'}" role="tab" aria-selected={tab === 'graph'} onclick={() => (tab = 'graph')}>Graph</button>
				<button class="rounded-lg px-3 py-1.5 text-sm {tab === 'list' ? 'bg-white text-black' : 'bg-raised'}" role="tab" aria-selected={tab === 'list'} onclick={() => (tab = 'list')}>
					{view?.seed ? 'Its radio' : 'Songs'}
				</button>
			</div>
			{#if error}
				<p class="px-4 py-8 text-sm text-muted">{error}</p>
			{:else if !view}
				<p class="px-4 py-8 text-sm text-muted">Loading the song graph…</p>
			{:else if view.nodes.length === 0}
				<p class="px-4 py-8 text-sm text-muted">
					{view.seed === null && around ? "YTMP hasn't learned anything about this song yet." : 'Nothing learned yet: play some music, import your Takeout history, or let it learn radios (Settings → Radio).'}
				</p>
			{:else if tab === 'graph'}
				<div class="relative mx-auto w-full max-w-[min(100%,70vh)] px-2">
					<svg viewBox="0 0 {SIZE} {SIZE}" class="block h-auto w-full touch-manipulation" role="img" aria-label="The songs and how they go together">
						{#each lines as line (line.a + line.b)}
							{@const a = at.get(line.a)}
							{@const b = at.get(line.b)}
							{@const w = line.w}
							{#if a && b}
								{@const k = kindOf(line.top.kind)}
								{@const near = picked && (line.a === picked || line.b === picked)}
								<line
									x1={a.x}
									y1={a.y}
									x2={b.x}
									y2={b.y}
									stroke={k.color}
									stroke-width={0.75 + 3.25 * (w / strongest)}
									stroke-opacity={picked ? (near ? 0.95 : 0.12) : 0.25 + 0.6 * (w / strongest)}
									stroke-dasharray={k.dashed ? '6 4' : undefined}
									stroke-linecap="round"
								/>
							{/if}
						{/each}
						{#each placed as p (p.node.id)}
							{@const isSeed = p.node.id === view.seed}
							<g
								role="button"
								tabindex="0"
								aria-label="{p.node.song.title}, {artistNames(p.node.song)}"
								class="cursor-pointer outline-none"
								onclick={() => (picked = picked === p.node.id ? null : p.node.id)}
								onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && (picked = p.node.id)}
							>
								<!-- A bigger target than the dot. -->
								<circle cx={p.x} cy={p.y} r={Math.max(18, radius(p.node) + 6)} fill="transparent" />
								<circle
									cx={p.x}
									cy={p.y}
									r={isSeed ? radius(p.node) + 4 : radius(p.node)}
									fill={isSeed ? '#ffffff' : picked === p.node.id ? '#e5e5e5' : '#8a8a8a'}
									stroke="var(--color-surface)"
									stroke-width="2"
								/>
							</g>
						{/each}
						<!-- Names last, over every dot. -->
						{#each placed as p (p.node.id)}
							{#if labelled.has(p.node.id)}
								<text x={p.x} y={p.y - radius(p.node) - 8} text-anchor="middle" class="pointer-events-none fill-white text-[13px]" paint-order="stroke" stroke="var(--color-surface)" stroke-width="4">
									{p.node.song.title.length > 28 ? `${p.node.song.title.slice(0, 27)}…` : p.node.song.title}
								</text>
							{/if}
						{/each}
					</svg>
					{#if pickedNode}
						<div class="mx-2 mb-2 rounded-xl bg-raised p-3 text-sm">
							<p class="truncate font-medium">{pickedNode.song.title}</p>
							<p class="truncate text-xs text-muted">{artistNames(pickedNode.song)} · played {pickedNode.plays}× {pickedNode.local ? '· also a file here' : ''}</p>
							{#each pickedEdges as { e, w }, i (i)}
								{@const other = nodeById.get(e.from === picked ? e.to : e.from)}
								<p class="mt-1 flex items-center gap-2 text-xs">
									<span class="inline-block h-0.5 w-4 shrink-0 rounded" style:background={kindOf(e.kind).color}></span>
									<span class="min-w-0 flex-1 truncate">{e.from === picked ? '→' : '←'} {other?.song.title ?? ''}</span>
									<span class="shrink-0 text-muted tabular-nums">{w.toFixed(2)}</span>
								</p>
							{/each}
							<button class="mt-2 rounded-full bg-white px-3 py-1.5 text-xs font-medium text-black" onclick={() => ((around = pickedNode.song.id), load(pickedNode.song.id))}>Show around this song</button>
						</div>
					{/if}
				</div>
				<p class="px-4 pb-1 text-xs text-muted">A line's colour: what counts most for those two songs. Tap a song for all of it.</p>
				<ul class="flex flex-wrap gap-x-4 gap-y-1 px-4 pb-3 text-xs text-muted" aria-label="Legend">
					{#each KINDS as k (k.kind)}
						<li class="flex items-center gap-1.5">
							<svg width="18" height="6" aria-hidden="true"><line x1="1" y1="3" x2="17" y2="3" stroke={k.color} stroke-width="2" stroke-dasharray={k.dashed ? '4 3' : undefined} stroke-linecap="round" /></svg>
							{k.label}
						</li>
					{/each}
					<li>Bigger dot: played more</li>
				</ul>
			{:else}
				<ol class="flex flex-col px-4 pb-4">
					{#each view.seed ? radio.slice(0, 50).map((r) => ({ node: nodeById.get(r.id), score: r.score })) : view.nodes.map((n) => ({ node: n, score: n.plays })) as row, i (i)}
						{#if row.node}
							<li class="flex items-center gap-3 border-b border-line/60 py-2 text-sm">
								<span class="w-6 shrink-0 text-right text-muted tabular-nums">{i + 1}</span>
								<button class="min-w-0 flex-1 text-left" onclick={() => ((around = row.node!.song.id), load(row.node!.song.id))}>
									<p class="truncate">{row.node.song.title}</p>
									<p class="truncate text-xs text-muted">{artistNames(row.node.song)}</p>
								</button>
								<span class="shrink-0 text-xs text-muted tabular-nums">{view.seed ? row.score.toFixed(2) : `${row.score}×`}</span>
							</li>
						{/if}
					{/each}
				</ol>
			{/if}
		</div>

		<aside class="flex shrink-0 flex-col gap-3 border-t border-line px-4 py-4 lg:w-80 lg:overflow-y-auto lg:border-t-0 lg:border-l">
			<div>
				<h3 class="font-bold">How much counts</h3>
				<p class="text-xs text-muted">For your radios, Find similar and Related. Move them to see the graph change; nothing is fetched again.</p>
			</div>
			{#each sliders as s (s.key)}
				<label class="block">
					<span class="flex justify-between text-sm"><span>{s.label}</span><span class="text-muted tabular-nums">{shown(s.key, tuning[s.key])}</span></span>
					<input
						type="range"
						class="w-full accent-white"
						min={s.min}
						max={s.max}
						step={s.step}
						value={tuning[s.key]}
						oninput={(e) => (tuning = { ...tuning, [s.key]: Number(e.currentTarget.value) })}
					/>
				</label>
			{/each}
			<div class="flex flex-wrap gap-2 pb-[env(safe-area-inset-bottom)]">
				<button class="rounded-full bg-white px-4 py-2 text-sm font-medium text-black disabled:opacity-40" disabled={!changed} onclick={use}>Use for my radios</button>
				{#if changed}
					<button class="rounded-full px-4 py-2 text-sm hover:bg-raised" onclick={() => (tuning = { ...saved })}>Undo</button>
				{/if}
				<button class="rounded-full px-4 py-2 text-sm text-muted hover:bg-raised" onclick={() => (tuning = { ...DEFAULT_TUNING })}>Defaults</button>
			</div>
		</aside>
	</div>
</div>

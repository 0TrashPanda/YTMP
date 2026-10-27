<script lang="ts">
	// The song graph (docs/features/history-and-recommendations.md#the-graph-view): around a song
	// (its neighbours and theirs), or your most played songs. Lines are where YTMP learned that
	// two songs go together, coloured by where from; the sliders change how much each part
	// counts, at once, and "Use for my radios" keeps them for your radios.
	import { onMount } from 'svelte';
	import { fade } from 'svelte/transition';
	import { coverUrls, DEFAULT_TUNING, KINDS, kindOf, layout, radioFrom, weight } from '../graph';
	import { thumbUrl } from '../images.svelte';
	import { nativeBridge } from '../native';
	import { listening } from '../listening.svelte';
	import type { EdgeKind, GraphNode, GraphTuning, GraphView, Song } from '../protocol.gen';
	import { artistNames } from '../format';
	import Icon from './Icon.svelte';
	import GraphBuildBar from './GraphBuildBar.svelte';

	let { seed = null, onClose, onToast }: { seed?: Song | null; onClose: () => void; onToast: (text: string) => void } = $props();

	const SIZE = 1000;
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
			cam = { x: 0, y: 0, w: SIZE };
		} catch (e) {
			error = e instanceof Error ? e.message : "Couldn't load the song graph";
		}
	}
	onMount(async () => {
		around = seed?.id ?? null;
		await listening.load();
		await load(around);
	});

	const mostPlays = $derived(Math.max(1, ...(view?.nodes ?? []).map((n) => n.plays)));
	/** A song's size: the seed biggest, the others by how often you played them. */
	const radius = (n: GraphNode) => (n.id === view?.seed ? 38 : 14 + 14 * Math.sqrt(n.plays / mostPlays));
	const placed = $derived(view ? layout(view.nodes, view.edges, view.seed, SIZE, radius) : []);

	// Covers: this host's (a music file's, a saved song's) first, else the song's own.
	let covers = $state<Record<string, string>>({});
	const loads = (url: string) =>
		new Promise<boolean>((done) => {
			const img = new Image();
			img.referrerPolicy = 'no-referrer';
			img.onload = () => done(true);
			img.onerror = () => done(false);
			img.src = url;
		});
	$effect(() => {
		for (const node of view?.nodes ?? []) {
			if (node.id in covers) continue;
			void (async () => {
				for (const url of coverUrls(node, 120, thumbUrl, !!nativeBridge)) {
					if (await loads(url)) return void (covers = { ...covers, [node.id]: url });
				}
			})();
		}
	});

	// Zoom and pan: the part of the graph that shows (a square: x, y, width).
	let cam = $state({ x: 0, y: 0, w: SIZE });
	let svg = $state<SVGSVGElement>();
	let hovered = $state<string | null>(null);
	/** A drag just moved the graph: the click it ends with isn't a tap on a song. */
	let dragged = false;
	const pointers = new Map<number, { x: number; y: number }>();
	let pinch: { d: number; w: number } | null = null;
	const scale = $derived(cam.w / SIZE);

	function toGraph(clientX: number, clientY: number) {
		const rect = svg!.getBoundingClientRect();
		return { x: cam.x + ((clientX - rect.left) / rect.width) * cam.w, y: cam.y + ((clientY - rect.top) / rect.height) * cam.w };
	}
	function zoomAt(clientX: number, clientY: number, w: number) {
		const next = Math.max(SIZE / 8, Math.min(SIZE, w));
		const p = toGraph(clientX, clientY);
		const f = next / cam.w;
		cam = { x: p.x - (p.x - cam.x) * f, y: p.y - (p.y - cam.y) * f, w: next };
	}
	function zoomBy(f: number) {
		const rect = svg!.getBoundingClientRect();
		zoomAt(rect.left + rect.width / 2, rect.top + rect.height / 2, cam.w * f);
	}
	function wheel(e: WheelEvent) {
		e.preventDefault();
		zoomAt(e.clientX, e.clientY, cam.w * Math.exp(e.deltaY * 0.0015));
	}
	function down(e: PointerEvent) {
		pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
		dragged = false;
		if (pointers.size === 2) {
			const [a, b] = [...pointers.values()];
			pinch = { d: Math.hypot(a.x - b.x, a.y - b.y), w: cam.w };
		}
	}
	function move(e: PointerEvent) {
		const last = pointers.get(e.pointerId);
		if (!last) return;
		pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
		if (pointers.size === 2 && pinch) {
			const [a, b] = [...pointers.values()];
			zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, (pinch.w * pinch.d) / Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)));
			dragged = true;
			return;
		}
		const dx = e.clientX - last.x;
		const dy = e.clientY - last.y;
		if (!dragged && Math.hypot(dx, dy) < 3) return;
		if (!dragged) svg?.setPointerCapture(e.pointerId);
		dragged = true;
		const per = cam.w / svg!.getBoundingClientRect().width;
		cam = { ...cam, x: cam.x - dx * per, y: cam.y - dy * per };
	}
	function up(e: PointerEvent) {
		pointers.delete(e.pointerId);
		if (pointers.size < 2) pinch = null;
	}
	function tap(id: string) {
		if (dragged) return;
		picked = picked === id ? null : id;
	}
	const neighbours = $derived.by(() => {
		const set = new Set<string>();
		if (!picked) return set;
		for (const { e } of weighed) {
			if (e.from === picked) set.add(e.to);
			if (e.to === picked) set.add(e.from);
		}
		return set;
	});
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
	// Names next to the seed and the songs its radio would bring first (not on every song).
	// …and only where they don't run into each other.
	const labelled = $derived.by(() => {
		const wanted = [hovered, picked, view?.seed, ...radio.slice(0, 6).map((r) => r.id)].filter(Boolean) as string[];
		const font = 22 * scale;
		type Box = { l: number; r: number; t: number; b: number };
		const boxes: Box[] = [];
		const out = new Map<string, { y: number; title: string }>();
		const hitsSong = (box: Box, own: string) =>
			placed.some((p) => {
				if (p.node.id === own) return false;
				const cx = Math.max(box.l, Math.min(p.x, box.r));
				const cy = Math.max(box.t, Math.min(p.y, box.b));
				return Math.hypot(p.x - cx, p.y - cy) < radius(p.node);
			});
		const hitsName = (box: Box) => boxes.some((o) => box.l < o.r && box.r > o.l && box.t < o.b && box.b > o.t);
		for (const id of new Set(wanted)) {
			const p = at.get(id);
			if (!p) continue;
			const title = p.node.song.title.length > 28 ? `${p.node.song.title.slice(0, 27)}…` : p.node.song.title;
			const half = (title.length * font * 0.55) / 2;
			const r = radius(p.node);
			// Above the song, else below it; a name that fits nowhere is left out (tap the song for it).
			for (const y of [p.y - r - 10 * scale, p.y + r + 10 * scale + font]) {
				const box = { l: p.x - half, r: p.x + half, t: y - font, b: y + 4 * scale };
				if (hitsName(box) || (id !== hovered && id !== picked && id !== view?.seed && hitsSong(box, id))) continue;
				boxes.push(box);
				out.set(id, { y, title });
				break;
			}
		}
		return out;
	});
	const changed = $derived(JSON.stringify(tuning) !== JSON.stringify(saved));
	const pickedNode = $derived(picked ? nodeById.get(picked) : null);
	const pickedEdges = $derived(picked ? weighed.filter(({ e }) => e.from === picked || e.to === picked).sort((a, b) => b.w - a.w).slice(0, 6) : []);


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
		{ key: 'listenNear', label: 'Songs played soon after (2 or 3 later)', min: 0, max: 2, step: 0.05 },
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

	<!-- Being built again: it loads again by itself when that's done. -->
	<div class="shrink-0 px-4 empty:hidden">
		<GraphBuildBar load={() => listening.buildStatus()} onDone={() => load(around)} />
	</div>
	<div class="flex min-h-0 flex-1 flex-col overflow-y-auto lg:flex-row lg:overflow-hidden">
		<div class="flex min-w-0 flex-col lg:min-h-0 lg:flex-1 lg:overflow-y-auto">
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
				<div class="relative mx-auto aspect-square w-full max-w-[min(100%,calc(100dvh-14rem))] px-2 lg:max-w-[min(100%,calc(100dvh-15rem))]">
					<svg
						bind:this={svg}
						viewBox="{cam.x} {cam.y} {cam.w} {cam.w}"
						class="block h-full w-full cursor-grab touch-none rounded-2xl bg-[radial-gradient(circle_at_center,#1c1c1c,transparent_70%)] select-none active:cursor-grabbing"
						role="img"
						aria-label="The songs and how they go together"
						onwheel={wheel}
						onpointerdown={down}
						onpointermove={move}
						onpointerup={up}
						onpointercancel={up}
					>
						<defs>
							{#each placed as p (p.node.id)}
								<clipPath id="gc-{p.node.id}"><circle cx={p.x} cy={p.y} r={radius(p.node)} /></clipPath>
							{/each}
						</defs>
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
									stroke-width={0.75 + 4.25 * (w / strongest) + (near ? 1 : 0)}
									stroke-opacity={picked ? (near ? 0.95 : 0.06) : 0.2 + 0.6 * (w / strongest)}
									stroke-dasharray={k.dashed ? '5 4' : undefined}
									stroke-linecap="round"
									vector-effect="non-scaling-stroke"
									style:transition="stroke-width 200ms, stroke-opacity 200ms"
								/>
							{/if}
						{/each}
						{#each placed as p (p.node.id)}
							{@const isSeed = p.node.id === view.seed}
							{@const r = radius(p.node)}
							{@const dim = !!picked && picked !== p.node.id && !neighbours.has(p.node.id)}
							<g
								role="button"
								tabindex="0"
								aria-label="{p.node.song.title}, {artistNames(p.node.song)}"
								class="cursor-pointer outline-none"
								opacity={dim ? 0.3 : 1}
								style:transition="opacity 200ms"
								onclick={() => tap(p.node.id)}
								onpointerenter={() => (hovered = p.node.id)}
								onpointerleave={() => hovered === p.node.id && (hovered = null)}
								onkeydown={(e) => (e.key === 'Enter' || e.key === ' ') && (picked = p.node.id)}
							>
								<!-- A gap around each song, so lines end before it. -->
								<circle cx={p.x} cy={p.y} r={r + 3} fill="var(--color-surface)" />
								<circle cx={p.x} cy={p.y} {r} fill="#2b2b2b" />
								{#if covers[p.node.id]}
									<image
										href={covers[p.node.id]}
										x={p.x - r}
										y={p.y - r}
										width={r * 2}
										height={r * 2}
										preserveAspectRatio="xMidYMid slice"
										clip-path="url(#gc-{p.node.id})"
									/>
								{:else}
									<text x={p.x} y={p.y + r * 0.3} text-anchor="middle" class="pointer-events-none fill-white/40" font-size={r * 0.9}>♪</text>
								{/if}
								<circle
									cx={p.x}
									cy={p.y}
									{r}
									fill="none"
									stroke={isSeed || picked === p.node.id ? '#ffffff' : hovered === p.node.id ? 'rgba(255,255,255,0.7)' : 'rgba(255,255,255,0.18)'}
									stroke-width={isSeed ? 3 : picked === p.node.id ? 2.5 : 1}
									vector-effect="non-scaling-stroke"
								/>
								<!-- A bigger target than the song on a touch screen. -->
								<circle cx={p.x} cy={p.y} r={Math.max(r, 22 * scale)} fill="transparent" />
							</g>
						{/each}
						<!-- Names last, over every song, the same size on screen at any zoom. -->
						{#each placed as p (p.node.id)}
							{@const label = labelled.get(p.node.id)}
							{#if label}
								{@const title = label.title}
								<text
									x={p.x}
									y={label.y}
									text-anchor="middle"
									class="pointer-events-none fill-white font-medium"
									font-size={22 * scale}
									paint-order="stroke"
									stroke="rgba(15,15,15,0.85)"
									stroke-width={7 * scale}
									stroke-linejoin="round">{title}</text
								>
							{/if}
						{/each}
					</svg>
					<div class="absolute right-4 bottom-2 flex flex-col overflow-hidden rounded-xl bg-raised/90 shadow-lg ring-1 ring-line">
						<button class="px-3 py-2 hover:bg-line" aria-label="Zoom in" onclick={() => zoomBy(0.7)}><Icon name="add" size={18} /></button>
						<button class="px-3 py-2 hover:bg-line" aria-label="Zoom out" onclick={() => zoomBy(1 / 0.7)}><span class="block h-[18px] w-[18px] text-center leading-[18px]">−</span></button>
						<button class="px-3 py-2 text-xs hover:bg-line" aria-label="Show all" onclick={() => (cam = { x: 0, y: 0, w: SIZE })}>All</button>
					</div>
				</div>
				<div class="mx-auto w-full max-w-3xl">
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
				<p class="px-4 pb-1 text-xs text-muted">A line's colour: what counts most for those two songs. Tap a song for all of it.</p>
				<ul class="flex flex-wrap gap-x-4 gap-y-1 px-4 pb-3 text-xs text-muted" aria-label="Legend">
					{#each KINDS as k (k.kind)}
						<li class="flex items-center gap-1.5">
							<svg width="18" height="6" aria-hidden="true"><line x1="1" y1="3" x2="17" y2="3" stroke={k.color} stroke-width="2" stroke-dasharray={k.dashed ? '4 3' : undefined} stroke-linecap="round" /></svg>
							{k.label}
						</li>
					{/each}
					<li>Bigger: played more · scroll or pinch to zoom, drag to move</li>
				</ul>
				</div>
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

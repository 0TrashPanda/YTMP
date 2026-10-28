<script lang="ts">
	// The whole song graph (docs/features/history-and-recommendations.md#the-whole-graph): every
	// song, laid out by the server so songs that go together are close, in groups much like
	// genres. Your listening, everyone else's and YouTube Music's radios, any of them together,
	// for the songs shown and for the links that place and group them, apart (your songs, placed
	// by the radios): which songs show changes here at once; other links ask the server for a map
	// by them, and the songs slide to their new places. Drawn with WebGL (sigma.js, loaded only here).
	import { onDestroy, onMount } from 'svelte';
	import { fade } from 'svelte/transition';
	import type Graph from 'graphology';
	import type Sigma from 'sigma';
	import { listening } from '../listening.svelte';
	import type { GraphMap } from '../protocol.gen';
	import Icon from './Icon.svelte';

	let { onClose, onAround }: { onClose: () => void; onAround: (songId: string) => void } = $props();

	type Part = 'mine' | 'others' | 'radios';
	const PARTS: { part: Part; label: string }[] = [
		{ part: 'mine', label: 'Mine' },
		{ part: 'others', label: 'Everyone else' },
		{ part: 'radios', label: 'YouTube Music radios' }
	];
	type Colour = 'group' | 'artist' | 'plays' | 'first';
	const COLOURS: { colour: Colour; label: string }[] = [
		{ colour: 'group', label: 'Groups' },
		{ colour: 'artist', label: 'Artist' },
		{ colour: 'plays', label: 'Played' },
		{ colour: 'first', label: 'First heard' }
	];
	type Lines = 'none' | 'strong' | 'all';
	const LINES: { lines: Lines; label: string }[] = [
		{ lines: 'none', label: 'No lines' },
		{ lines: 'strong', label: 'Strongest lines' },
		{ lines: 'all', label: 'All lines' }
	];
	/** Categorical colours (validated for the dark surface), then grey for the rest. */
	const CAT = ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#9085e9', '#3fb5c4', '#b8b03d', '#e06666', '#6aa84f', '#a64d79', '#e69138'];
	const REST = '#5a5a5a';
	/** Areas this small stay grey; the legend lists this many areas, each with this many of its groups. */
	const MIN_COLOURED = 10;
	const LEGEND_AREAS = 12;
	const LEGEND_GROUPS = 3;
	/** Lighter (up) and darker (down) steps of an area's colour, for its groups, biggest first. */
	const SHADES = [0, 0.38, -0.32, 0.2, -0.18, 0.55, -0.45, 0.28, -0.25, 0.46, -0.38, 0.12];
	const PLAYS = [0, 1, 2, 5, 10, 25];
	const BG = [15, 15, 15];
	const RAMP = [
		[40, 52, 110],
		[33, 120, 160],
		[40, 170, 130],
		[180, 200, 60],
		[250, 225, 80]
	];
	const ramp = (t: number) => {
		t = Math.max(0, Math.min(1, t)) * (RAMP.length - 1);
		const i = Math.min(RAMP.length - 2, Math.floor(t));
		const f = t - i;
		const c = RAMP[i].map((v, k) => Math.round(v + (RAMP[i + 1][k] - v) * f));
		return `rgb(${c[0]},${c[1]},${c[2]})`;
	};
	const rampCss = `linear-gradient(90deg, ${[0, 0.25, 0.5, 0.75, 1].map(ramp).join(',')})`;
	/** A line: its colour mixed with the background (see-through lines come out pale in WebGL). */
	const dim = (c: string, a: number) => {
		const m = c.startsWith('#') ? [1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16)) : (c.match(/\d+/g) ?? []).map(Number);
		const v = BG.map((b, i) => Math.round(b + ((m[i] ?? 0) - b) * a));
		return `rgb(${v[0]},${v[1]},${v[2]})`;
	};
	/** [c] lighter (towards white, [t] > 0) or darker (towards black). */
	const shade = (c: string, t: number) => {
		const m = [1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16));
		const v = m.map((x) => Math.round(t >= 0 ? x + (255 - x) * t : x * (1 + t)));
		return `rgb(${v[0]},${v[1]},${v[2]})`;
	};
	/** Takeout gives some songs (removed or private ones, mostly) only as their link. */
	const nameless = (title: string) => /^https?:\/\//.test(title) || !title;
	const month = (ms: number) => new Date(ms).toLocaleDateString(undefined, { month: 'short', year: 'numeric' });

	/** Which parts make a song show, which parts' links place and group the songs (and are drawn), and how often you must have played a song. */
	let songParts = $state<Part[]>(['mine', 'others', 'radios']);
	let linkParts = $state<Part[]>(['mine', 'others', 'radios']);
	let minPlays = $state(0);
	/** Songs without any line (with the links chosen) left out: grey dust otherwise. */
	let hideLonely = $state(true);
	let filtersOpen = $state(false);
	/** Anything left out: the Filters button says so. */
	const filtered = $derived(songParts.length < 3 || linkParts.length < 3 || minPlays > 0);
	let colour = $state<Colour>('group');
	let lines = $state<Lines>('strong');
	// Raw: thousands of songs and links, read in loops; a deep proxy of them makes every read slow.
	let map = $state.raw<GraphMap | null>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);
	let picked = $state<number | null>(null);
	let legendOpen = $state(false);
	let box = $state<HTMLDivElement>();

	let graph: Graph | null = null;
	let renderer: Sigma | null = null;
	let hovered: number | null = null;
	let strongCut = 0;
	/** How much each link counts with the parts shown, and which songs have a link then. */
	let weights = new Float64Array(0);
	let songOn = new Uint8Array(0);
	let shown = $state({ songs: 0, links: 0 });
	let groupShown = $state.raw<number[]>([]);
	let areaShown = $state.raw<number[]>([]);

	async function load() {
		loading = true;
		error = null;
		picked = null;
		try {
			const [m] = await Promise.all([listening.graphMap(), libs()]);
			map = m;
			draw();
		} catch (e) {
			error = e instanceof Error ? e.message : "Couldn't load the song graph";
		} finally {
			loading = false;
		}
	}

	let Lib: { Graph: typeof Graph; Sigma: typeof Sigma; animateNodes: typeof import('sigma/utils').animateNodes } | null = null;
	async function libs() {
		if (Lib) return Lib;
		const [g, s, u] = await Promise.all([import('graphology'), import('sigma'), import('sigma/utils')]);
		return (Lib = { Graph: g.default, Sigma: s.default, animateNodes: u.animateNodes });
	}

	/** The map placed and grouped by [linkParts]: asked of the server, then the songs slide there. */
	let placing = $state(false);
	/** Filters or colours being worked out on the page (thousands of songs: a moment on a phone). */
	let filtering = $state(false);
	const busy = $derived(loading || placing || filtering);

	/** Does [work] after the loading bar had a frame to show. */
	async function withBar(work: () => void) {
		filtering = true;
		await new Promise((r) => requestAnimationFrame(() => setTimeout(r, 0)));
		try {
			work();
		} finally {
			filtering = false;
		}
	}
	let placeAsked = 0;
	async function placeBy() {
		const mine = ++placeAsked;
		placing = true;
		try {
			const next = await listening.graphMap(linkParts.length === PARTS.length ? undefined : linkParts);
			if (mine !== placeAsked) return;
			const g = graph;
			if (!g || !map || !Lib || next.nodes.length !== map.nodes.length) {
				map = next;
				draw();
				return;
			}
			map = next;
			paint();
			show();
			Lib.animateNodes(g, Object.fromEntries(next.nodes.map((n, i) => [String(i), { x: n.x, y: -n.y }])), { duration: 700, easing: 'quadraticInOut' });
		} catch (e) {
			if (mine === placeAsked) error = e instanceof Error ? e.message : "Couldn't load the song graph";
		} finally {
			if (mine === placeAsked) placing = false;
		}
	}

	/**
	 * A colour for every area (the coarse groups, about a dozen): biggest first, each the colour its
	 * linked areas (side by side on the map) have least of, so neighbours differ.
	 */
	const areaColours = $derived.by(() => {
		if (!map) return [] as string[];
		const k = map.areas.length;
		const touch = Array.from({ length: k }, () => new Map<number, number>());
		map.from.forEach((a, i) => {
			const ga = map!.nodes[a].area;
			const gb = map!.nodes[map!.to[i]].area;
			if (ga === gb) return;
			const w = map!.mine[i] + map!.others[i] + map!.radios[i];
			touch[ga].set(gb, (touch[ga].get(gb) ?? 0) + w);
			touch[gb].set(ga, (touch[gb].get(ga) ?? 0) + w);
		});
		const chosen = new Array<number>(k).fill(-1);
		for (let g = 0; g < k; g++) {
			if (map.areas[g].songs < MIN_COLOURED || map.areas[g].unlinked) continue;
			const clash = new Array<number>(CAT.length).fill(0);
			for (const [other, w] of touch[g]) if (chosen[other] >= 0) clash[chosen[other]] += w;
			chosen[g] = clash.indexOf(Math.min(...clash));
		}
		return chosen.map((c) => (c < 0 ? REST : CAT[c]));
	});
	/** Each group's area: where most of its songs are. */
	const groupArea = $derived.by(() => {
		if (!map) return [] as number[];
		const counts = map.groups.map(() => new Map<number, number>());
		for (const n of map.nodes) counts[n.group].set(n.area, (counts[n.group].get(n.area) ?? 0) + 1);
		return counts.map((c) => [...c].sort((a, b) => b[1] - a[1])[0]?.[0] ?? 0);
	});
	/** A group: a shade of its area's colour, the biggest group of an area in the colour itself. */
	const groupColours = $derived.by(() => {
		if (!map) return [] as string[];
		const seen = new Map<number, number>();
		return map.groups.map((_, g) => {
			const area = groupArea[g];
			const base = areaColours[area] ?? REST;
			const k = seen.get(area) ?? 0;
			seen.set(area, k + 1);
			return base === REST ? REST : shade(base, SHADES[k % SHADES.length]);
		});
	});
	const artistOf = (i: number) => map!.nodes[i].artist.split(', ')[0];
	const topArtists = $derived.by(() => {
		if (!map) return [] as string[];
		const plays = new Map<string, number>();
		for (const n of map.nodes) {
			const a = n.artist.split(', ')[0];
			if (a && !nameless(n.title)) plays.set(a, (plays.get(a) ?? 0) + 1000 * n.plays + 1);
		}
		return [...plays].sort((a, b) => b[1] - a[1]).slice(0, CAT.length).map(([a]) => a);
	});
	const mostPlays = $derived(Math.max(1, ...(map?.nodes ?? []).map((n) => n.plays)));
	const firsts = $derived((map?.nodes ?? []).map((n) => n.firstAt).filter((t): t is number => t != null));
	const firstLo = $derived(firsts.length ? Math.min(...firsts) : 0);
	const firstHi = $derived(firsts.length ? Math.max(...firsts) : 1);

	function colourOf(i: number): string {
		const n = map!.nodes[i];
		switch (colour) {
			case 'group':
				return groupColours[n.group] ?? REST;
			case 'artist': {
				const k = nameless(n.title) ? -1 : topArtists.indexOf(artistOf(i));
				return k < 0 ? REST : CAT[k];
			}
			case 'plays':
				return n.plays ? ramp(Math.log(n.plays) / Math.log(Math.max(2, mostPlays))) : REST;
			case 'first':
				return n.firstAt != null ? ramp((n.firstAt - firstLo) / Math.max(1, firstHi - firstLo)) : REST;
		}
	}

	/**
	 * A song's dot: bigger when you played it more, and smaller on a small screen (the same map
	 * in less room: at full size the dots would make one blob). Zooming in makes them bigger.
	 */
	const sizeScale = () => Math.max(0.3, Math.min(1, (box?.clientWidth ?? 1100) / 1100));
	const songSize = (plays: number) => (1.5 + 9 * Math.sqrt(plays / mostPlays)) * sizeScale();
	let sizedFor = 0;
	function resized() {
		if (!graph || !map || Math.abs((box?.clientWidth ?? 0) - sizedFor) < 40) return;
		sizedFor = box?.clientWidth ?? 0;
		const nodes = map.nodes;
		graph.updateEachNodeAttributes((key, at) => ({ ...at, size: songSize(nodes[Number(key)].plays) }), { attributes: ['size'] });
		show();
	}

	function draw() {
		if (!map || !Lib || !box) return;
		renderer?.kill();
		renderer = null;
		const g = new Lib.Graph({ type: 'undirected' });
		map.nodes.forEach((n, i) =>
			g.addNode(i, { x: n.x, y: -n.y, size: songSize(n.plays), label: nameless(n.title) ? 'Song without a name' : n.title, color: REST })
		);
		map.from.forEach((a, k) => {
			if (!g.hasEdge(a, map!.to[k])) g.addEdgeWithKey(k, a, map!.to[k], { w: 0, size: 0.2 });
		});
		graph = g;
		sizedFor = box.clientWidth;
		show();
		paint();
		try {
			renderer = new Lib.Sigma(g, box, {
				labelColor: { color: '#ffffff' },
				labelSize: 12,
				labelFont: 'Roboto, system-ui, sans-serif',
				labelRenderedSizeThreshold: 12,
				labelDensity: 0.4,
				labelGridCellSize: 140,
				defaultEdgeColor: 'rgb(45,45,45)',
				defaultDrawNodeHover: drawHover,
				zIndex: true,
				minCameraRatio: 0.02,
				maxCameraRatio: 3,
				nodeReducer: (key, at) => {
					if (at.off) return { ...at, hidden: true };
					const f = picked ?? hovered;
					if (f === null) return at;
					const k = Number(key);
					if (k === f) return { ...at, highlighted: true, zIndex: 2 };
					if (g.areNeighbors(String(f), key)) return { ...at, zIndex: 1, forceLabel: g.degree(String(f)) < 25 };
					return { ...at, color: '#2a2a2a', label: '', zIndex: 0 };
				},
				edgeReducer: (key, at) => {
					if ((at.w as number) <= 0) return { ...at, hidden: true };
					const f = picked ?? hovered;
					if (f !== null) {
						if (g.hasExtremity(key, String(f))) return { ...at, color: 'rgb(220,220,220)', size: (at.size as number) + 0.5, zIndex: 1 };
						return { ...at, hidden: true };
					}
					if (lines === 'none' || (lines === 'strong' && (at.w as number) < strongCut)) return { ...at, hidden: true };
					return at;
				}
			});
		} catch {
			error = "This browser can't draw the whole graph (it needs WebGL)";
			return;
		}
		// Hovering is a mouse's: a finger on a song while it drags the map isn't picking it (that
		// would light it up and draw everything again on every move). A tap picks a song.
		const touch = (e: { original: Event }) => 'touches' in e.original;
		renderer.on('enterNode', ({ node, event }) => {
			if (touch(event)) return;
			hovered = Number(node);
			renderer?.refresh({ skipIndexation: true });
		});
		renderer.on('leaveNode', ({ event }) => {
			if (touch(event) || hovered === null) return;
			hovered = null;
			renderer?.refresh({ skipIndexation: true });
		});
		renderer.on('clickNode', ({ node }) => ((picked = picked === Number(node) ? null : Number(node)), renderer?.refresh({ skipIndexation: true })));
		renderer.on('clickStage', () => ((picked = null), renderer?.refresh({ skipIndexation: true })));
	}

	/**
	 * The filters: songs with a link from [songParts] (and played at least [minPlays] times) show;
	 * lines between them count their links from [linkParts].
	 */
	function show() {
		const g = graph;
		if (!g || !map) return;
		const has = (parts: Part[]) => ({ mine: parts.includes('mine'), others: parts.includes('others'), radios: parts.includes('radios') });
		const song = has(songParts);
		const line = has(linkParts);
		const n = map.from.length;
		weights = new Float64Array(n);
		songOn = new Uint8Array(map.nodes.length);
		for (let k = 0; k < n; k++) {
			if ((song.mine && map.mine[k] > 0) || (song.others && map.others[k] > 0) || (song.radios && map.radios[k] > 0)) {
				songOn[map.from[k]] = 1;
				songOn[map.to[k]] = 1;
			}
		}
		if (minPlays > 0) map.nodes.forEach((node, i) => node.plays < minPlays && (songOn[i] = 0));
		let links = 0;
		const lineCount = new Uint32Array(map.nodes.length);
		for (let k = 0; k < n; k++) {
			if (!songOn[map.from[k]] || !songOn[map.to[k]]) continue;
			const w = (line.mine ? map.mine[k] : 0) + (line.others ? map.others[k] : 0) + (line.radios ? map.radios[k] : 0);
			weights[k] = w;
			if (w > 0) {
				links++;
				lineCount[map.from[k]]++;
				lineCount[map.to[k]]++;
			}
		}
		if (hideLonely) for (let i = 0; i < songOn.length; i++) if (!lineCount[i]) songOn[i] = 0;
		let maxW = 1e-6;
		for (const w of weights) if (w > maxW) maxW = w;
		// The strongest 4,000 lines; fewer on a small screen, where they'd hide the songs.
		strongCut = [...weights].sort((a, b) => b - a)[Math.min(n - 1, Math.round(4000 * sizeScale()))] ?? 0;
		const thin = sizeScale();
		g.updateEachEdgeAttributes((key, at) => ({ ...at, w: weights[Number(key)], size: (0.2 + 1.5 * Math.sqrt(weights[Number(key)] / maxW)) * thin }), { attributes: ['w', 'size'] });
		g.updateEachNodeAttributes((key, at) => ({ ...at, off: !songOn[Number(key)] }), { attributes: ['off'] });
		const counts = new Array(map.groups.length).fill(0);
		const areaCounts = new Array(map.areas.length).fill(0);
		let songs = 0;
		map.nodes.forEach((node, i) => {
			if (!songOn[i]) return;
			songs++;
			counts[node.group]++;
			// By its group's area, as its colour goes.
			areaCounts[groupArea[node.group] ?? node.area]++;
		});
		shown = { songs, links };
		groupShown = counts;
		areaShown = areaCounts;
		if (picked !== null && !songOn[picked]) picked = null;
		renderer?.refresh();
	}

	/** Songs and their lines in the colours asked for. */
	function paint() {
		const g = graph;
		if (!g || !map) return;
		// All at once: one change for sigma, not one per song and line.
		const colours = map.nodes.map((_, i) => colourOf(i));
		const same = new Map<string, string>();
		const line = (c: string) => same.get(c) ?? (same.set(c, dim(c, 0.45)), same.get(c)!);
		const grey = dim('#ffffff', 0.12);
		g.updateEachNodeAttributes((key, at) => ({ ...at, color: colours[Number(key)] }), { attributes: ['color'] });
		g.updateEachEdgeAttributes(
			(_key, at, s, t) => {
				const a = colours[Number(s)];
				return { ...at, color: a === colours[Number(t)] && a !== REST ? line(a) : grey };
			},
			{ attributes: ['color'] }
		);
		renderer?.refresh();
	}

	/** The last pointer was a finger: no name under it (sigma counts a finger on a song as hovering it). */
	let fingered = false;
	/** The name of the song under the pointer: white on a dark box. */
	function drawHover(ctx: CanvasRenderingContext2D, d: { x: number; y: number; size: number; label?: string | null }, st: { labelSize: number; labelFont: string }) {
		if (!d.label || fingered) return;
		const size = st.labelSize;
		const pad = 4;
		ctx.font = `500 ${size}px ${st.labelFont}`;
		const w = ctx.measureText(d.label).width;
		ctx.fillStyle = 'rgba(15,15,15,0.92)';
		ctx.beginPath();
		ctx.roundRect(d.x + d.size + 3, d.y - size / 2 - pad, w + pad * 2, size + pad * 2, 6);
		ctx.fill();
		ctx.beginPath();
		ctx.arc(d.x, d.y, d.size + 2, 0, Math.PI * 2);
		ctx.strokeStyle = '#fff';
		ctx.lineWidth = 2;
		ctx.stroke();
		ctx.fillStyle = '#fff';
		ctx.fillText(d.label, d.x + d.size + 3 + pad, d.y + size / 3);
	}

	/** Turns [part] on or off in [list] (one stays on), in the parts' order. */
	const toggled = (list: Part[], part: Part) => {
		const next = list.includes(part) ? list.filter((p) => p !== part) : PARTS.map((p) => p.part).filter((p) => p === part || list.includes(p));
		return next.length ? next : list;
	};
	function setSongs(part: Part) {
		songParts = toggled(songParts, part);
		void withBar(show);
	}
	function setLinks(part: Part) {
		const next = toggled(linkParts, part);
		if (next === linkParts) return;
		linkParts = next;
		void withBar(show);
		void placeBy();
	}
	function setLonely(hide: boolean) {
		hideLonely = hide;
		void withBar(show);
	}
	const camera = () => renderer?.getCamera();
	function setMinPlays(n: number) {
		minPlays = n;
		void withBar(show);
	}
	function setColour(c: Colour) {
		colour = c;
		void withBar(paint);
	}
	function setLines(l: Lines) {
		lines = l;
		renderer?.refresh({ skipIndexation: true });
	}

	const pickedNode = $derived(map && picked !== null ? map.nodes[picked] : null);
	const pickedNear = $derived.by(() => {
		if (!map || picked === null) return [];
		const near: { i: number; w: number }[] = [];
		void shown;
		map.from.forEach((a, k) => {
			if (weights[k] <= 0) return;
			if (a === picked) near.push({ i: map!.to[k], w: weights[k] });
			else if (map!.to[k] === picked) near.push({ i: a, w: weights[k] });
		});
		return near.sort((a, b) => b.w - a.w).slice(0, 6);
	});
	type Row = { color: string; label: string; n?: number; sub?: boolean };
	const legend = $derived.by((): Row[] => {
		if (!map) return [];
		if (colour === 'group') {
			const rows: Row[] = [];
			const groupsOf = new Map<number, number[]>();
			map.groups.forEach((_, g) => {
				const list = groupsOf.get(groupArea[g]);
				if (list) list.push(g);
				else groupsOf.set(groupArea[g], [g]);
			});
			let rest = 0;
			map.areas.forEach((area, a) => {
				const n = areaShown[a] ?? 0;
				if (!n) return;
				if (area.unlinked) return void rows.push({ color: REST, label: 'Not linked by the links chosen in Filters', n });
				if (a >= LEGEND_AREAS || areaColours[a] === REST) return void (rest += n);
				rows.push({ color: areaColours[a], label: area.artists.join(', ') || 'Songs no one here played', n });
				const inside = (groupsOf.get(a) ?? []).filter((g) => (groupShown[g] ?? 0) > 0);
				if (inside.length > 1)
					for (const g of inside.slice(0, LEGEND_GROUPS))
						rows.push({ color: groupColours[g], label: map!.groups[g].artists.join(', ') || '—', n: groupShown[g], sub: true });
			});
			if (rest) rows.push({ color: REST, label: 'Smaller areas', n: rest });
			return rows;
		}
		if (colour === 'artist') return [...topArtists.map((a, i) => ({ color: CAT[i], label: a })), { color: REST, label: 'Other artists' }];
		return [];
	});

	onMount(load);
	onDestroy(() => renderer?.kill());
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && onClose()} onresize={resized} />

<div class="fixed inset-0 z-[70] flex flex-col bg-surface" role="dialog" aria-label="Whole song graph" transition:fade|global={{ duration: 120 }}>
	<!-- One slim row: the map gets the room. -->
	<header class="flex shrink-0 items-center gap-1.5 px-1.5 pt-[max(env(safe-area-inset-top),0.25rem)] pb-1">
		<button class="shrink-0 rounded-full p-1.5 hover:bg-raised" aria-label="Back" onclick={onClose}><Icon name="back" size={22} /></button>
		<div class="hidden min-w-0 shrink-0 leading-tight sm:block">
			<h2 class="text-sm font-bold">All your music</h2>
			<p class="text-[11px] text-muted">{@render counts()}</p>
		</div>
		<div class="flex min-w-0 flex-1 gap-1.5 overflow-x-auto [scrollbar-width:none] sm:ml-2" role="group" aria-label="Colour by">
			<button
				class="shrink-0 rounded-lg px-2.5 py-1 text-sm {filtersOpen || filtered ? 'bg-white text-black' : 'bg-raised'}"
				aria-expanded={filtersOpen}
				onclick={() => (filtersOpen = !filtersOpen)}>Filters{filtered ? ' ·' : ''}</button
			>
			{#each COLOURS as c (c.colour)}
				<button class="shrink-0 rounded-full px-2.5 py-1 text-xs {colour === c.colour ? 'bg-white/90 text-black' : 'bg-raised text-muted'}" aria-pressed={colour === c.colour} onclick={() => setColour(c.colour)}>{c.label}</button>
			{/each}
		</div>
	</header>
	{#snippet counts()}
		{#if map && !loading}{shown.songs.toLocaleString()} songs · {shown.links.toLocaleString()} links{placing ? ' · placing them…' : ''}{:else}Laying out the graph…{/if}
	{/snippet}

	<div class="relative min-h-0 flex-1">
		{#if busy}
			<div class="pointer-events-none absolute inset-x-0 top-0 z-20 h-[3px] overflow-hidden bg-accent/20" role="progressbar" aria-label="Updating the graph">
				<div class="loading-run h-full w-2/5 bg-accent"></div>
			</div>
		{/if}
		<div
			bind:this={box}
			class="absolute inset-0 touch-none"
			aria-label="The songs and how they go together"
			role="img"
			onpointerdowncapture={(e) => (fingered = e.pointerType !== 'mouse')}
			onpointermovecapture={(e) => e.pointerType === 'mouse' && (fingered = false)}
		></div>
		{#if map && !loading && !error}
			<div class="absolute bottom-[max(env(safe-area-inset-bottom),0.5rem)] left-2 z-10 flex flex-col overflow-hidden rounded-xl bg-raised/90 shadow-lg ring-1 ring-line">
				<button class="px-3 py-2 hover:bg-line" aria-label="Zoom in" onclick={() => camera()?.animatedZoom({ duration: 250 })}><Icon name="add" size={18} /></button>
				<button class="px-3 py-2 hover:bg-line" aria-label="Zoom out" onclick={() => camera()?.animatedUnzoom({ duration: 250 })}><span class="block h-[18px] w-[18px] text-center leading-[18px]">−</span></button>
				<button class="px-3 py-2 text-xs hover:bg-line" aria-label="Recenter" onclick={() => camera()?.animatedReset({ duration: 300 })}>All</button>
			</div>
		{/if}
		{#if error}
			<p class="absolute inset-x-4 top-8 text-center text-sm text-muted">{error}</p>
		{:else if loading}
			<p class="pointer-events-none absolute inset-x-4 top-8 text-center text-sm text-muted">Laying out the graph… The first time, that takes a few seconds.</p>
		{:else if map && shown.songs === 0}
			<p class="absolute inset-x-4 top-8 text-center text-sm text-muted">{map.nodes.length ? 'Nothing learned from this yet.' : 'Nothing learned here yet.'}</p>
		{/if}

		{#if filtersOpen && map}
			<div class="absolute top-2 right-2 z-10 max-h-[calc(100%-1rem)] w-[min(20rem,calc(100%-1rem))] overflow-y-auto rounded-xl bg-raised/95 p-3 text-sm shadow-lg ring-1 ring-line">
				{#snippet chips(label: string, list: Part[], pick: (p: Part) => void)}
					<p class="mb-1 text-xs text-muted">{label}</p>
					<div class="mb-3 flex flex-wrap gap-1.5" role="group" aria-label={label}>
						{#each PARTS as p (p.part)}
							<button class="rounded-lg px-2.5 py-1 text-xs {list.includes(p.part) ? 'bg-white text-black' : 'bg-line'}" aria-pressed={list.includes(p.part)} onclick={() => pick(p.part)}>{p.label}</button>
						{/each}
					</div>
				{/snippet}
				<p class="mb-2 text-xs text-muted sm:hidden">{@render counts()}</p>
				{@render chips('Songs from', songParts, setSongs)}
				{@render chips('Placed and grouped by', linkParts, setLinks)}
				<p class="mb-1 text-xs text-muted">Lines</p>
				<div class="mb-3 flex flex-wrap gap-1.5" role="group" aria-label="Lines">
					{#each LINES as l (l.lines)}
						<button class="rounded-lg px-2.5 py-1 text-xs {lines === l.lines ? 'bg-white text-black' : 'bg-line'}" aria-pressed={lines === l.lines} onclick={() => setLines(l.lines)}>{l.label}</button>
					{/each}
				</div>
				<p class="mb-1 text-xs text-muted">You played it at least</p>
				<div class="flex flex-wrap gap-1.5" role="group" aria-label="You played it at least">
					{#each PLAYS as n (n)}
						<button class="rounded-lg px-2.5 py-1 text-xs {minPlays === n ? 'bg-white text-black' : 'bg-line'}" aria-pressed={minPlays === n} onclick={() => setMinPlays(n)}>{n === 0 ? 'Any' : `${n}×`}</button>
					{/each}
				</div>
				<p class="mt-3 mb-1 text-xs text-muted">Songs without lines</p>
				<div class="flex flex-wrap gap-1.5" role="group" aria-label="Songs without lines">
					<button class="rounded-lg px-2.5 py-1 text-xs {hideLonely ? 'bg-white text-black' : 'bg-line'}" aria-pressed={hideLonely} onclick={() => setLonely(true)}>Hide</button>
					<button class="rounded-lg px-2.5 py-1 text-xs {!hideLonely ? 'bg-white text-black' : 'bg-line'}" aria-pressed={!hideLonely} onclick={() => setLonely(false)}>Show</button>
				</div>
			</div>
		{/if}

		{#if map && !loading && legend.length + (colour === 'plays' || colour === 'first' ? 1 : 0) > 0}
			<div class="absolute top-2 left-2 max-h-[calc(100%-1rem)] w-[min(19rem,calc(100%-1rem))] overflow-y-auto rounded-xl bg-raised/90 text-xs shadow-lg ring-1 ring-line">
				<button class="flex w-full items-center justify-between px-3 py-2 font-medium" aria-expanded={legendOpen} onclick={() => (legendOpen = !legendOpen)}>
					{COLOURS.find((c) => c.colour === colour)?.label}<span class="text-muted">{legendOpen ? 'Hide' : 'Show'}</span>
				</button>
				{#if legendOpen}
					<div class="px-3 pb-3">
						{#if colour === 'plays' || colour === 'first'}
							<div class="h-2.5 rounded-full" style:background={rampCss}></div>
							<div class="mt-1 flex justify-between text-muted">
								{#if colour === 'plays'}<span>once</span><span>{mostPlays}×</span>{:else}<span>{month(firstLo)}</span><span>{month(firstHi)}</span>{/if}
							</div>
						{:else}
							<ul class="grid gap-1">
								{#each legend as row, i (i)}
									<li class="flex items-baseline gap-2 {row.sub ? 'pl-4 text-muted' : ''}">
										<span class="inline-block h-2.5 w-2.5 shrink-0 translate-y-px rounded-full" style:background={row.color}></span>
										<span class="min-w-0 flex-1">{row.label}</span>
										{#if row.n}<span class="text-muted tabular-nums">{row.n.toLocaleString()}</span>{/if}
									</li>
								{/each}
							</ul>
						{/if}
						<p class="mt-2 text-muted">Bigger: you played it more. Groups are songs often heard together, much like genres; radios keep to a song's group. Groups in one area are shades of its colour.{linkParts.length < PARTS.length ? ' Placed and grouped by fewer links (Filters): radios still use all of them.' : ''}</p>
					</div>
				{/if}
			</div>
		{/if}

		{#if pickedNode && map}
			<div class="absolute right-2 bottom-[max(env(safe-area-inset-bottom),0.5rem)] w-[min(22rem,calc(100%-1rem))] rounded-xl bg-raised/95 p-3 text-sm shadow-lg ring-1 ring-line">
				<div class="flex items-start gap-2">
					<p class="min-w-0 flex-1 truncate font-medium">{nameless(pickedNode.title) ? 'Song without a name' : pickedNode.title}</p>
					<button class="-mt-1 -mr-1 shrink-0 rounded-full p-1 text-muted hover:bg-line hover:text-white" aria-label="Close" onclick={() => ((picked = null), renderer?.refresh({ skipIndexation: true }))}><Icon name="close" size={16} /></button>
				</div>
				<p class="truncate text-xs text-muted">
					{nameless(pickedNode.title) ? 'Takeout only has its link' : pickedNode.artist}{pickedNode.plays ? ` · played ${pickedNode.plays}×` : ''}{pickedNode.firstAt ? ` · first ${month(pickedNode.firstAt)}` : ''}
				</p>
				<p class="truncate text-xs text-muted">Group: {map.groups[pickedNode.group]?.artists.join(', ') || '—'}</p>
				{#each pickedNear as { i, w } (i)}
					<p class="mt-1 flex items-center gap-2 text-xs">
						<span class="min-w-0 flex-1 truncate">{nameless(map.nodes[i].title) ? 'Song without a name' : map.nodes[i].title} <span class="text-muted">{map.nodes[i].artist}</span></span>
						<span class="shrink-0 text-muted tabular-nums">{w.toFixed(1)}</span>
					</p>
				{/each}
				<button class="mt-2 rounded-full bg-white px-3 py-1.5 text-xs font-medium text-black" onclick={() => onAround(pickedNode.id)}>Show around this song</button>
			</div>
		{/if}
	</div>
</div>

// The song graph view (docs/features/history-and-recommendations.md#the-graph-view): the server
// sends raw counts, this weighs them with the sliders (the same as SongGraph.weight on the
// server, keep them in step) and lays the songs out.
import type { EdgeKind, GraphEdge, GraphNode, GraphTuning } from './protocol.gen';

/** A YouTube Music radio's first this many songs are kept (SongGraph.MAX_RADIO_SONGS). */
const MAX_RADIO_SONGS = 50;
/** In a radio, each song goes with this many after it (SongGraph.NEAR_WINDOW). */
const NEAR_WINDOW = 3;

export const DEFAULT_TUNING: GraphTuning = {
	personal: 2,
	server: 1,
	imported: 1,
	ytm: 1.5,
	ytmAccount: 0.5,
	radioNear: 0.5,
	listenNear: 0.3,
	oneOff: 0.3,
	party: 0.6,
	skip: 1,
	reverse: 0.5,
	secondHop: 0.35,
	sameGroup: 1,
	halfLifeDays: 180,
	radioDepth: 50
};

/** Where an edge comes from, as the legend says it, and its colour (validated for the dark surface). */
export const KINDS: { kind: EdgeKind; label: string; color: string; dashed?: boolean }[] = [
	{ kind: 'personal', label: 'You, in YTMP', color: '#3987e5' },
	{ kind: 'personal_near', label: 'You, soon after', color: '#3987e5', dashed: true },
	{ kind: 'server', label: 'Everyone on your server', color: '#d95926' },
	{ kind: 'imported', label: 'You, imported from Takeout', color: '#199e70' },
	{ kind: 'imported_near', label: 'Imported, soon after', color: '#199e70', dashed: true },
	{ kind: 'server_imported', label: 'Everyone, imported', color: '#d95926', dashed: true },
	{ kind: 'ytm', label: 'YouTube Music radios', color: '#c98500' },
	{ kind: 'ytm_account', label: 'Radios with your account', color: '#d55181' },
	{ kind: 'radio_near', label: 'Close together in a radio', color: '#9085e9', dashed: true }
];
export const kindOf = (kind: EdgeKind) => KINDS.find((k) => k.kind === kind) ?? KINDS[0];

/** What [e] counts with [t]. [party]: party listening counts (Radio learns from: solo and parties). */
export function weight(e: GraphEdge, t: GraphTuning, now: number, party = true): number {
	const ageDays = Math.max(0, now - e.updatedAt) / 86_400_000;
	const decay = t.halfLifeDays > 0 ? Math.pow(0.5, ageDays / t.halfLifeDays) : 1;
	switch (e.kind) {
		case 'personal':
			return t.personal * (e.solo + e.shared * (party ? t.party : 0) - e.skips * t.skip) * decay;
		case 'imported':
			return t.personal * t.imported * oneOff(e, t) * (e.solo - e.skips * t.skip) * decay;
		case 'server':
			return t.server * (e.solo + e.shared * t.party - e.skips * t.skip) * decay;
		case 'server_imported':
			return t.server * t.imported * oneOff(e, t) * (e.solo - e.skips * t.skip) * decay;
		case 'personal_near':
		case 'imported_near': {
			// 2 songs later counts fully, 3 later half.
			const distance = e.seen > 0 ? e.rankSum / e.seen : 2;
			const heard = e.solo + e.shared * (party ? t.party : 0);
			return t.personal * (e.kind === 'imported_near' ? t.imported : 1) * t.listenNear * (1 - (distance - 2) / 2) * heard * decay;
		}
		case 'radio_near': {
			if ((e.bestRank ?? 0) >= t.radioDepth) return 0;
			const distance = e.seen > 0 ? e.rankSum / e.seen : 1;
			const often = Math.min(2, Math.sqrt(Math.max(1, e.seen)));
			return t.radioNear * (1 - (distance - 1) / NEAR_WINDOW) * often * decay;
		}
		default: {
			if ((e.bestRank ?? 0) >= t.radioDepth) return 0;
			const rank = e.seen > 0 ? e.rankSum / e.seen : (e.bestRank ?? MAX_RADIO_SONGS);
			const often = Math.min(2, Math.sqrt(Math.max(1, e.seen)));
			return (e.kind === 'ytm' ? t.ytm : t.ytmAccount) * Math.max(0.2, 1 - rank / MAX_RADIO_SONGS) * often * decay;
		}
	}
}

/** A Takeout link that happened once and no radio confirms counts [GraphTuning.oneOff] of it. */
const oneOff = (e: GraphEdge, t: GraphTuning) => (e.solo <= 1 && !e.confirmed ? t.oneOff : 1);

/**
 * What a radio from [seed] would bring, best first (GraphRank.rank on the server, here over the
 * songs the view has): a walk along the links, each step to a song in proportion to how much
 * that link counts against the song's other links, each step after the first [secondHop] of the
 * one before; songs in the seed's [group] then count [sameGroup] extra.
 */
export function radioFrom(seed: string, edges: GraphEdge[], t: GraphTuning, now: number, group?: (id: string) => number | null | undefined): { id: string; score: number }[] {
	const out = new Map<string, Map<string, number>>();
	const add = (from: string, to: string, w: number) => {
		const ways = out.get(from) ?? new Map<string, number>();
		ways.set(to, (ways.get(to) ?? 0) + w);
		out.set(from, ways);
	};
	for (const e of edges) {
		if (e.from === e.to) continue;
		const w = weight(e, t, now);
		add(e.from, e.to, w);
		// Back to the song before: songs close together in a radio go together either way round.
		add(e.to, e.from, w * (e.kind === 'radio_near' ? 1 : t.reverse));
	}
	const shares = new Map<string, [string, number][]>();
	for (const [from, ways] of out) {
		const on = [...ways].filter(([, w]) => w > 0);
		const sum = on.reduce((s, [, w]) => s + w, 0);
		shares.set(from, on.map(([to, w]) => [to, w / sum]));
	}
	const onward = Math.max(0, Math.min(0.9, t.secondHop));
	const scores = new Map<string, number>();
	let at = new Map([[seed, 1]]);
	for (let step = 0; step < WALK_STEPS && at.size; step++) {
		const next = new Map<string, number>();
		for (const [node, p] of at) for (const [to, share] of shares.get(node) ?? []) if (to !== seed) next.set(to, (next.get(to) ?? 0) + p * share);
		for (const [node, p] of next) scores.set(node, (scores.get(node) ?? 0) + p);
		if (onward === 0) break;
		at = new Map([...next].map(([k, v]) => [k, v * onward]));
	}
	const home = group?.(seed);
	if (home != null && t.sameGroup) for (const [id, v] of scores) if (group!(id) === home) scores.set(id, v * (1 + t.sameGroup));
	const best = Math.max(0, ...scores.values());
	return [...scores].filter(([, v]) => best > 0 && v >= best * 0.01).sort((a, b) => b[1] - a[1]).map(([id, score]) => ({ id, score }));
}

/** How far the walk goes, in steps (GraphRank.WALK_STEPS). */
const WALK_STEPS = 6;

export interface Placed {
	node: GraphNode;
	x: number;
	y: number;
}

/**
 * A force layout (springs along edges, songs pushing each other away, never overlapping), in
 * a [size] square; [radius] is each song's size. [seed] sits in the middle. The same songs and
 * edges always land in the same places.
 */
export function layout(nodes: GraphNode[], edges: GraphEdge[], seed: string | null, size: number, radius: (node: GraphNode) => number): Placed[] {
	const n = nodes.length;
	const index = new Map(nodes.map((node, i) => [node.id, i]));
	const r = nodes.map(radius);
	// A fixed start (a spiral), so it doesn't jump around on every load.
	const pos = nodes.map((_, i) => {
		const a = i * 2.399963;
		const d = (size / 3) * Math.sqrt((i + 0.5) / n);
		return { x: size / 2 + d * Math.cos(a), y: size / 2 + d * Math.sin(a) };
	});
	const links = edges.map((e) => [index.get(e.from), index.get(e.to)]).filter((l): l is [number, number] => l[0] !== undefined && l[1] !== undefined && l[0] !== l[1]);
	const ideal = size / (1.5 + Math.sqrt(n));
	const s = seed !== null ? index.get(seed) : undefined;
	const steps = 350;
	for (let step = 0; step < steps; step++) {
		const cool = 1 - step / steps;
		const force = pos.map(() => ({ x: 0, y: 0 }));
		for (let i = 0; i < n; i++) {
			for (let j = i + 1; j < n; j++) {
				const dx = pos[i].x - pos[j].x || 0.01;
				const dy = pos[i].y - pos[j].y || 0.01;
				const push = Math.min(1, (ideal * ideal) / (dx * dx + dy * dy)) * 0.03;
				force[i].x += dx * push;
				force[i].y += dy * push;
				force[j].x -= dx * push;
				force[j].y -= dy * push;
			}
		}
		for (const [a, b] of links) {
			const dx = pos[b].x - pos[a].x;
			const dy = pos[b].y - pos[a].y;
			const d = Math.hypot(dx, dy) || 1;
			const pull = ((d - ideal) / d) * 0.05;
			force[a].x += dx * pull;
			force[a].y += dy * pull;
			force[b].x -= dx * pull;
			force[b].y -= dy * pull;
		}
		for (let i = 0; i < n; i++) {
			// A little pull to the middle keeps loose songs in view.
			force[i].x += (size / 2 - pos[i].x) * 0.03;
			force[i].y += (size / 2 - pos[i].y) * 0.03;
			const max = 12 * cool + 0.5;
			pos[i].x += Math.max(-max, Math.min(max, force[i].x));
			pos[i].y += Math.max(-max, Math.min(max, force[i].y));
		}
		// Songs never sit on each other: overlapping ones are pushed apart.
		for (let i = 0; i < n; i++) {
			for (let j = i + 1; j < n; j++) {
				const dx = pos[j].x - pos[i].x || 0.01;
				const dy = pos[j].y - pos[i].y || 0.01;
				const d = Math.hypot(dx, dy);
				const min = r[i] + r[j] + 6;
				if (d >= min) continue;
				const move = (min - d) / 2 / d;
				pos[i].x -= dx * move;
				pos[i].y -= dy * move;
				pos[j].x += dx * move;
				pos[j].y += dy * move;
			}
		}
		for (let i = 0; i < n; i++) {
			pos[i].x = Math.max(r[i] + 4, Math.min(size - r[i] - 4, pos[i].x));
			pos[i].y = Math.max(r[i] + 4, Math.min(size - r[i] - 4, pos[i].y));
		}
		if (s !== undefined) pos[s] = { x: size / 2, y: size / 2 };
	}
	return nodes.map((node, i) => ({ node, ...pos[i] }));
}

/**
 * Where a song's cover can come from, best first: this page's own host (a music file's or a
 * saved song's cover, also without internet), then the song's own pictures.
 */
export function coverUrls(
	node: GraphNode,
	size: number,
	own: (thumbnails: GraphNode['song']['thumbnails'], size: number) => string | undefined,
	/** Ask this host for saved songs' covers too (the app: its song cache plays offline). */
	saved = false
): string[] {
	const here = node.sources
		.filter((id) => id.startsWith('local:') || (saved && id.startsWith('ytm:')))
		.sort((a, b) => Number(b.startsWith('local:')) - Number(a.startsWith('local:')))
		.map((id) => `/api/local/art/${encodeURIComponent(id)}?size=${size}`);
	const web = own(node.song.thumbnails, size);
	return [...here, ...(web ? [web] : [])];
}

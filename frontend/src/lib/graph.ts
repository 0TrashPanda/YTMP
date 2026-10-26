// The song graph view (docs/features/history-and-recommendations.md#the-graph-view): the server
// sends raw counts, this weighs them with the sliders (the same as SongGraph.weight on the
// server, keep them in step) and lays the songs out.
import type { EdgeKind, GraphEdge, GraphNode, GraphTuning } from './protocol.gen';

/** A YouTube Music radio's first this many songs are kept (SongGraph.MAX_RADIO_SONGS). */
const MAX_RADIO_SONGS = 50;

export const DEFAULT_TUNING: GraphTuning = {
	personal: 2,
	server: 1,
	imported: 1,
	ytm: 1.5,
	ytmAccount: 0.5,
	party: 0.6,
	skip: 1,
	reverse: 0.5,
	secondHop: 0.35,
	halfLifeDays: 180,
	radioDepth: 50
};

/** Where an edge comes from, as the legend says it, and its colour (validated for the dark surface). */
export const KINDS: { kind: EdgeKind; label: string; color: string; dashed?: boolean }[] = [
	{ kind: 'personal', label: 'You, in YTMP', color: '#3987e5' },
	{ kind: 'server', label: 'Everyone on your server', color: '#d95926' },
	{ kind: 'imported', label: 'You, imported from Takeout', color: '#199e70' },
	{ kind: 'server_imported', label: 'Everyone, imported', color: '#d95926', dashed: true },
	{ kind: 'ytm', label: 'YouTube Music radios', color: '#c98500' },
	{ kind: 'ytm_account', label: 'Radios with your account', color: '#d55181' }
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
			return t.personal * t.imported * (e.solo - e.skips * t.skip) * decay;
		case 'server':
			return t.server * (e.solo + e.shared * t.party - e.skips * t.skip) * decay;
		case 'server_imported':
			return t.server * t.imported * (e.solo - e.skips * t.skip) * decay;
		default: {
			if ((e.bestRank ?? 0) >= t.radioDepth) return 0;
			const rank = e.seen > 0 ? e.rankSum / e.seen : (e.bestRank ?? MAX_RADIO_SONGS);
			const often = Math.min(2, Math.sqrt(Math.max(1, e.seen)));
			return (e.kind === 'ytm' ? t.ytm : t.ytmAccount) * Math.max(0.2, 1 - rank / MAX_RADIO_SONGS) * often * decay;
		}
	}
}

/** What a radio from [seed] would bring, best first (SongGraph.similar, without the limits). */
export function radioFrom(seed: string, edges: GraphEdge[], t: GraphTuning, now: number): { id: string; score: number }[] {
	const scores = new Map<string, number>();
	const add = (id: string, v: number) => scores.set(id, (scores.get(id) ?? 0) + v);
	for (const e of edges) {
		if (e.from === seed) add(e.to, weight(e, t, now));
		else if (e.to === seed) add(e.from, weight(e, t, now) * t.reverse);
	}
	scores.delete(seed);
	const near = [...scores].filter(([, v]) => v > 0).sort((a, b) => b[1] - a[1]).slice(0, 8);
	const best = near[0]?.[1];
	if (best && t.secondHop > 0) {
		const strength = new Map(near.map(([id, v]) => [id, v / best]));
		for (const e of edges) {
			const s = strength.get(e.from);
			if (s !== undefined && e.to !== seed) add(e.to, s * weight(e, t, now) * t.secondHop);
		}
	}
	return [...scores].filter(([, v]) => v >= 0.05).sort((a, b) => b[1] - a[1]).map(([id, score]) => ({ id, score }));
}

export interface Placed {
	node: GraphNode;
	x: number;
	y: number;
}

/**
 * A force layout (springs along edges, songs pushing each other away), in a [size] square.
 * [seed] sits in the middle. The same songs and edges always land in the same places.
 */
export function layout(nodes: GraphNode[], edges: GraphEdge[], seed: string | null, size: number): Placed[] {
	const n = nodes.length;
	const index = new Map(nodes.map((node, i) => [node.id, i]));
	// A fixed start (a spiral), so it doesn't jump around on every load.
	const pos = nodes.map((_, i) => {
		const a = i * 2.399963;
		const r = (size / 3) * Math.sqrt((i + 0.5) / n);
		return { x: size / 2 + r * Math.cos(a), y: size / 2 + r * Math.sin(a) };
	});
	const links = edges.map((e) => [index.get(e.from), index.get(e.to)]).filter((l): l is [number, number] => l[0] !== undefined && l[1] !== undefined && l[0] !== l[1]);
	const ideal = size / (2 + Math.sqrt(n));
	for (let step = 0; step < 300; step++) {
		const cool = 1 - step / 300;
		const force = pos.map(() => ({ x: 0, y: 0 }));
		for (let i = 0; i < n; i++) {
			for (let j = i + 1; j < n; j++) {
				const dx = pos[i].x - pos[j].x || 0.01;
				const dy = pos[i].y - pos[j].y || 0.01;
				const d2 = dx * dx + dy * dy;
				const push = Math.min(1, (ideal * ideal) / d2) * 0.03;
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
			const max = 10 * cool + 0.5;
			pos[i].x += Math.max(-max, Math.min(max, force[i].x));
			pos[i].y += Math.max(-max, Math.min(max, force[i].y));
			pos[i].x = Math.max(24, Math.min(size - 24, pos[i].x));
			pos[i].y = Math.max(24, Math.min(size - 24, pos[i].y));
		}
		const s = seed !== null ? index.get(seed) : undefined;
		if (s !== undefined) pos[s] = { x: size / 2, y: size / 2 };
	}
	return nodes.map((node, i) => ({ node, ...pos[i] }));
}

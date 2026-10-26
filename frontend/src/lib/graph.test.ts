import { describe, expect, it } from 'vitest';
import { DEFAULT_TUNING, radioFrom, weight } from './graph';
import { parseRadio } from './radio.svelte';
import type { GraphEdge } from './protocol.gen';

const now = 1_000_000_000_000;
const edge = (from: string, to: string, kind: GraphEdge['kind'], more: Partial<GraphEdge> = {}): GraphEdge => ({
	from,
	to,
	kind,
	solo: 0,
	shared: 0,
	skips: 0,
	seen: 0,
	bestRank: null,
	rankSum: 0,
	updatedAt: now,
	confirmed: false,
	...more
});

describe('the graph view weighs edges like the server', () => {
	it('counts your plays, takes skips off, and leaves out radio places past the depth', () => {
		expect(weight(edge('a', 'b', 'personal', { solo: 2, skips: 1 }), DEFAULT_TUNING, now)).toBeCloseTo(2);
		expect(weight(edge('a', 'b', 'ytm', { seen: 1, bestRank: 0, rankSum: 0 }), DEFAULT_TUNING, now)).toBeCloseTo(1.5);
		expect(weight(edge('a', 'b', 'ytm', { seen: 1, bestRank: 10, rankSum: 10 }), { ...DEFAULT_TUNING, radioDepth: 10 }, now)).toBe(0);
		// Half as much after the half-life.
		expect(weight(edge('a', 'b', 'server', { solo: 1, updatedAt: now - 180 * 86_400_000 }), DEFAULT_TUNING, now)).toBeCloseTo(0.5);
	});

	it('lets a one-off Takeout link count less until a radio confirms it, and near links count either way', () => {
		expect(weight(edge('a', 'b', 'imported', { solo: 1 }), DEFAULT_TUNING, now)).toBeCloseTo(0.6);
		expect(weight(edge('a', 'b', 'imported', { solo: 1, confirmed: true }), DEFAULT_TUNING, now)).toBeCloseTo(2);
		expect(weight(edge('a', 'b', 'radio_near', { seen: 1, bestRank: 1, rankSum: 3 }), DEFAULT_TUNING, now)).toBeCloseTo(0.5 / 3);
		expect(radioFrom('b', [edge('a', 'b', 'radio_near', { seen: 1, bestRank: 1, rankSum: 1 })], DEFAULT_TUNING, now).map((r) => r.id)).toEqual(['a']);
	});

	it('counts songs played soon after less, 3 later half of 2 later', () => {
		expect(weight(edge('a', 'c', 'personal_near', { solo: 2, seen: 2, rankSum: 4 }), DEFAULT_TUNING, now)).toBeCloseTo(2 * 0.3 * 2);
		expect(weight(edge('a', 'd', 'personal_near', { solo: 1, seen: 1, rankSum: 3 }), DEFAULT_TUNING, now)).toBeCloseTo(2 * 0.3 * 0.5);
	});

	it("ranks a radio's songs, with the songs before the seed and neighbours' neighbours counting less", () => {
		const edges = [edge('s', 'a', 'personal', { solo: 3 }), edge('b', 's', 'personal', { solo: 3 }), edge('a', 'c', 'personal', { solo: 3 })];
		expect(radioFrom('s', edges, DEFAULT_TUNING, now).map((r) => r.id)).toEqual(['a', 'b', 'c']);
		expect(radioFrom('s', edges, { ...DEFAULT_TUNING, secondHop: 0 }, now).map((r) => r.id)).toEqual(['a', 'b']);
	});
});

describe('radio choices', () => {
	it('keep only what is valid', () => {
		const r = parseRadio({ from: 'graph', discover: 4, perArtist: 'x', blockedArtists: ['A', 3] });
		expect(r.from).toBe('graph');
		expect(r.discover).toBe(1);
		expect(r.perArtist).toBe(0);
		expect(r.blockedArtists).toEqual(['A']);
		expect(parseRadio(null).from).toBe('mix');
	});
});

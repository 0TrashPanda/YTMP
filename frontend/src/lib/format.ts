import { thumbUrl } from './images.svelte';
import type { Song } from './protocol.gen';

export function formatTime(ms: number): string {
	const total = Math.max(0, Math.floor(ms / 1000));
	const minutes = Math.floor(total / 60);
	const seconds = total % 60;
	return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

/** How long ago [ms] was, roughly: "just now", "5 min ago", "3 h ago", "2 days ago", or the date. */
export function timeAgo(ms: number, now: number = Date.now()): string {
	const minutes = Math.floor((now - ms) / 60_000);
	if (minutes < 1) return 'just now';
	if (minutes < 60) return `${minutes} min ago`;
	const hours = Math.floor(minutes / 60);
	if (hours < 24) return `${hours} h ago`;
	const days = Math.floor(hours / 24);
	if (days < 30) return days === 1 ? 'yesterday' : `${days} days ago`;
	return new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

/** The artists, or for a podcast episode the podcast. */
export function artistNames(song: Song): string {
	return song.artists.map((a) => a.name).join(', ') || song.podcast?.name || '';
}

/** A song's picture for showing it [size] px wide (see [thumbUrl]). */
export function artUrl(song: Song, size: number): string | null {
	return thumbUrl(song.thumbnails, size) ?? null;
}

/** Lowercase, without accents: "cafe" finds "Café". */
const plain = (text: string) => text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();

/** Rows for [editDistance], kept between calls (it runs for every word of every item). */
let rows = [new Uint16Array(32), new Uint16Array(32), new Uint16Array(32)];

/** Edits (a letter added, removed, changed, or two swapped) between [a] and [b]. */
function editDistance(a: string, b: string): number {
	if (rows[0].length <= b.length) rows = rows.map(() => new Uint16Array(b.length * 2 + 1));
	let [before, prev, cur] = rows;
	for (let j = 0; j <= b.length; j++) prev[j] = j;
	for (let i = 1; i <= a.length; i++) {
		cur[0] = i;
		for (let j = 1; j <= b.length; j++) {
			const cost = a[i - 1] === b[j - 1] ? 0 : 1;
			let d = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost);
			if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) d = Math.min(d, before[j - 2] + 1);
			cur[j] = d;
		}
		[before, prev, cur] = [prev, cur, before];
	}
	return prev[b.length];
}

/** [word]'s letters in order, starting at the start of a word, not too spread out ("rt" in "road trip"). */
function inOrder(word: string, text: string): boolean {
	for (let start = 0; start < text.length; start++) {
		if (text[start] !== word[0] || (start > 0 && /[\p{L}\p{N}]/u.test(text[start - 1]))) continue;
		let at = start;
		for (const letter of word.slice(1)) {
			at = text.indexOf(letter, at + 1);
			if (at < 0) break;
		}
		if (at >= 0 && at - start < word.length * 4) return true;
	}
	return false;
}

/** How well one typed word fits [text] (higher is better), or null when it doesn't. */
function wordScore(word: string, text: string, words: string[]): number | null {
	if (words.includes(word)) return 100;
	if (words.some((w) => w.startsWith(word))) return 80;
	if (text.includes(word)) return 60;
	// Typos: one mistake per 4 letters, in a whole word or the start of one.
	const allowed = Math.floor(word.length / 4);
	if (allowed > 0) {
		// A part whose length is off by more than the typos allowed can't be close.
		const close = words.some((w) =>
			[w, w.slice(0, word.length - 1), w.slice(0, word.length), w.slice(0, word.length + 1)].some(
				(part) => part.length && Math.abs(part.length - word.length) <= allowed && editDistance(word, part) <= allowed
			)
		);
		if (close) return 40;
	}
	if (word.length >= 2 && inOrder(word, text)) return 20;
	return null;
}

/**
 * Forgiving search for what you type, for finding things in a list (your library): every
 * typed word must fit somewhere, as a word, the start of one, part of one, with a typo or
 * two, or its letters in order ("rt" finds "Road trip"). Capitals and accents don't matter.
 * Returns the matching [items], best first (ties keep their order).
 */
export function fuzzyFind<T>(items: T[], query: string, text: (item: T) => string): T[] {
	return fuzzyIndex(items, text)(query);
}

/**
 * [fuzzyFind] for a list searched again on every typed letter (the whole song graph): each
 * item's text is prepared once, and the returned function searches it.
 */
export function fuzzyIndex<T>(items: T[], text: (item: T) => string): (query: string) => T[] {
	const prepared = items.map((item) => {
		const haystack = plain(text(item));
		return { item, haystack, words: haystack.split(/[^\p{L}\p{N}]+/u).filter(Boolean) };
	});
	return (query) => {
		const typed = plain(query).split(/\s+/).filter(Boolean);
		if (!typed.length) return items;
		const scored: [T, number][] = [];
		for (const { item, haystack, words } of prepared) {
			let total = 0;
			for (const word of typed) {
				const score = wordScore(word, haystack, words);
				if (score === null) {
					total = -1;
					break;
				}
				total += score;
			}
			if (total >= 0) scored.push([item, total]);
		}
		return scored.sort((a, b) => b[1] - a[1]).map(([item]) => item);
	};
}

/** Shows a number with as many decimals as [step] has (0.1 → one), without trailing zeros. */
export function formatStep(n: number, step: number): string {
	const decimals = (String(step).split('.')[1] ?? '').length;
	return String(Number(n.toFixed(decimals)));
}

/**
 * A number typed into a setting: within [min]–[max], rounded to [step] ("1,5" is 1.5 too).
 * Null when it isn't a number, so the box shows the old value again.
 */
export function typedNumber(text: string, min: number, max: number, step = 1): number | null {
	const n = Number(text.trim().replace(',', '.'));
	if (!text.trim() || !Number.isFinite(n)) return null;
	return Number(formatStep(Math.min(max, Math.max(min, Math.round(n / step) * step)), step));
}

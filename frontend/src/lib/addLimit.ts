// How many songs of a long playlist go into the queue (Settings → Adding songs): at most the
// set number without asking; above another, it asks first, with the set choices (and all of
// it); the first ones or picked at random.
import type { PlaylistPick } from './settings.svelte';

/**
 * Added without asking ([count] songs), or asked first: one of [counts], or (with [everything])
 * all of it. A choice that's as long as the playlist or longer is "all of it".
 */
export type AddChoice = { ask: false; count: number } | { ask: true; counts: number[]; everything: boolean };

/**
 * For a playlist of [total] songs (null: not known, a long one), with at most [max] without
 * asking, asking above [askOver] (0: never) with the choices [counts], and [all]: also offer
 * all of it.
 */
export function addChoice(total: number | null, max: number, askOver: number, counts: number[] = [], all = false): AddChoice {
	if (askOver > 0 && (total === null || total > askOver)) {
		const below = [...new Set(counts)].filter((n) => total === null || n < total).sort((a, b) => a - b);
		const everything = all || (total !== null && counts.some((n) => n >= total));
		if (below.length || everything) return { ask: true, counts: below, everything };
	}
	return { ask: false, count: Math.min(total ?? max, max) };
}

/** [count] of [songs]: the first ones, or picked at random (in the playlist's order). */
export function pickSongs<T>(songs: T[], count: number, how: PlaylistPick, random = Math.random): T[] {
	if (songs.length <= count) return songs;
	if (how === 'first') return songs.slice(0, count);
	const order = songs.map((_, i) => i);
	// The first [count] places of a Fisher–Yates shuffle.
	for (let i = 0; i < count; i++) {
		const j = i + Math.floor(random() * (order.length - i));
		[order[i], order[j]] = [order[j], order[i]];
	}
	const chosen = new Set(order.slice(0, count));
	return songs.filter((_, i) => chosen.has(i));
}

/** How a message names [count] songs of [name]: `200 songs from Liked music`, or just the name when it's all of them. */
export function songsFrom(count: number, total: number, name: string): string {
	return count >= total ? name : `${count} ${count === 1 ? 'song' : 'songs'} from ${name}`;
}

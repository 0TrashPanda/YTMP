// How many songs of a long playlist go into the queue (Settings → Adding songs): at most the
// set number, asked first above another, the first ones or picked at random.
import type { PlaylistPick } from './settings.svelte';

/** Added without asking ([count] songs), or asked first: [few] or [many] (all of it, up to the most). */
export type AddChoice = { ask: false; count: number } | { ask: true; few: number; many: number };

/**
 * For a playlist of [total] songs (null: not known, a long one), with at most [max] at once
 * and asking above [askOver] (0: never).
 */
export function addChoice(total: number | null, max: number, askOver: number): AddChoice {
	const many = Math.min(total ?? max, max);
	if (askOver > 0 && (total === null || total > askOver) && many > askOver) return { ask: true, few: askOver, many };
	return { ask: false, count: many };
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

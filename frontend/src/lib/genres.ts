// What a set of songs sounds like, by their genres (docs/implementation/sources/genres.md): the
// queue's autoplay line, History's "Your genres this week" and its genre chips. The room list's
// genres come from the host (RoomSummaries.kt), by the same rules.
import type { Song } from './protocol.gen';

/** Songs with a genre: music only ("" is looked up, none found). */
const genreOf = (song: Song): string | null => (song.genre && !song.podcast && song.genre !== 'Podcast' ? song.genre : null);

/** Each genre's share of the songs that have one, largest first. */
export function genreShares(songs: Song[]): { genre: string; share: number }[] {
	const counts = new Map<string, number>();
	for (const song of songs) {
		const genre = genreOf(song);
		if (genre) counts.set(genre, (counts.get(genre) ?? 0) + 1);
	}
	const total = [...counts.values()].reduce((a, b) => a + b, 0);
	return [...counts].map(([genre, n]) => ({ genre, share: n / total })).sort((a, b) => b.share - a.share);
}

/**
 * The genres these songs are mostly (at least a fifth each, at most [max]); none while fewer
 * than half of the music has a genre yet.
 */
export function topGenres(songs: Song[], max = 2): string[] {
	const music = songs.filter((s) => !s.podcast);
	if (music.filter(genreOf).length * 2 < music.length) return [];
	return genreShares(music).filter((g) => g.share >= 0.2).slice(0, max).map((g) => g.genre);
}

/** "Pop", "Pop and Hip-Hop/Rap". */
export const genreList = (genres: string[]): string => genres.join(' and ');

/**
 * A genre name in parts that compare across Apple, Deezer and YouTube Music: "Hip-Hop/Rap" and
 * "Rap/Hip Hop" are ["hiphop"], "R&B/Soul" and "R&B & soul" are ["rnb", "soul"], "Latin Music" is ["latin"].
 */
function parts(name: string): string[] {
	return name
		.toLowerCase()
		.replace(/r\s*&\s*b/g, 'rnb')
		.split(/\s*(?:\/|&|,|\band\b)\s*/)
		.map((p) => p.normalize('NFD').replace(/[^a-z0-9]/g, '').replace(/music$/, ''))
		.filter(Boolean)
		.map((p) => (p.length > 4 && p.endsWith('s') ? p.slice(0, -1) : p))
		.map((p) => ALIASES[p] ?? p);
}

/** Names that mean the same genre at another store. */
const ALIASES: Record<string, string> = {
	rap: 'hiphop',
	electro: 'electronic',
	electronica: 'electronic',
	reggaeton: 'latin',
	singer: 'folk',
	songwriter: 'folk',
	film: 'soundtrack',
	filmsgame: 'soundtrack'
};

/**
 * YouTube Music's genre page for a song's [genre] (from Apple or Deezer): the one whose name has
 * the genre's first part, else any part of it; null when none fits.
 */
export function genrePageFor<T extends { title: string }>(genre: string, pages: T[]): T | null {
	const want = parts(genre);
	if (!want.length) return null;
	let best: T | null = null;
	let bestScore = 0;
	for (const page of pages) {
		const have = parts(page.title);
		const score = have.includes(want[0]) ? 2 : want.some((p) => have.includes(p)) ? 1 : 0;
		if (score > bestScore) [best, bestScore] = [page, score];
	}
	return best;
}

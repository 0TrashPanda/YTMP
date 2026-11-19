// YouTube Music's genres (GET /api/genres), loaded once when a song menu or the player first
// needs them: the song menu's "More Pop" and the player's genre open the one a song's genre is.
import { getGenres } from './api';
import { genrePageFor } from './genres';
import type { GenreLink, Song } from './protocol.gen';

let list = $state<GenreLink[]>([]);
let asked = false;

/** Loads them, once (again after a failure). */
export function loadYtmGenres(): void {
	if (asked) return;
	asked = true;
	getGenres()
		.then((answer) => (list = answer.genres))
		.catch(() => (asked = false));
}

/** YouTube Music's page for [song]'s genre; null without one (or before they're loaded). */
export function genrePageOf(song: Song | null | undefined): GenreLink | null {
	if (!song?.genre || song.podcast || song.genre === 'Podcast') return null;
	return genrePageFor(song.genre, list);
}

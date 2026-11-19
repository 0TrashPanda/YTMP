// How a room shows in room lists (docs/features/rooms/room-lifecycle.md#the-room-list): a room you
// didn't name is called after the artists it plays, or its genres when no artist stands out.
import type { RoomSummary } from './protocol.gen';

/** The name the room got because nobody typed one: "Anna's room", "Solo room". */
export function isDefaultName(name: string): boolean {
	const n = name.trim().toLowerCase();
	return n.endsWith("'s room") || n.endsWith('’s room') || n === 'solo room' || n === 'room name';
}

/** The genres it plays ("Dance, Pop"), or null when they aren't known. */
function genres(summary: RoomSummary | null | undefined): string | null {
	const list = summary?.genres ?? [];
	return list.length > 0 ? list.join(', ') : null;
}

/** A mix of artists with known genres: named after them ("Pop and Hip-Hop/Rap mix"). */
function genreTitle(summary: RoomSummary | null | undefined): string | null {
	const list = summary?.genres ?? [];
	return summary?.mixed && list.length > 0 ? `${list.join(' and ')} mix` : null;
}

/** The room's title in a list: its own name, else its genres (a mix) or its artists. */
export function roomTitle(name: string, summary: RoomSummary | null | undefined): string {
	if (!isDefaultName(name)) return name;
	const artists = summary?.artists ?? [];
	return genreTitle(summary) ?? (artists.length > 0 ? artists.join(', ') : name);
}

/**
 * The line under it: genres and artists when the title is a name of its own; else the genre
 * (when the title is artists) and the song it's on ("▶ Feather · Nujabes") or played last.
 */
export function roomLine(name: string, summary: RoomSummary | null | undefined): string | null {
	if (!summary) return null;
	const title = roomTitle(name, summary);
	if (title === name && summary.artists.length > 0) return [genres(summary), summary.artists.join(', ')].filter(Boolean).join(' · ');
	const song = summary.song ? (summary.playing ? `▶ ${summary.song}` : `Last: ${summary.song}`) : null;
	const genre = title === genreTitle(summary) ? null : genres(summary);
	return [genre, song].filter(Boolean).join(' · ') || null;
}

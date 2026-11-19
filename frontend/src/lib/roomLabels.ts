// How a room shows in room lists (docs/features/rooms/room-lifecycle.md#the-room-list): a room you
// didn't name is called after the artists it plays, so genre rooms tell themselves apart.
import type { RoomSummary } from './protocol.gen';

/** The name the room got because nobody typed one: "Anna's room", "Solo room". */
export function isDefaultName(name: string): boolean {
	const n = name.trim().toLowerCase();
	return n.endsWith("'s room") || n.endsWith('’s room') || n === 'solo room' || n === 'room name';
}

/** The room's title in a list: its own name, or its artists if it never got one. */
export function roomTitle(name: string, summary: RoomSummary | null | undefined): string {
	const artists = summary?.artists ?? [];
	return isDefaultName(name) && artists.length > 0 ? artists.join(', ') : name;
}

/**
 * The line under it: the artists when the title is a name of its own, else the song it's on
 * ("▶ Feather · Nujabes") or played last.
 */
export function roomLine(name: string, summary: RoomSummary | null | undefined): string | null {
	if (!summary) return null;
	if (roomTitle(name, summary) === name && summary.artists.length > 0) return summary.artists.join(', ');
	if (!summary.song) return null;
	return summary.playing ? `▶ ${summary.song}` : `Last: ${summary.song}`;
}

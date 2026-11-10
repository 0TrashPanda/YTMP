// The song playing in the room, marked in lists (search, albums, playlists, Up next) like YTM:
// bars over its art, moving while it plays and standing still while paused.
import type { RoomConnection } from './room.svelte';

export function playingMark(room: RoomConnection | null | undefined, songId: string | null | undefined): 'moving' | 'still' | null {
	const now = room?.state?.nowPlaying;
	if (!now || !songId || now.item.song.id !== songId) return null;
	return room?.state?.playback.playing ? 'moving' : 'still';
}

import { thumbUrl } from './images.svelte';
import type { Song } from './protocol.gen';

export function formatTime(ms: number): string {
	const total = Math.max(0, Math.floor(ms / 1000));
	const minutes = Math.floor(total / 60);
	const seconds = total % 60;
	return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

/** The artists, or for a podcast episode the podcast. */
export function artistNames(song: Song): string {
	return song.artists.map((a) => a.name).join(', ') || song.podcast?.name || '';
}

/** A song's picture for showing it [size] px wide (see [thumbUrl]). */
export function artUrl(song: Song, size: number): string | null {
	return thumbUrl(song.thumbnails, size) ?? null;
}

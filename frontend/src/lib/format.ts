import type { Song } from './protocol.gen';

export function formatTime(ms: number): string {
	const total = Math.max(0, Math.floor(ms / 1000));
	const minutes = Math.floor(total / 60);
	const seconds = total % 60;
	return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

export function artistNames(song: Song): string {
	return song.artists.map((a) => a.name).join(', ');
}

/** The smallest thumbnail that is at least [size] px, or the largest one. */
export function artUrl(song: Song, size: number): string | null {
	const sorted = [...song.thumbnails].sort((a, b) => a.width - b.width);
	return (sorted.find((t) => t.width >= size) ?? sorted.at(-1))?.url ?? null;
}

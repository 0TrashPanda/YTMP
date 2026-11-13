// Playlists kept offline on the phone (docs/features/music/sources.md#playlists-offline): Keep offline on
// a playlist's page, and the list in Settings → Song cache and downloads. Only on the phone's own
// pages, where its song cache is.

import { getOfflinePlaylists, keepPlaylist } from './api';
import { nativeBridge } from './native';
import type { OfflinePlaylist, OfflinePlaylists, Thumbnail } from './protocol.gen';

/** On the phone's own pages, which keep its song cache. */
export const onThisPhone = () => !!nativeBridge && ['127.0.0.1', 'localhost'].includes(location.hostname);

export const offline = $state<{ list: OfflinePlaylists | null }>({ list: null });

let timer: ReturnType<typeof setTimeout> | undefined;
let watchers = 0;

/** Loads the list; while songs come in, again every few seconds (as long as someone [watch]es). */
export async function loadOffline(): Promise<OfflinePlaylists | null> {
	clearTimeout(timer);
	try {
		offline.list = await getOfflinePlaylists();
	} catch {
		return offline.list;
	}
	if (watchers > 0 && coming(offline.list)) timer = setTimeout(loadOffline, 5000);
	return offline.list;
}

/** Keeps the list fresh while a page shows it; the function it gives back stops that. */
export function watchOffline(): () => void {
	watchers++;
	void loadOffline();
	return () => {
		watchers--;
		if (!watchers) clearTimeout(timer);
	};
}

/** Songs are still coming in for a kept (or most played) playlist. */
export const coming = (list: OfflinePlaylists | null) =>
	!!list && (list.busy || list.playlists.some((p) => (p.kept || p.mostPlayed) && missing(p) > 0)) && !list.waiting;

/** Songs of [p] that aren't on the phone yet but can be (not the ones the cache can't keep). */
export const missing = (p: OfflinePlaylist) => Math.max(0, p.songs - p.here - p.notKept);

export const findOffline = (id: string) => offline.list?.playlists.find((p) => p.id === id) ?? null;

/** Keeps playlist [id] offline, or stops; the message to show. */
export async function setKept(id: string, keep: boolean, title: string, thumbnails: Thumbnail[]): Promise<string> {
	offline.list = await keepPlaylist({ id, keep, title, thumbnails });
	if (watchers > 0) void loadOffline();
	return keep ? `"${title}" is kept offline: its songs download in the background` : `"${title}" isn't kept offline any more`;
}

/** What a playlist's Keep offline button says: "Keep offline", or how far it is. */
export function keepLabel(p: OfflinePlaylist | null): string {
	if (!p?.kept) return 'Keep offline';
	if (!p.read) return 'Offline · getting ready';
	if (p.tooLong) return 'Offline · too long';
	if (p.here >= p.songs) return 'Offline';
	return `Offline · ${p.here}/${p.songs}`;
}

/** A line about a playlist in the settings: how many songs are here, and why not all. */
export function offlineLine(p: OfflinePlaylist): string {
	if (!p.read) return 'Getting its songs…';
	if (p.tooLong) return 'Too many songs to keep offline (over 1000)';
	const parts = [p.here >= p.songs ? `All ${p.songs} ${p.songs === 1 ? 'song' : 'songs'} on this phone` : `${p.here} of ${p.songs} songs on this phone`];
	if (p.notKept) parts.push(`${p.notKept} can't be kept in the cache (podcast episodes, songs over an hour): download ${p.notKept === 1 ? 'it' : 'them'} from the song menu`);
	return parts.join(' · ');
}

/** Why songs aren't coming in, in plain words, or null. */
export function offlineNote(list: OfflinePlaylists | null): string | null {
	switch (list?.waiting) {
		case 'Wi-Fi':
			return 'Waiting for Wi-Fi.';
		case 'full':
			return `The cache is full${list.full ? ` before all of "${list.full}" fits` : ''}: a bigger size keeps more.`;
		case 'error':
			return "Couldn't reach YouTube Music; trying again later.";
		default:
			return null;
	}
}

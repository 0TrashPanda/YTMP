// The desktop app's music and song cache on this computer (docs/implementation/desktop-app.md#music-and-songs-on-this-computer).
// The app's own host keeps them (desktop/host/: the same host code as the phone's, without
// rooms); the window reaches it at the app's `ytmphost` address and plays from there, so they
// never go through the server, unless the server asks for a song another player in the room
// needs ([watchForRequests]).
import type { AlbumPage, ArtistPage, LocalLibraries, LocalLibraryPage, LocalLibrarySummary, Song, SongCacheStatus } from './protocol.gen';
import { desktopApp } from './ownModule.svelte';

export interface ComputerStatus {
	libraryId: string;
	cacheMb: number;
	cacheSongs: number;
	cacheBytes: number;
	music: boolean;
	folders: { path: string; name: string }[];
	musicSongs: number;
	scanning: boolean;
}

interface MusicStatus {
	music: boolean;
	folders: { path: string; name: string }[];
	songs: number;
	scanning: boolean;
	libraryId: string;
}

/** Songs longer than this (mixes, whole concerts) aren't kept, like on the phone. */
const MAX_CACHED_MS = 60 * 60 * 1000;
const MB = 1024 * 1024;

/** This computer's music and cache; null until asked (and always outside a desktop app that has them). */
export const computer = $state({ status: null as ComputerStatus | null });

/** The desktop app has its own host (0.46 on: older apps don't). */
export function onComputer(): boolean {
	return !!desktopApp()?.host;
}

async function ask<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
	const host = desktopApp()?.host;
	if (!host) throw new Error('Only in the desktop app');
	const response = await fetch(host + path, {
		method,
		headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
		body: body === undefined ? undefined : JSON.stringify(body)
	});
	const text = await response.text();
	const parsed = (() => {
		try {
			return JSON.parse(text);
		} catch {
			return null;
		}
	})();
	if (!response.ok) throw new Error(parsed?.error?.message ?? `this computer's host answered ${response.status}`);
	return parsed as T;
}

function combine(music: MusicStatus, cache: SongCacheStatus): ComputerStatus {
	return {
		libraryId: music.libraryId,
		cacheMb: Math.round(cache.maxBytes / MB),
		cacheSongs: cache.songs,
		cacheBytes: cache.bytes,
		music: music.music,
		folders: music.folders,
		musicSongs: music.songs,
		scanning: music.scanning
	};
}

export async function loadComputer(): Promise<ComputerStatus | null> {
	if (!onComputer()) return null;
	try {
		const [music, cache] = await Promise.all([ask<MusicStatus>('/api/desktop/music'), ask<SongCacheStatus>('/api/cache')]);
		computer.status = combine(music, cache);
		watchForRequests();
	} catch {
		// The host is starting: the settings ask again.
	}
	return computer.status;
}

export async function changeComputer(changes: { cacheMb?: number; music?: boolean; folders?: string[] }): Promise<void> {
	if (changes.cacheMb !== undefined) await ask<SongCacheStatus>('/api/cache', 'PUT', { maxBytes: changes.cacheMb * MB });
	if (changes.music !== undefined || changes.folders !== undefined) await ask<MusicStatus>('/api/desktop/music', 'PUT', { music: changes.music, folders: changes.folders });
	await loadComputer();
}

export async function clearComputerCache(): Promise<void> {
	await ask('/api/cache/clear', 'POST');
	await loadComputer();
}

export async function rescanComputer(): Promise<void> {
	await ask('/api/desktop/music/rescan', 'POST');
	await loadComputer();
}

/** The system's folder picker; the folder joins the music on this computer. */
export async function addComputerFolder(): Promise<void> {
	const path = await desktopApp()?.pickFolder?.();
	if (!path) return;
	const folders = computer.status?.folders.map((f) => f.path) ?? [];
	await changeComputer({ folders: [...folders, path], music: true });
}

export async function removeComputerFolder(path: string): Promise<void> {
	await changeComputer({ folders: (computer.status?.folders ?? []).map((f) => f.path).filter((p) => p !== path) });
}

const musicOn = () => !!computer.status?.music && (computer.status.folders.length ?? 0) > 0;

/** The Local tab's library of this computer's music, when it's on. */
export async function computerLibrary(): Promise<LocalLibrarySummary | null> {
	if (!onComputer()) return null;
	if (!computer.status) await loadComputer();
	if (!musicOn()) return null;
	try {
		const { libraries } = await ask<LocalLibraries>('/api/local');
		return libraries.find((l) => l.id === computer.status?.libraryId) ?? null;
	} catch {
		return null;
	}
}

export const isComputerLibrary = (id: string) => !!computer.status && id === computer.status.libraryId;
export const computerLibraryPage = (id: string) => ask<LocalLibraryPage>(`/api/local/${encodeURIComponent(id)}`);

/** A song, album or artist of this computer's music (not another computer's). */
export function isMine(id: string | null | undefined): boolean {
	const device = desktopApp()?.device?.id;
	return !!device && !!id && id.startsWith(`local:dev-${device}:`);
}

export const computerAlbum = (id: string) => ask<AlbumPage>(`/api/albums/${encodeURIComponent(id)}`);
export const computerArtist = (id: string) => ask<ArtistPage>(`/api/artists/${encodeURIComponent(id)}`);

/** A YouTube Music song the song cache takes (it's on, music, at most an hour). */
function cacheTakes(song: Song): boolean {
	return (computer.status?.cacheMb ?? 0) > 0 && song.id.startsWith('ytm:') && !song.podcast && song.durationMs <= MAX_CACHED_MS;
}

/**
 * Where this computer plays [song] from itself, or null: a song of its own music, or (with the
 * song cache on) a YouTube Music song, which the cache keeps as it plays.
 */
export function computerAudio(song: Song): string | null {
	const host = desktopApp()?.host;
	if (!host || (!isMine(song.id) && !cacheTakes(song))) return null;
	return `${host}/api/audio/${encodeURIComponent(song.id)}`;
}

/**
 * Before the window asks for [song]'s audio: the cache hears about it (`started`: it plays now
 * and counts as a play; `known`: it's next), so the request fills the cache.
 */
export async function tellComputer(song: Song, how: 'started' | 'known'): Promise<void> {
	if (!cacheTakes(song)) return;
	await ask(`/api/desktop/${how}`, 'POST', song).catch(() => {});
}

let watching = false;

/**
 * While the music on this computer is on: the server says when another player in a room needs
 * one of its songs (it asks no sooner), and this computer's host sends that one file.
 */
function watchForRequests(): void {
	const app = desktopApp();
	if (watching || !app?.device || !musicOn()) return;
	watching = true;
	void (async () => {
		const { id, secret } = app.device!;
		let wait = 1000;
		while (musicOn()) {
			try {
				const response = await fetch(`/api/devices/${id}/wanted`, { headers: { 'X-Device-Secret': secret } });
				if (!response.ok) throw new Error(`the server answered ${response.status}`);
				wait = 1000;
				const { songs } = (await response.json()) as { songs: string[] };
				for (const songId of songs) {
					const url = new URL(`/api/devices/${id}/songs/${encodeURIComponent(songId)}`, location.href).href;
					ask('/api/desktop/upload', 'POST', { songId, url, secret }).catch((e: Error) => console.warn(`sending ${songId}: ${e.message}`));
				}
			} catch {
				// An older server, or no connection: ask again a little later.
				await new Promise((r) => setTimeout(r, wait));
				wait = Math.min(wait * 2, 60_000);
			}
		}
		watching = false;
	})();
}

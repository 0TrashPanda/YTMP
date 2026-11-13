// The desktop app's music and song cache on this computer (docs/implementation/desktop-app.md#music-and-songs-on-this-computer).
// The app's own module keeps them (ytm-module/ytmp_ytm/desktop.py); the window plays them from
// the app's `ytmpfiles` address, so they never go through the server, unless the server asks
// for a song another player in the room needs ([sendWhenAsked]).
import type { AlbumPage, ArtistPage, LocalLibraryPage, LocalLibrarySummary, Song } from './protocol.gen';
import { desktopApp } from './ownModule.svelte';

export interface ComputerStatus {
	device: string;
	libraryId: string;
	cacheMb: number;
	cacheSongs: number;
	cacheBytes: number;
	music: boolean;
	folders: { path: string; name: string }[];
	musicSongs: number;
	scanning: boolean;
}

/** Songs longer than this (mixes, whole concerts) aren't kept, like on the phone. */
const MAX_CACHED_MS = 60 * 60 * 1000;

/** This computer's music and cache; null until asked (and always outside a desktop app that has them). */
export const computer = $state({ status: null as ComputerStatus | null });

/** The desktop app has them (0.46 on: older apps don't). */
export function onComputer(): boolean {
	return !!desktopApp()?.files;
}

async function ask<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
	const app = desktopApp();
	if (!app?.files || !app.moduleRequest) throw new Error('Only in the desktop app');
	const answer = await app.moduleRequest(path, method, body === undefined ? undefined : JSON.stringify(body));
	const parsed = (() => {
		try {
			return JSON.parse(answer.body);
		} catch {
			return null;
		}
	})();
	if (answer.status < 200 || answer.status > 299) throw new Error(parsed?.error?.message ?? `the app's module answered ${answer.status}`);
	return parsed as T;
}

export async function loadComputer(): Promise<ComputerStatus | null> {
	if (!onComputer()) return null;
	try {
		computer.status = await ask<ComputerStatus>('desktop/status');
		watchForRequests();
	} catch {
		// The module is starting: the settings ask again.
	}
	return computer.status;
}

export async function changeComputer(changes: { cacheMb?: number; music?: boolean; folders?: string[] }): Promise<void> {
	computer.status = await ask<ComputerStatus>('desktop/settings', 'PUT', changes);
	watchForRequests();
}

export async function clearComputerCache(): Promise<void> {
	computer.status = await ask<ComputerStatus>('desktop/cache/clear', 'POST');
}

export async function rescanComputer(): Promise<void> {
	computer.status = await ask<ComputerStatus>('desktop/music/rescan', 'POST');
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
		return await ask<LocalLibrarySummary>('desktop/library/summary');
	} catch {
		return null;
	}
}

export const isComputerLibrary = (id: string) => !!computer.status && id === computer.status.libraryId;
export const computerLibraryPage = () => ask<LocalLibraryPage>('desktop/library');

/** A song, album or artist of this computer's music (not another computer's). */
export function isMine(id: string | null | undefined): boolean {
	const device = desktopApp()?.device?.id;
	return !!device && !!id && id.startsWith(`local:dev-${device}:`);
}

export const computerAlbum = (id: string) => ask<AlbumPage>(`desktop/albums/${encodeURIComponent(id)}`);
export const computerArtist = (id: string) => ask<ArtistPage>(`desktop/artists/${encodeURIComponent(id)}`);

/**
 * Where this computer plays [song] from itself, or null: a song of its own music, or (with the
 * song cache on) a YouTube Music song, which the cache keeps as it plays.
 */
export function computerAudio(song: Song): string | null {
	const files = desktopApp()?.files;
	if (!files) return null;
	const cached = (computer.status?.cacheMb ?? 0) > 0 && song.id.startsWith('ytm:') && !song.podcast && song.durationMs <= MAX_CACHED_MS;
	if (!isMine(song.id) && !cached) return null;
	return `${files}audio/${encodeURIComponent(song.id)}`;
}

let watching = false;

/**
 * While the music on this computer is on: the server says when another player in a room needs
 * one of its songs (it asks no sooner), and the app's module sends that one file.
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
					ask('desktop/upload', 'POST', { songId, url, secret }).catch((e: Error) => console.warn(`sending ${songId}: ${e.message}`));
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

// Download in the song menu (docs/implementation/downloads.md). The host makes the song a file
// with its title, artist, album and cover in it. A browser saves that the usual way; the app
// saves it in the phone's Music/YTMP folder (it looks the song up itself, whatever the host).

import type { Song } from './protocol.gen';
import { downloadFile } from './api';
import { nativeBridge } from './native';
import { isYoutubeSong } from './ownModule';

/** Songs that can be downloaded: YouTube Music's (not someone's local music). */
export const canDownload = (song: Song) => isYoutubeSong(song.id);

/** The file name in a Content-Disposition header (the UTF-8 one first), or null. */
export function fileNameFrom(disposition: string | null): string | null {
	if (!disposition) return null;
	const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
	if (utf8) {
		try {
			return decodeURIComponent(utf8[1].trim());
		} catch {
			// Fall back to the plain name.
		}
	}
	return /filename="([^"]*)"/i.exec(disposition)?.[1] ?? null;
}

/** Answers from the app, by song: one download per song at a time. */
const waiting = new Map<string, (error: string | null) => void>();

function inApp(song: Song): Promise<string | null> | null {
	const bridge = nativeBridge;
	if (!bridge?.downloadSong) return null;
	window.__ytmpNative = {
		...window.__ytmpNative,
		onDownloaded: (songId, error) => {
			waiting.get(songId)?.(error);
			waiting.delete(songId);
		}
	};
	return new Promise((resolve) => {
		const before = waiting.get(song.id);
		waiting.set(song.id, (error) => (before?.(error), resolve(error)));
		bridge.downloadSong!(JSON.stringify(song));
	});
}

async function inBrowser(song: Song): Promise<void> {
	const { blob, disposition } = await downloadFile(song);
	const url = URL.createObjectURL(blob);
	const link = document.createElement('a');
	link.href = url;
	link.download = fileNameFrom(disposition) ?? `${song.title}.m4a`;
	document.body.append(link);
	link.click();
	link.remove();
	setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

/** Downloads [song], telling how it goes with [onToast]. */
export async function downloadSong(song: Song, onToast: (text: string) => void): Promise<void> {
	onToast(`Downloading "${song.title}"…`);
	const app = inApp(song);
	if (app) {
		const error = await app;
		onToast(error ? `Couldn't download "${song.title}": ${error}` : `Downloaded "${song.title}" to the Music folder`);
		return;
	}
	try {
		await inBrowser(song);
		onToast(`Downloaded "${song.title}"`);
	} catch (e) {
		onToast(`Couldn't download "${song.title}": ${e instanceof Error ? e.message : e}`);
	}
}

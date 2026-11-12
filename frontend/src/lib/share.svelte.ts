// Sharing a room or a song (docs/features/sharing.md): by holding another phone against this
// one (the app answers as an NFC tag with the link, android/.../share/TapShareService.kt), and
// through the normal share sheet. A song's link is its YouTube Music link, so a friend without
// YTMP gets it in YouTube Music; with YTMP it opens the song screen (SharedSong.svelte).
import { nativeBridge } from './native';
import { settings } from './settings.svelte';
import type { Song } from './protocol.gen';

/** A song's link to share: its YouTube Music link; null for music that's only here (local files). */
export function songLink(song: Pick<Song, 'id'>): string | null {
	const id = /^(?:ytm|yt):([A-Za-z0-9_-]{11})$/.exec(song.id)?.[1];
	return id ? `https://music.youtube.com/watch?v=${id}` : null;
}

/** Whether holding phones together can share here: 'on', 'off' (NFC is switched off on the phone), or null (not here, or switched off in Settings). */
export function tapShareState(): 'on' | 'off' | null {
	if (!nativeBridge?.tapShare || !settings.tapToShare.value) return null;
	const state = nativeBridge.tapShare();
	return state === 'none' ? null : state;
}

/** The phone has NFC (whatever Settings → Sharing says), for the setting itself. */
export const canTapShare = () => !!nativeBridge?.tapShare && nativeBridge.tapShare() !== 'none';

/**
 * While a Share sheet is open: holding another phone against this one hands over [url]. Returns
 * how to stop (when the sheet closes). Only one at a time: the newest wins.
 */
export function shareByTap(url: string, openInApp = true): () => void {
	if (!tapShareState()) return () => {};
	if (nativeBridge?.setTapShareFor) nativeBridge.setTapShareFor(url, openInApp);
	else nativeBridge?.setTapShare?.(url);
	return () => nativeBridge?.setTapShare?.(null);
}

/** The phone's (or browser's) share sheet with [url]; without one, the link is copied. */
export async function shareLink(title: string, url: string, onToast: (text: string) => void): Promise<void> {
	if (nativeBridge?.shareLink) return nativeBridge.shareLink(title, url);
	try {
		if (navigator.share) return void (await navigator.share({ title, url }));
	} catch (e) {
		if (e instanceof DOMException && e.name === 'AbortError') return;
	}
	await copyLink(url, onToast);
}

export async function copyLink(url: string, onToast: (text: string) => void): Promise<void> {
	try {
		await navigator.clipboard.writeText(url);
		onToast('Link copied');
	} catch {
		onToast(url);
	}
}

/**
 * A song another phone or app shared with this one: [show] gets its song id, now (one that came
 * while no page could show it) and whenever another comes. Returns how to stop listening.
 */
export function onSharedSong(show: (songId: string) => void): () => void {
	if (!nativeBridge?.takeShared) return () => {};
	const take = () => {
		const id = nativeBridge?.takeShared?.();
		if (id) show(id);
	};
	const onShared = take;
	window.__ytmpNative = { ...window.__ytmpNative, onShared };
	take();
	return () => {
		if (window.__ytmpNative?.onShared === onShared) window.__ytmpNative = { ...window.__ytmpNative, onShared: undefined };
	};
}

/** What the song screen's buttons do with a shared song in a room. */
export type SharedAction = 'play' | 'next' | 'end' | 'radio';
const ACTIONS: SharedAction[] = ['play', 'next', 'end', 'radio'];

/**
 * A room link that does [action] with a shared song once the room is open (after "Play it in":
 * a new solo room, or another room). The room page reads it with [sharedFromUrl].
 */
export function roomLinkWith(base: string, songId: string, action: SharedAction): string {
	return `${base}?shared=${encodeURIComponent(songId)}&do=${action}`;
}

/** The shared song and what to do with it, from the room page's address; null when there's none. */
export function sharedFromUrl(url: URL): { songId: string; action: SharedAction } | null {
	const songId = url.searchParams.get('shared');
	const action = url.searchParams.get('do') as SharedAction | null;
	if (!songId || !/^(?:ytm|yt):[A-Za-z0-9_-]{11}$/.test(songId) || !action || !ACTIONS.includes(action)) return null;
	return { songId, action };
}

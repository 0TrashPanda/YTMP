// Your own YouTube Music module (ytm-module/), running on this computer. YouTube's stream
// links only work from the internet connection that asked for them, so when the room's links
// don't work here, the player asks this module instead of streaming through the host
// (docs/implementation/playback-sync.md).

import { saved, type OwnModule } from './storage';

/** Songs the module can look up; local files and the like come from the host. */
export const isYoutubeSong = (songId: string) => songId.startsWith('ytm:');

/** Give up on a lookup after this long (ms); it usually takes 1–3 s. */
const LOOKUP_TIMEOUT_MS = 20_000;
/** Use a link only while it has at least this long (ms) left. */
const EXPIRY_MARGIN_MS = 10 * 60_000;

const links = new Map<string, Promise<{ url: string; expiresAt: number }>>();

async function get<T>(module: OwnModule, path: string): Promise<T> {
	let response: Response;
	try {
		response = await fetch(new URL(path, module.url.replace(/\/*$/, '/')), {
			headers: { Authorization: `Bearer ${module.key}` },
			signal: AbortSignal.timeout(LOOKUP_TIMEOUT_MS)
		});
	} catch {
		throw new Error(`can't reach your module at ${module.url}`);
	}
	if (!response.ok) {
		const body = await response.json().catch(() => null);
		if (response.status === 401) throw new Error('your module turned the key down');
		throw new Error(body?.error?.message ?? `your module answered ${response.status}`);
	}
	return response.json();
}

/** A stream link for [songId] from your own module; throws with a readable reason. */
export async function ownStreamUrl(songId: string): Promise<string> {
	const module = saved.ownModule;
	if (!module) throw new Error('no module of your own set up');
	const cached = links.get(songId);
	if (cached) {
		const link = await cached.catch(() => null);
		if (link && link.expiresAt - Date.now() > EXPIRY_MARGIN_MS) return link.url;
		if (links.get(songId) === cached) links.delete(songId);
	}
	const lookup = get<{ url: string; expiresAt: number }>(module, `songs/${encodeURIComponent(songId)}/stream`);
	links.set(songId, lookup);
	lookup.catch(() => links.delete(songId));
	return (await lookup).url;
}

/** Looks up [songId] ahead of time, so the next song starts without waiting. */
export function prefetchOwnStream(songId: string): void {
	if (saved.ownModule && isYoutubeSong(songId)) ownStreamUrl(songId).catch(() => {});
}

/** Forgets a link that didn't play, so the next try asks again. */
export function forgetOwnStream(songId: string): void {
	links.delete(songId);
}

/** Checks the address and key: the module's version, or throws with a readable reason. */
export async function testOwnModule(module: OwnModule): Promise<string> {
	const info = await get<{ name: string; version: string }>(module, 'info');
	return `${info.name} module ${info.version}`;
}

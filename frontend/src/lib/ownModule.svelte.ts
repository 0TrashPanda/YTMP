// Your own YouTube Music module (ytm-module/), running on this computer. YouTube's stream
// links only work from the internet connection that asked for them, so the player can ask this
// module instead of using the room's link or streaming through the host, in the order chosen
// in the settings (docs/implementation/playback-sync.md).
//
// Whether it answers is checked when the page opens and then every minute while it doesn't, so
// the player skips it meanwhile instead of waiting on it for every song, and the room says so.

import { saved, type OwnModule } from './storage';

/** Songs the module can look up; local files and the like come from the host. */
export const isYoutubeSong = (songId: string) => songId.startsWith('ytm:');

/** Give up on a lookup after this long (ms); it usually takes 1–3 s. */
const LOOKUP_TIMEOUT_MS = 20_000;
/** Use a link only while it has at least this long (ms) left. */
const EXPIRY_MARGIN_MS = 10 * 60_000;
/** While it doesn't answer, asked again this often (ms). */
export const RETRY_MS = 60_000;

/** The module can't be used at all (not reachable, or it turns the key down), as opposed to one song failing. */
class ModuleDown extends Error {}

/**
 * Off: none, or switched off. Checking: asked, no answer yet. Works: it answered (`text` says
 * what). Down: it didn't (`text` says why), asked again every [RETRY_MS].
 */
export const moduleStatus = $state<{ state: 'off' | 'checking' | 'works' | 'down'; text: string }>({ state: 'off', text: '' });

let retry: ReturnType<typeof setTimeout> | undefined;
/** Counts checks, so one that answers after the settings changed is ignored. */
let checks = 0;

/** The module, when it's set up and switched on. */
export const activeModule = (): OwnModule | null => {
	const module = saved.ownModule;
	return module?.on ? module : null;
};

/** The player may ask it now: on, and not known to be down. */
export const moduleUsable = (): boolean => activeModule() !== null && moduleStatus.state !== 'down';

const links = new Map<string, Promise<{ url: string; expiresAt: number }>>();

async function get<T>(module: OwnModule, path: string): Promise<T> {
	let response: Response;
	try {
		response = await fetch(new URL(path, module.url.replace(/\/*$/, '/')), {
			headers: { Authorization: `Bearer ${module.key}` },
			signal: AbortSignal.timeout(LOOKUP_TIMEOUT_MS)
		});
	} catch {
		throw new ModuleDown(`can't reach your module at ${module.url}`);
	}
	if (!response.ok) {
		const body = await response.json().catch(() => null);
		if (response.status === 401) throw new ModuleDown('your module turned the key down');
		throw new Error(body?.error?.message ?? `your module answered ${response.status}`);
	}
	return response.json();
}

function down(text: string): void {
	moduleStatus.state = 'down';
	moduleStatus.text = text;
	clearTimeout(retry);
	retry = setTimeout(checkOwnModule, RETRY_MS);
}

/** Asks the module whether it answers now (see [moduleStatus]); on the page's start, after a settings change, and while it's down. */
export async function checkOwnModule(): Promise<void> {
	clearTimeout(retry);
	const check = ++checks;
	const module = activeModule();
	if (!module) {
		moduleStatus.state = 'off';
		moduleStatus.text = '';
		return;
	}
	if (moduleStatus.state === 'off') moduleStatus.state = 'checking';
	try {
		const text = await testOwnModule(module);
		if (check !== checks) return;
		moduleStatus.state = 'works';
		moduleStatus.text = text;
	} catch (e) {
		if (check !== checks) return;
		down((e as Error).message);
	}
}

/** Keeps the address and key, but stops using it (until switched on again in the settings). */
export function switchOffOwnModule(): void {
	const module = saved.ownModule;
	if (module) saved.ownModule = { ...module, on: false };
	void checkOwnModule();
}

/** A stream link for [songId] from your own module; throws with a readable reason. */
export async function ownStreamUrl(songId: string): Promise<string> {
	const module = activeModule();
	if (!module) throw new Error('no module of your own switched on');
	const cached = links.get(songId);
	if (cached) {
		const link = await cached.catch(() => null);
		if (link && link.expiresAt - Date.now() > EXPIRY_MARGIN_MS) return link.url;
		if (links.get(songId) === cached) links.delete(songId);
	}
	const lookup = get<{ url: string; expiresAt: number }>(module, `songs/${encodeURIComponent(songId)}/stream`);
	links.set(songId, lookup);
	lookup.then(
		() => {
			if (moduleStatus.state !== 'works') void checkOwnModule();
		},
		(e) => {
			links.delete(songId);
			if (e instanceof ModuleDown) down(e.message);
		}
	);
	return (await lookup).url;
}

/** Looks up [songId] ahead of time, so the next song starts without waiting. */
export function prefetchOwnStream(songId: string): void {
	if (moduleUsable() && isYoutubeSong(songId)) ownStreamUrl(songId).catch(() => {});
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

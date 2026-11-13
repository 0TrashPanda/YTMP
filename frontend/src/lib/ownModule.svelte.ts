// Your own YouTube Music module (ytm-module/), running on this computer. YouTube's stream
// links only work from the internet connection that asked for them, so the player can ask this
// module instead of using the room's link or streaming through the host, in the order chosen
// in the settings (docs/implementation/playback-sync.md). Search and the pages ask it first too
// ([askOwnModule], used by api.ts), so YouTube sees this computer rather than the room's server.
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

/** Search and pages: give up sooner, the room's server can answer instead. */
const CATALOG_TIMEOUT_MS = 12_000;

/** The module can't be used at all (not reachable, or it turns the key down), as opposed to one song failing. */
class ModuleDown extends Error {}

/** The module answered with an error: its HTTP [status] and error [code] (`not_found`, `blocked`, …). */
export class ModuleError extends Error {
	constructor(
		message: string,
		readonly status: number,
		readonly code: string | null
	) {
		super(message);
	}
}

/**
 * Off: none, or switched off. Checking: asked, no answer yet. Works: it answered (`text` says
 * what). Down: it didn't (`text` says why), asked again every [RETRY_MS].
 */
export const moduleStatus = $state<{ state: 'off' | 'checking' | 'works' | 'down'; text: string }>({ state: 'off', text: '' });

let retry: ReturnType<typeof setTimeout> | undefined;
/** Counts checks, so one that answers after the settings changed is ignored. */
let checks = 0;

/** The desktop app (desktop/, docs/implementation/desktop-app.md), or null in a browser or the Android app. */
export const desktopApp = (): DesktopApp | null => (typeof window !== 'undefined' ? (window.YtmpDesktop ?? null) : null);

/** In the desktop app: its own module, reached through the app (no address or key). */
export const DESKTOP_MODULE: OwnModule = { url: 'this app', key: '', on: true };

/** The module, when it's set up and switched on: in the desktop app its own one, unless switched off. */
export const activeModule = (): OwnModule | null => {
	if (desktopApp()?.moduleRequest) return saved.desktopModuleOn ? DESKTOP_MODULE : null;
	const module = saved.ownModule;
	return module?.on ? module : null;
};

/** The player may ask it now: on, and not known to be down. */
export const moduleUsable = (): boolean => activeModule() !== null && moduleStatus.state !== 'down';

const links = new Map<string, Promise<{ url: string; expiresAt: number }>>();

/** What the module answered: its HTTP status and body (JSON text). */
export interface ModuleAnswer {
	status: number;
	body: string;
}

/** What the desktop app gives the page. */
export interface DesktopApp {
	version: string;
	platform: 'linux' | 'windows';
	/**
	 * A request to the app's module (GET unless [method] says otherwise, [body] JSON); the app
	 * forwards it, since WebKitGTK won't let an https page fetch http://127.0.0.1. Older apps only GET.
	 */
	moduleRequest?: (path: string, method?: string, body?: string) => Promise<ModuleAnswer>;
	/** Back to the app's page that asks for the server's address. */
	changeServer?: () => Promise<void>;
	/** This build looks for new versions when it starts (absent before 0.45: those didn't). */
	updates?: boolean;
	/** This computer's host, where the window gets this computer's music and song cache (`…/api/…`); absent in older apps. */
	host?: string;
	/** This computer's id (in its songs' IDs) and secret, for the server's requests for a song. */
	device?: { id: string; secret: string };
	/** The system's folder picker: the folder's path, or null. */
	pickFolder?: () => Promise<string | null>;
	/**
	 * Looks for a new version now (also one you skipped): true when it offered one (its dialog
	 * answered by then), false when this is the newest; rejects with why it couldn't look.
	 * Absent before 0.47.
	 */
	checkUpdates?: () => Promise<boolean>;
}

declare global {
	interface Window {
		/** The desktop app's shell (desktop/src-tauri/src/main.rs, `bridge_script`); absent elsewhere. */
		YtmpDesktop?: DesktopApp;
	}
}

/**
 * Every request to the module goes through here: [path] (like `search?q=…`), with the key.
 * In the desktop app through its shell, else with fetch. Throws when it can't be reached.
 */
export async function moduleRequest(module: OwnModule, path: string, timeoutMs = LOOKUP_TIMEOUT_MS): Promise<ModuleAnswer> {
	const desktop = typeof window !== 'undefined' ? window.YtmpDesktop?.moduleRequest : undefined;
	if (desktop) {
		let timer: ReturnType<typeof setTimeout> | undefined;
		const late = new Promise<never>((_, reject) => (timer = setTimeout(() => reject(new Error('timeout')), timeoutMs)));
		return Promise.race([desktop(path), late]).finally(() => clearTimeout(timer));
	}
	const response = await fetch(new URL(path, module.url.replace(/\/*$/, '/')), {
		headers: { Authorization: `Bearer ${module.key}` },
		signal: AbortSignal.timeout(timeoutMs)
	});
	return { status: response.status, body: await response.text() };
}

async function get<T>(module: OwnModule, path: string, timeoutMs = LOOKUP_TIMEOUT_MS): Promise<T> {
	let answer: ModuleAnswer;
	try {
		answer = await moduleRequest(module, path, timeoutMs);
	} catch {
		throw new ModuleDown(module === DESKTOP_MODULE ? "this app's YouTube Music module doesn't answer" : `can't reach your module at ${module.url}`);
	}
	const parse = () => {
		try {
			return JSON.parse(answer.body);
		} catch {
			return null;
		}
	};
	if (answer.status < 200 || answer.status > 299) {
		const body = parse();
		if (answer.status === 401) throw new ModuleDown('your module turned the key down');
		throw new ModuleError(body?.error?.message ?? `your module answered ${answer.status}`, answer.status, body?.error?.code ?? null);
	}
	return JSON.parse(answer.body) as T;
}

/** After a lookup: it answered (so it works), or it can't be used at all (so it's down until it answers again). */
function noted<T>(lookup: Promise<T>): Promise<T> {
	lookup.then(
		() => {
			if (moduleStatus.state !== 'works') void checkOwnModule();
		},
		(e) => {
			if (e instanceof ModuleDown) down(e.message);
		}
	);
	return lookup;
}

/**
 * Asks your own module for [path] (a search, a page, lyrics: `docs/implementation/module-api.md`);
 * throws a [ModuleError] when it answers with an error, or another error when it can't be used.
 */
export function askOwnModule<T>(path: string): Promise<T> {
	const module = activeModule();
	if (!module) return Promise.reject(new Error('no module of your own switched on'));
	return noted(get<T>(module, path, CATALOG_TIMEOUT_MS));
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
	if (desktopApp()?.moduleRequest) {
		saved.desktopModuleOn = false;
		void checkOwnModule();
		return;
	}
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
	const lookup = noted(get<{ url: string; expiresAt: number }>(module, `songs/${encodeURIComponent(songId)}/stream`));
	links.set(songId, lookup);
	lookup.catch(() => links.delete(songId));
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

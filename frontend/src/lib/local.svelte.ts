// The Local tab's settings on this device, and whether it has anything to show.
import { getLocalLibraries } from './api';
import { nativeBridge } from './native';
import type { LocalGroup, LocalLibrarySummary } from './protocol.gen';
import { read, saved, write } from './storage';

export const local = $state({
	/** Show the music on the network (a Raspberry Pi's). */
	network: saved.localNetwork,
	/** The app shows the music on this phone (in rooms on this phone). */
	phone: nativeBridge?.phoneMusic?.() ?? false,
	/** The libraries this host has that are switched on here; null until asked. */
	libraries: null as LocalLibrarySummary[] | null
});

export type LocalChip = 'albums' | 'artists' | 'songs' | 'genres' | 'folders';
export const LOCAL_CHIPS: LocalChip[] = ['albums', 'artists', 'songs', 'genres', 'folders'];

type Place = { chip: LocalChip; query: string; library: string | null; scrollTop: number; songsShown: number };
const START: Place = { chip: 'albums', query: '', library: null, scrollTop: 0, songsShown: 0 };

function savedPlace(): Place {
	try {
		const p = JSON.parse(read('ytmp.localPlace') ?? 'null') as Partial<Place> | null;
		if (!p || !LOCAL_CHIPS.includes(p.chip as LocalChip)) return { ...START };
		return {
			chip: p.chip as LocalChip,
			query: typeof p.query === 'string' ? p.query : '',
			library: typeof p.library === 'string' ? p.library : null,
			scrollTop: typeof p.scrollTop === 'number' ? p.scrollTop : 0,
			songsShown: typeof p.songsShown === 'number' ? p.songsShown : 0
		};
	} catch {
		return { ...START };
	}
}

/**
 * Where you were in the Local tab: the library, the chip, the search, how far you scrolled and
 * how many songs were loaded. Back always returns there; with [remember] (Settings → Local
 * music) the tab opens there too, also after the app was closed.
 */
export const localPlace = $state({ remember: read('ytmp.localRemember') !== '0', ...(read('ytmp.localRemember') !== '0' ? savedPlace() : START) });

$effect.root(() => {
	$effect(() => {
		const { remember, ...place } = localPlace;
		if (remember) write('ytmp.localPlace', JSON.stringify(place));
	});
});

export function setLocalRemember(on: boolean): void {
	localPlace.remember = on;
	write('ytmp.localRemember', on ? '1' : '0');
}

/** The Local tab from the start: albums, no search, at the top. */
export function forgetLocalPlace(): void {
	Object.assign(localPlace, START);
}

export function setLocalNetwork(on: boolean): void {
	local.network = on;
	saved.localNetwork = on;
	void loadLocal();
}

/** Switches the music on this phone on or off (Android asks once); null, or why it can't. */
export function setPhoneMusic(on: boolean): Promise<string | null> {
	const bridge = nativeBridge;
	if (!bridge?.setPhoneMusic) return Promise.resolve('Only in the app');
	return new Promise((resolve) => {
		window.__ytmpNative = {
			...window.__ytmpNative,
			onPhoneMusic: (now, error) => {
				local.phone = now;
				void loadLocal(true);
				resolve(error);
			}
		};
		bridge.setPhoneMusic!(on);
	});
}

/** Asks the host which local music it has (the bottom bar shows Local when there's some). */
export async function loadLocal(fresh = false): Promise<void> {
	try {
		const all = (await getLocalLibraries(fresh)).libraries;
		local.libraries = all.filter((l) => (l.kind === 'phone' ? true : local.network));
	} catch {
		local.libraries = [];
	}
}

const inside = (folder: string, path: string) => path === '' || folder === path || folder.startsWith(path + '/');

/** The folders right inside [path] ("" is the top) with songs in them or deeper, and how many. */
export function subfolders(folders: LocalGroup[], path: string): { path: string; name: string; songs: number }[] {
	const found = new Map<string, number>();
	for (const folder of folders) {
		if (folder.name === path || !inside(folder.name, path)) continue;
		const name = folder.name.slice(path ? path.length + 1 : 0).split('/')[0];
		found.set(name, (found.get(name) ?? 0) + folder.songs.length);
	}
	return [...found].map(([name, songs]) => ({ path: path ? `${path}/${name}` : name, name, songs }));
}

/** Every song in folder [path] and the folders inside it, as places in the library's songs. */
export function songsUnder(folders: LocalGroup[], path: string): number[] {
	return folders.filter((f) => inside(f.name, path)).flatMap((f) => f.songs);
}

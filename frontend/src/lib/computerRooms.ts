// Nearby rooms in the desktop app: public rooms phones on this computer's network host. The
// app's own host finds them with mDNS (desktop/host: NearbyRooms, `/api/desktop/rooms`), like
// the phone app does (android/.../Nearby.kt); the home page lists them like the phone's.
import type { NearbyRoom } from './native';
import { desktopApp } from './ownModule.svelte';

/** The desktop app can list and open nearby rooms (older apps can't). */
export const hasComputerRooms = (): boolean => !!(desktopApp()?.host && desktopApp()?.openNearbyRoom);

async function computerRooms(): Promise<NearbyRoom[]> {
	const response = await fetch(`${desktopApp()?.host}/api/desktop/rooms`);
	if (!response.ok) throw new Error(`this computer's host answered ${response.status}`);
	return (await response.json()) as NearbyRoom[];
}

/** Calls [show] with the rooms now and again every few seconds, until the returned function is called. */
export function watchComputerRooms(show: (rooms: NearbyRoom[]) => void): () => void {
	if (!hasComputerRooms()) return () => {};
	let stopped = false;
	async function look() {
		try {
			const rooms = await computerRooms();
			if (!stopped) show(rooms);
		} catch {
			// The host restarts with the app's watcher; next time.
		}
	}
	void look();
	const timer = setInterval(() => !stopped && void look(), ROOMS_EVERY_MS);
	return () => {
		stopped = true;
		clearInterval(timer);
	};
}

/** Opens [room] in the app's window (the app checks its host found it). */
export async function openComputerRoom(room: NearbyRoom): Promise<void> {
	await desktopApp()?.openNearbyRoom?.(room.url, room.code);
}

const ROOMS_EVERY_MS = 3_000;

// The Local tab's settings on this device, and whether it has anything to show.
import { getLocalLibraries } from './api';
import type { LocalLibrarySummary } from './protocol.gen';
import { saved } from './storage';

export const local = $state({
	/** Show the music on the network (a Raspberry Pi's). */
	network: saved.localNetwork,
	/** The libraries this host has that are switched on here; null until asked. */
	libraries: null as LocalLibrarySummary[] | null
});

export function setLocalNetwork(on: boolean): void {
	local.network = on;
	saved.localNetwork = on;
	void loadLocal();
}

/** Asks the host which local music it has (the bottom bar shows Local when there's some). */
export async function loadLocal(): Promise<void> {
	try {
		const all = (await getLocalLibraries()).libraries;
		local.libraries = all.filter((l) => l.kind === 'phone' || local.network);
	} catch {
		local.libraries = [];
	}
}

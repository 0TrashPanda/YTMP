// The Local tab's settings on this device, and whether it has anything to show.
import { getLocalLibraries } from './api';
import { nativeBridge } from './native';
import type { LocalLibrarySummary } from './protocol.gen';
import { saved } from './storage';

export const local = $state({
	/** Show the music on the network (a Raspberry Pi's). */
	network: saved.localNetwork,
	/** The app shows the music on this phone (in rooms on this phone). */
	phone: nativeBridge?.phoneMusic?.() ?? false,
	/** The libraries this host has that are switched on here; null until asked. */
	libraries: null as LocalLibrarySummary[] | null
});

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

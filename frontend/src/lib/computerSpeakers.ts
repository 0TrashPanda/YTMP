// Speakers near this computer (the desktop app): Chromecasts, Sonos speakers, MPDs, amplifier
// zones and YouTube TVs on this computer's network, which a server can't reach. The app's own
// host (desktop/host: DesktopHost, `/api/desktop/nearby`) finds them, follows the room itself
// and plays it there, like the phone app does (docs/features/playback-and-outputs.md#host-outputs).
// Play on shows them under *Near this computer*, the same way as the phone's.
import { onComputer } from './computer.svelte';
import type { NearbyState } from './native';
import { desktopApp } from './ownModule.svelte';

interface HostNearby extends NearbyState {
	notices: { id: number; text: string }[];
	lastNotice: number;
}

async function ask(path: string, body?: unknown): Promise<HostNearby> {
	const host = desktopApp()?.host;
	if (!host) throw new Error('Only in the desktop app');
	const response = await fetch(host + path, {
		method: body === undefined ? 'GET' : 'POST',
		headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
		body: body === undefined ? undefined : JSON.stringify(body)
	});
	const answer = await response.json().catch(() => null);
	if (!response.ok) throw new Error(answer?.error?.message ?? `this computer's host answered ${response.status}`);
	return answer as HostNearby;
}

const asState = (answer: HostNearby): NearbyState => ({ devices: answer.devices, shown: [], playing: answer.playing });

/** The desktop app with a host that finds speakers (older apps don't). */
export const hasComputerSpeakers = (): boolean => onComputer();

/** The speakers now, or null when the host can't be reached. */
export async function computerSpeakers(): Promise<NearbyState | null> {
	try {
		return asState(await ask('/api/desktop/nearby'));
	} catch {
		return null;
	}
}

/** Plays the room on [deviceId] from this computer; the new state. */
export async function playOnComputerSpeaker(deviceId: string, room: { hostUrl: string; code: string; guestToken: string }): Promise<NearbyState> {
	return asState(await ask('/api/desktop/nearby/play', { deviceId, ...room }));
}

export async function stopComputerSpeaker(deviceId: string): Promise<NearbyState> {
	return asState(await ask('/api/desktop/nearby/stop', { deviceId }));
}

/** [volume]: 0–1. */
export async function setComputerSpeakerVolume(deviceId: string, volume: number): Promise<NearbyState> {
	return asState(await ask('/api/desktop/nearby/volume', { deviceId, volume }));
}

/**
 * Shows the speakers' problems (another app took one, one went off the network) with [show],
 * looking every few seconds, until the returned function is called. Only new ones.
 */
export function watchComputerSpeakerNotices(show: (text: string) => void): () => void {
	if (!hasComputerSpeakers()) return () => {};
	let last: number | null = null;
	let stopped = false;
	async function look() {
		try {
			const answer = await ask(`/api/desktop/nearby?after=${last ?? Number.MAX_SAFE_INTEGER}`);
			if (last !== null) for (const notice of answer.notices) show(notice.text);
			last = answer.lastNotice;
		} catch {
			// The host restarts with the app's watcher; next time.
		}
	}
	void look();
	const timer = setInterval(() => !stopped && void look(), NOTICES_EVERY_MS);
	return () => {
		stopped = true;
		clearInterval(timer);
	};
}

const NOTICES_EVERY_MS = 5_000;

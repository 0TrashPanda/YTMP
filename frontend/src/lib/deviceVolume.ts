import { nativeBridge } from './native';
import type { Participant } from './protocol.gen';

/**
 * Keeps this device's volume in step with the room, so your other devices can see and set it
 * (docs/features/playback-and-outputs.md#your-devices). The app's player does this itself
 * (android/.../RemoteVolume.kt); this is for the browser's.
 */
export class DeviceVolume {
	/** [Participant.volumeChanges] as last seen, for [seenFor]. */
	private seen = 0;
	private seenFor: string | null = null;

	/**
	 * What to do now that [me] (this device in the room) changed, with [volume] the volume here:
	 * `apply` the one another device of yours set, or `report` this one when the room has another.
	 */
	update(me: Participant | null, volume: number): { apply: number } | { report: number } | null {
		// Only devices with an account have devices of their own.
		if (!me?.accountId) return null;
		if (this.seenFor !== me.id) {
			// The first time (also after a reload): what's here counts.
			this.seenFor = me.id;
			this.seen = me.volumeChanges;
		} else if (me.volumeChanges !== this.seen) {
			this.seen = me.volumeChanges;
			if (me.volume !== null && me.volume !== volume) return { apply: me.volume };
		}
		return me.volume === volume ? null : { report: volume };
	}
}

/** What this device is called in the room ([Participant.device]): the phone's name in the app. */
export function deviceName(): string {
	return nativeBridge?.deviceName?.() || browserDeviceName(navigator.userAgent);
}

/** A name for this device, so you can tell yours apart: "Firefox on Windows", "Safari on iPhone". */
export function browserDeviceName(userAgent: string): string {
	const browser = /Edg\//.test(userAgent)
		? 'Edge'
		: /OPR\/|Opera/.test(userAgent)
			? 'Opera'
			: /Firefox\/|FxiOS/.test(userAgent)
				? 'Firefox'
				: /Chrome\/|CriOS/.test(userAgent)
					? 'Chrome'
					: /Safari\//.test(userAgent)
						? 'Safari'
						: 'Browser';
	const system = /iPhone/.test(userAgent)
		? 'iPhone'
		: /iPad/.test(userAgent)
			? 'iPad'
			: /Android/.test(userAgent)
				? 'Android'
				: /CrOS/.test(userAgent)
					? 'ChromeOS'
					: /Windows/.test(userAgent)
						? 'Windows'
						: /Mac OS X|Macintosh/.test(userAgent)
							? 'Mac'
							: /Linux/.test(userAgent)
								? 'Linux'
								: null;
	return system ? `${browser} on ${system}` : browser;
}

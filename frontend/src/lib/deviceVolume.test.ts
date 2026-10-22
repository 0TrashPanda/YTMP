import { describe, expect, it } from 'vitest';
import { browserDeviceName, DeviceVolume } from './deviceVolume';
import type { Participant } from './protocol.gen';

const me = (volume: number | null, volumeChanges: number, accountId: string | null = 'anna@x') =>
	({ id: 'p1', accountId, volume, volumeChanges }) as unknown as Participant;

describe('DeviceVolume', () => {
	it('says the volume here when the room has another one', () => {
		const v = new DeviceVolume();
		expect(v.update(me(null, 0), 0.8)).toEqual({ report: 0.8 });
		// Even when another device set it before this page was open: what's here counts.
		expect(new DeviceVolume().update(me(0.3, 4), 0.8)).toEqual({ report: 0.8 });
		expect(new DeviceVolume().update(me(0.8, 4), 0.8)).toBeNull();
	});

	it('turns to a volume set from another device', () => {
		const v = new DeviceVolume();
		v.update(me(0.8, 0), 0.8);
		expect(v.update(me(0.8, 0), 0.8)).toBeNull();
		expect(v.update(me(0.4, 1), 0.8)).toEqual({ apply: 0.4 });
		// Its own report coming back is no change from elsewhere.
		expect(v.update(me(0.5, 1), 0.5)).toBeNull();
		// Changed here since: the room hears it again (also when a report got lost).
		expect(v.update(me(0.5, 1), 0.6)).toEqual({ report: 0.6 });
	});

	it('does nothing for a guest', () => {
		expect(new DeviceVolume().update(me(null, 0, null), 0.8)).toBeNull();
		expect(new DeviceVolume().update(null, 0.8)).toBeNull();
	});
});

describe('browserDeviceName', () => {
	it('names the browser and the system', () => {
		expect(browserDeviceName('Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0')).toBe('Firefox on Windows');
		expect(
			browserDeviceName('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15')
		).toBe('Safari on Mac');
		expect(
			browserDeviceName('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1')
		).toBe('Safari on iPhone');
		expect(
			browserDeviceName('Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36')
		).toBe('Chrome on Android');
		expect(
			browserDeviceName('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0')
		).toBe('Edge on Windows');
		expect(browserDeviceName('Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36')).toBe('Chrome on Linux');
	});
});

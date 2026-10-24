import { beforeEach, describe, expect, it, vi } from 'vitest';

// The account, as reached through listening.svelte.ts: a PUT merges keys, null removes one.
let account: Record<string, unknown> = {};
let loggedIn = true;
vi.mock('./listening.svelte', () => ({
	listening: {
		get available() {
			return loggedIn;
		},
		load: async () => {},
		call: async (_path: string, method = 'GET', body?: Record<string, unknown>) => {
			if (method === 'GET') return structuredClone(account);
			account = Object.fromEntries(Object.entries({ ...account, ...body }).filter(([, v]) => v !== null));
			return account;
		}
	}
}));

// This browser's storage.
const stored = new Map<string, string>();
vi.stubGlobal('localStorage', {
	getItem: (key: string) => stored.get(key) ?? null,
	setItem: (key: string, value: string) => void stored.set(key, value),
	removeItem: (key: string) => void stored.delete(key)
});

beforeEach(() => {
	vi.resetModules();
	vi.useFakeTimers();
	stored.clear();
	account = {};
	loggedIn = true;
});

const start = async () => {
	const module = await import('./settings.svelte');
	await module.load();
	await vi.advanceTimersByTimeAsync(1000);
	return module.settings;
};

describe('settings', () => {
	it('follow the account, and a change goes there', async () => {
		account = { tabOrder: 'related' };
		const settings = await start();
		expect(settings.tabOrder.value).toBe('related');
		settings.imageQuality.set('low');
		await vi.advanceTimersByTimeAsync(1000);
		expect(account).toEqual({ tabOrder: 'related', imageQuality: 'low' });
	});

	it('can be kept different on this device', async () => {
		account = { imageQuality: 'high' };
		const settings = await start();
		settings.imageQuality.keepHere(true);
		settings.imageQuality.set('low');
		await vi.advanceTimersByTimeAsync(1000);
		expect(settings.imageQuality.value).toBe('low');
		expect(settings.imageQuality.account).toBe('high');
		expect(account).toEqual({ imageQuality: 'high' });
		settings.imageQuality.keepHere(false);
		expect(settings.imageQuality.value).toBe('high');
	});

	it("move this browser's old values onto the account, or keep them here when the account has others", async () => {
		account = { nextPlays: true };
		localStorage.setItem('ytmp.nextPlays', '0');
		localStorage.setItem('ytmp.videoArt', 'fit');
		const settings = await start();
		expect(account).toEqual({ nextPlays: true, videoArt: 'fit' });
		expect(settings.videoArt.onlyHere).toBe(false);
		expect(settings.nextPlays.value).toBe(false);
		expect(settings.nextPlays.onlyHere).toBe(true);
		expect(localStorage.getItem('ytmp.nextPlays')).toBeNull();
	});

	it('without an account stay in this browser', async () => {
		loggedIn = false;
		localStorage.setItem('ytmp.pageSize', '1000');
		const settings = await start();
		expect(settings.pageSize.value).toBe(1000);
		expect(settings.pageSize.onlyHere).toBe(false);
		settings.pageSize.set(100);
		expect(settings.pageSize.value).toBe(100);
		expect(account).toEqual({});
	});
});

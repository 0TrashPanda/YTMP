import { beforeEach, describe, expect, it, vi } from 'vitest';

// This browser's storage.
const stored = new Map<string, string>();
vi.stubGlobal('localStorage', {
	getItem: (key: string) => stored.get(key) ?? null,
	setItem: (key: string, value: string) => void stored.set(key, value),
	removeItem: (key: string) => void stored.delete(key)
});

// The module: answering, or not reachable.
let answering = true;
const fetchMock = vi.fn(async (url: URL | string) => {
	if (!answering) throw new TypeError('Failed to fetch');
	const path = String(url);
	const body = path.endsWith('/info') ? { name: 'ytm', version: '0.1.0' } : { url: 'https://youtube/song', expiresAt: Date.now() + 3_600_000 };
	return new Response(JSON.stringify(body), { status: 200 });
});
vi.stubGlobal('fetch', fetchMock);

async function load() {
	return import('./ownModule.svelte');
}

beforeEach(() => {
	vi.resetModules();
	vi.useFakeTimers();
	stored.clear();
	fetchMock.mockClear();
	answering = true;
});

describe('your own module', () => {
	it('is off when none is set up, and an old saved one counts as on', async () => {
		let m = await load();
		await m.checkOwnModule();
		expect(m.moduleStatus.state).toBe('off');

		vi.resetModules();
		stored.set('ytmp.ownModule', JSON.stringify({ url: 'http://localhost:8401', key: 'k' }));
		m = await load();
		await m.checkOwnModule();
		expect(m.moduleStatus.state).toBe('works');
		expect(m.moduleStatus.text).toBe('ytm module 0.1.0');
	});

	it('asks again every minute while it doesn’t answer, and is skipped meanwhile', async () => {
		stored.set('ytmp.ownModule', JSON.stringify({ url: 'http://localhost:8401', key: 'k', on: true }));
		answering = false;
		const m = await load();
		await m.checkOwnModule();
		expect(m.moduleStatus.state).toBe('down');
		expect(m.moduleUsable()).toBe(false);

		await vi.advanceTimersByTimeAsync(m.RETRY_MS);
		expect(fetchMock).toHaveBeenCalledTimes(2);
		expect(m.moduleStatus.state).toBe('down');

		answering = true;
		await vi.advanceTimersByTimeAsync(m.RETRY_MS);
		expect(m.moduleStatus.state).toBe('works');
		expect(m.moduleUsable()).toBe(true);
		// Working again: no more checks.
		await vi.advanceTimersByTimeAsync(m.RETRY_MS * 3);
		expect(fetchMock).toHaveBeenCalledTimes(3);
	});

	it('a lookup that can’t reach it marks it down', async () => {
		stored.set('ytmp.ownModule', JSON.stringify({ url: 'http://localhost:8401', key: 'k', on: true }));
		const m = await load();
		await m.checkOwnModule();
		answering = false;
		await expect(m.ownStreamUrl('ytm:abc')).rejects.toThrow("can't reach");
		expect(m.moduleStatus.state).toBe('down');
	});

	it('switched off: kept, not used, no message and no checks', async () => {
		stored.set('ytmp.ownModule', JSON.stringify({ url: 'http://localhost:8401', key: 'k', on: true }));
		answering = false;
		const m = await load();
		await m.checkOwnModule();
		expect(m.moduleStatus.state).toBe('down');

		m.switchOffOwnModule();
		expect(m.moduleStatus.state).toBe('off');
		expect(JSON.parse(stored.get('ytmp.ownModule')!)).toEqual({ url: 'http://localhost:8401', key: 'k', on: false });
		await vi.advanceTimersByTimeAsync(m.RETRY_MS * 3);
		expect(fetchMock).toHaveBeenCalledTimes(1);
	});
});

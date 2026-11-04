import { beforeEach, describe, expect, it, vi } from 'vitest';

// The account, as reached through listening.svelte.ts: a PUT merges keys, null removes one.
let account: Record<string, unknown> = {};
vi.mock('./listening.svelte', () => ({
	listening: {
		available: true,
		load: async () => {},
		call: async (_path: string, method = 'GET', body?: Record<string, unknown>) => {
			if (method === 'GET') return structuredClone(account);
			account = Object.fromEntries(Object.entries({ ...account, ...body }).filter(([, v]) => v !== null));
			return account;
		}
	}
}));

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
});

const start = async () => {
	const settings = await import('./settings.svelte');
	await settings.load();
	await vi.advanceTimersByTimeAsync(1000);
	return import('./gestures.svelte');
};

describe('gestures', () => {
	it('tap in lists adds to the queue at first', async () => {
		const { gestures } = await start();
		expect(gestures.list.tap).toBe('addToQueue');
	});

	it('a list saved before keeps its choices, but the defaults of then (Tap = Play next, Right = Add to queue) follow the new ones', async () => {
		account = { gesturesList: { tap: 'playNext', right: 'addToQueue', left: 'radio', hold: 'menu' } };
		const { gestures } = await start();
		expect(gestures.list).toEqual({ tap: 'addToQueue', right: 'playNext', left: 'radio', hold: 'menu' });
	});

	it('a list saved with the version before keeps a Tap of Add to queue, and its Right follows the new default', async () => {
		account = { gesturesList: { v: 2, tap: 'addToQueue', right: 'addToQueue', left: 'playNow', hold: 'menu' } };
		const { gestures } = await start();
		expect(gestures.list).toEqual({ tap: 'addToQueue', right: 'playNext', left: 'playNow', hold: 'menu' });
	});

	it('swiping right in lists plays next, and Autoplay adds on tap, plays now right and removes left', async () => {
		const { gestures } = await start();
		expect(gestures.list.right).toBe('playNext');
		expect(gestures.autoplay).toEqual({ tap: 'addToQueue', right: 'playNow', left: 'remove', hold: 'menu' });
	});

	it('a list saved before with another Tap keeps it', async () => {
		account = { gesturesList: { tap: 'playNow', right: 'addToQueue', left: 'playNow', hold: 'menu' } };
		const { gestures } = await start();
		expect(gestures.list.tap).toBe('playNow');
	});

	it('Play next chosen now stays', async () => {
		const { gestures, setGesture } = await start();
		setGesture('list', 'tap', 'playNext');
		expect(gestures.list.tap).toBe('playNext');
		await vi.advanceTimersByTimeAsync(1000);
		expect(account.gesturesList).toMatchObject({ v: 3, tap: 'playNext' });
	});

	it('Autoplay keeps what Up next had, until it is set', async () => {
		account = { gesturesQueue: { tap: 'playNow', right: 'addToQueue', left: 'remove', hold: 'radio' } };
		const { gestures } = await start();
		// What were the defaults then follow Autoplay's own; the rest is kept.
		expect(gestures.autoplay).toEqual({ tap: 'addToQueue', right: 'addToQueue', left: 'remove', hold: 'radio' });
	});

	it('the queue and Autoplay change apart', async () => {
		const { gestures, setGesture } = await start();
		setGesture('queue', 'left', 'playNext');
		setGesture('autoplay', 'left', 'addToQueue');
		expect(gestures.queue.left).toBe('playNext');
		expect(gestures.autoplay.left).toBe('addToQueue');
		expect(gestures.autoplay.right).toBe('playNow');
	});

	it("changing the queue doesn't change Autoplay, also when it followed the queue", async () => {
		account = { gesturesQueue: { tap: 'playNow', right: 'addToQueue', left: 'remove', hold: 'menu' } };
		const { gestures, setGesture, resetGestures } = await start();
		setGesture('queue', 'right', 'radio');
		expect(gestures.queue.right).toBe('radio');
		expect(gestures.autoplay.right).toBe('addToQueue');
		resetGestures('queue');
		expect(gestures.queue.right).toBe('playNext');
		expect(gestures.autoplay.right).toBe('addToQueue');
	});

	it("Autoplay's Defaults are its own, not the queue's", async () => {
		account = { gesturesQueue: { tap: 'playNow', right: 'addToQueue', left: 'remove', hold: 'menu' } };
		const { gestures, resetGestures, DEFAULTS } = await start();
		resetGestures('autoplay');
		expect(gestures.autoplay).toEqual(DEFAULTS.autoplay);
		expect(gestures.queue.right).toBe('addToQueue');
	});
});

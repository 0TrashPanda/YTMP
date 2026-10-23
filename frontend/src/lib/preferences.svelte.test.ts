import { beforeEach, describe, expect, it, vi } from 'vitest';

// The account, as reached through listening.svelte.ts: a PUT merges keys, null removes one.
let account: Record<string, unknown> = {};
const puts: Record<string, unknown>[] = [];
vi.mock('./listening.svelte', () => ({
	listening: {
		available: true,
		load: async () => {},
		call: async (_path: string, method = 'GET', body?: Record<string, unknown>) => {
			if (method === 'GET') return structuredClone(account);
			puts.push(body!);
			account = Object.fromEntries(Object.entries({ ...account, ...body }).filter(([, v]) => v !== null));
			return account;
		}
	}
}));

beforeEach(() => {
	vi.resetModules();
	account = {};
	puts.length = 0;
});

describe('preferences', () => {
	it("takes the account's, and puts up what only this browser has", async () => {
		account = { songMenu: { items: ['radio'] } };
		const { preferences } = await import('./preferences.svelte');
		preferences.set('soloHidden', ['karaoke']);
		expect(puts).toEqual([]);
		await preferences.load();
		expect(preferences.onAccount).toBe(true);
		expect(preferences.get('songMenu')).toEqual({ items: ['radio'] });
		expect(account).toEqual({ songMenu: { items: ['radio'] }, soloHidden: ['karaoke'] });
	});

	it('sends a few changes in a row once, only the keys that changed', async () => {
		vi.useFakeTimers();
		const { preferences } = await import('./preferences.svelte');
		await preferences.load();
		preferences.set('songMenu', { items: ['a'] });
		preferences.set('songMenu', { items: ['b'] });
		preferences.set('other', undefined);
		await vi.advanceTimersByTimeAsync(1000);
		expect(puts).toEqual([{ songMenu: { items: ['b'] }, other: null }]);
		vi.useRealTimers();
	});
});

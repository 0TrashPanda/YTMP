import { describe, expect, it } from 'vitest';

describe('polyfills', () => {
	it('copies plain data deeply where structuredClone is missing (Chrome 90)', async () => {
		const original = globalThis.structuredClone;
		// @ts-expect-error removed to load the stand-in
		delete globalThis.structuredClone;
		try {
			await import('./polyfills');
			const state = { a: [1, { b: 'x' }], c: null, d: { e: true } };
			const copied = globalThis.structuredClone(state);
			expect(copied).toEqual(state);
			expect(copied.a).not.toBe(state.a);
			expect(copied.a[1]).not.toBe(state.a[1]);
			expect(copied.d).not.toBe(state.d);
		} finally {
			globalThis.structuredClone = original;
		}
	});
});

import { beforeEach, describe, expect, it, vi } from 'vitest';

// The two settings, as plain values.
const values: Record<string, Record<string, unknown>> = { list: {}, queue: {} };
const setting = (key: string) => ({
	get value() {
		return values[key];
	},
	set: (v: Record<string, unknown>) => void (values[key] = v),
	reset: () => void (values[key] = {})
});
vi.mock('./settings.svelte', () => ({ settings: { gesturesList: setting('list'), gesturesQueue: setting('queue') } }));

const { gestures, setGesture, setPageTap, resetGestures } = await import('./gestures.svelte');

beforeEach(() => {
	values.list = {};
	values.queue = {};
});

describe('tap on album and playlist pages', () => {
	it('plays from that song by default', () => {
		expect(gestures.pageTap).toBe('playFromHere');
		expect(gestures.list.tap).toBe('playNext');
	});

	it('is kept when a list gesture changes, and reset with them', () => {
		setPageTap('menu');
		setGesture('list', 'tap', 'playNow');
		expect(gestures.pageTap).toBe('menu');
		expect(gestures.list.tap).toBe('playNow');
		resetGestures('list');
		expect(gestures.pageTap).toBe('playFromHere');
	});

	it('ignores what it can’t be', () => {
		values.list = { pageTap: 'remove' };
		expect(gestures.pageTap).toBe('playFromHere');
		// Play from here is only for those pages.
		values.list = { tap: 'playFromHere' };
		expect(gestures.list.tap).toBe('playNext');
	});
});

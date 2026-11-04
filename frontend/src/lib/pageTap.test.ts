import { describe, expect, it, vi } from 'vitest';

vi.mock('./settings.svelte', () => ({ settings: {} }));

const { pageTap } = await import('./gestures.svelte');

type State = Parameters<typeof pageTap>[0];
const room = (queued: number, current: 'none' | 'playing' | 'paused'): State =>
	({
		queue: Array.from({ length: queued }, () => ({})),
		nowPlaying: current === 'none' ? null : {},
		playback: { playing: current === 'playing' }
	}) as unknown as State;

describe('tapping a song on an album or playlist page', () => {
	it('plays it from there in an empty room', () => {
		expect(pageTap(room(0, 'none'), true, 'playNext')).toBe('playFromHere');
		// A paused or finished song with nothing after it: empty too.
		expect(pageTap(room(0, 'paused'), true, 'playNext')).toBe('playFromHere');
	});

	it('does what a tap in lists does when something is queued or playing', () => {
		expect(pageTap(room(0, 'playing'), true, 'playNext')).toBe('playNext');
		expect(pageTap(room(2, 'paused'), true, 'addToQueue')).toBe('addToQueue');
		expect(pageTap(null, true, 'playNext')).toBe('playNext');
	});

	it('does what a tap in lists does with the setting off', () => {
		expect(pageTap(room(0, 'none'), false, 'playNext')).toBe('playNext');
	});
});

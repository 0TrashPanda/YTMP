import { describe, expect, it } from 'vitest';
import { pickSource, type SourceChoice } from './songSources';
import type { SongSource } from './storage';

const choice = (c: Partial<SourceChoice> = {}): SourceChoice => ({
	tried: new Set(),
	youtube: true,
	directIsYoutube: true,
	moduleUsable: true,
	directFails: false,
	...c
});
const LOCAL_FIRST: SongSource[] = ['own', 'host', 'direct'];

describe('pickSource', () => {
	it('follows the chosen order', () => {
		expect(pickSource(['direct', 'own', 'host'], choice())).toBe('direct');
		expect(pickSource(LOCAL_FIRST, choice())).toBe('own');
	});

	it('takes the next one after a failure, and nothing once all were tried', () => {
		expect(pickSource(LOCAL_FIRST, choice({ tried: new Set(['own']) }))).toBe('host');
		expect(pickSource(LOCAL_FIRST, choice({ tried: new Set(['own', 'host']) }))).toBe('direct');
		expect(pickSource(LOCAL_FIRST, choice({ tried: new Set(['own', 'host', 'direct']) }))).toBeNull();
	});

	it('passes over your module while it is off or down, and for songs it does not know', () => {
		expect(pickSource(LOCAL_FIRST, choice({ moduleUsable: false }))).toBe('host');
		expect(pickSource(LOCAL_FIRST, choice({ youtube: false }))).toBe('host');
	});

	it('passes over the room’s YouTube links once they failed here, but not the host’s own files', () => {
		expect(pickSource(['direct', 'own', 'host'], choice({ directFails: true }))).toBe('own');
		expect(pickSource(['direct', 'own', 'host'], choice({ directFails: true, directIsYoutube: false }))).toBe('direct');
	});

	it('ends with the host when the others can’t be used', () => {
		expect(pickSource(['direct', 'own', 'host'], choice({ tried: new Set(['direct']), moduleUsable: false }))).toBe('host');
	});
});

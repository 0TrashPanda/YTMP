import { describe, expect, it } from 'vitest';
import { formatTime, fuzzyFind } from './format';

describe('formatTime', () => {
	it('is minutes and seconds', () => {
		expect(formatTime(0)).toBe('0:00');
		expect(formatTime(65_400)).toBe('1:05');
		expect(formatTime(-5)).toBe('0:00');
		expect(formatTime(3_725_000)).toBe('62:05');
	});
});

describe('fuzzyFind', () => {
	const items = ['Liked music', 'Road trip', 'Café del Mar', 'Daft Punk – Discovery'];
	const find = (query: string) => fuzzyFind(items, query, (s) => s);

	it('finds words, starts of words, typos, accents and letters in order', () => {
		expect(find('')).toEqual(items);
		expect(find('road')).toEqual(['Road trip']);
		expect(find('disc')).toEqual(['Daft Punk – Discovery']);
		expect(find('cafe')).toEqual(['Café del Mar']);
		expect(find('discovrey')).toEqual(['Daft Punk – Discovery']);
		expect(find('rt')).toContain('Road trip');
		expect(find('zzz')).toEqual([]);
	});

	it('needs every word', () => {
		expect(find('daft discovery')).toEqual(['Daft Punk – Discovery']);
		expect(find('daft road')).toEqual([]);
	});
});

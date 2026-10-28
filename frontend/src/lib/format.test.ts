import { describe, expect, it } from 'vitest';
import { formatTime, fuzzyFind, fuzzyIndex, timeAgo, typedNumber } from './format';

describe('formatTime', () => {
	it('is minutes and seconds', () => {
		expect(formatTime(0)).toBe('0:00');
		expect(formatTime(65_400)).toBe('1:05');
		expect(formatTime(-5)).toBe('0:00');
		expect(formatTime(3_725_000)).toBe('62:05');
	});
});

describe('timeAgo', () => {
	const now = Date.UTC(2026, 9, 22, 12);
	it('is minutes, hours or days, and the date after a month', () => {
		expect(timeAgo(now - 20_000, now)).toBe('just now');
		expect(timeAgo(now - 5 * 60_000, now)).toBe('5 min ago');
		expect(timeAgo(now - 3 * 3_600_000, now)).toBe('3 h ago');
		expect(timeAgo(now - 30 * 3_600_000, now)).toBe('yesterday');
		expect(timeAgo(now - 4 * 86_400_000, now)).toBe('4 days ago');
		expect(timeAgo(now - 90 * 86_400_000, now)).toContain('2026');
	});
});

describe('fuzzyIndex', () => {
	it('searches the prepared list again and again, like fuzzyFind', () => {
		const search = fuzzyIndex(['Primo Victoria – Sabaton', 'Lungs – potsu', 'Sparta – Sabaton'], (s) => s);
		expect(search('sabatn primo')).toEqual(['Primo Victoria – Sabaton']);
		expect(search('potsu')).toEqual(['Lungs – potsu']);
		expect(search('sabaton')).toHaveLength(2);
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

describe('typedNumber', () => {
	it('keeps a typed number within its range, rounded to the step', () => {
		expect(typedNumber('42', 0, 100)).toBe(42);
		expect(typedNumber(' 7.6 ', 0, 100)).toBe(8);
		expect(typedNumber('150', 0, 100)).toBe(100);
		expect(typedNumber('-30', -500, 500)).toBe(-30);
		expect(typedNumber('-900', -500, 500)).toBe(-500);
		expect(typedNumber('1,35', 0.5, 2, 0.05)).toBe(1.35);
		expect(typedNumber('0.3', 0, 50, 0.1)).toBe(0.3);
	});

	it("is null when it isn't a number", () => {
		expect(typedNumber('', 0, 100)).toBeNull();
		expect(typedNumber('abc', 0, 100)).toBeNull();
		expect(typedNumber('1e400', 0, 100)).toBeNull();
	});
});

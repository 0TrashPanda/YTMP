import { describe, expect, it } from 'vitest';
import { matches, rowsOf, searchWords, type Node } from './settingsSearch';

describe('matching', () => {
	it('finds words by their start, in any order, ignoring case and accents', () => {
		const text = 'Skip silence Skips the quiet parts of an episode';
		expect(matches(text, searchWords('silence'))).toBe(true);
		expect(matches(text, searchWords('EPIS skip'))).toBe(true);
		expect(matches(text, searchWords('lence'))).toBe(false);
		expect(matches(text, searchWords('skip loud'))).toBe(false);
		expect(matches('Café playlist', searchWords('cafe'))).toBe(true);
	});

	it('treats curly and straight quotes alike', () => {
		expect(matches('Holding doesn’t select text', searchWords("doesn't"))).toBe(true);
	});

	it('finds nothing without words', () => {
		expect(searchWords('  · ')).toEqual([]);
		expect(matches('anything', [])).toBe(false);
	});
});

describe('rows', () => {
	const el = (tagName: string, parentElement: Node | null): Node => ({ tagName, parentElement });

	it('is the biggest part around a label that holds no other label', () => {
		// page > card > (h3, toggleA > text > label, toggleB > label)
		const page = el('DIV', null);
		const card = el('SECTION', page);
		const heading = el('H3', card);
		const toggleA = el('DIV', card);
		const textA = el('DIV', toggleA);
		const labelA = el('P', textA);
		const toggleB = el('DIV', card);
		const labelB = el('P', toggleB);
		const rows = rowsOf(page, [heading, labelA, labelB]);
		expect(rows.get(labelA)).toBe(toggleA);
		expect(rows.get(labelB)).toBe(toggleB);
		// A heading stands for its card.
		expect(rows.get(heading)).toBe(card);
	});

	it('stops at the page', () => {
		const page = el('DIV', null);
		const wrapper = el('DIV', page);
		const label = el('P', wrapper);
		const heading = el('H3', page);
		expect(rowsOf(page, [label]).get(label)).toBe(wrapper);
		expect(rowsOf(page, [heading]).get(heading)).toBe(heading);
	});
});

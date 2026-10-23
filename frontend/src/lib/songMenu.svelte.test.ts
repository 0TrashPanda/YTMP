import { describe, expect, it } from 'vitest';
import { arrange, DEFAULT_ITEMS, defaultLayout, MENU_ACTIONS, parseLayout, type MenuLayout } from './songMenu.svelte';

describe('parseLayout', () => {
	it('gives the defaults for nothing or something unreadable', () => {
		expect(parseLayout(null)).toEqual(defaultLayout());
		expect(parseLayout('not json')).toEqual(defaultLayout());
	});

	it('keeps known actions once, and adds new ones at the end', () => {
		const layout = parseLayout(JSON.stringify({ items: ['remove', 'divider', 'gone', 'remove', 'playNext'], hidden: ['like', 'gone'], buttons: ['radio', 'radio', 'nope'] }));
		expect(layout.items.slice(0, 3)).toEqual(['remove', 'divider', 'playNext']);
		expect(layout.items.filter((i) => i !== 'divider').sort()).toEqual(Object.keys(MENU_ACTIONS).sort());
		expect(layout.hidden).toEqual(['like']);
		expect(layout.buttons).toEqual(['radio', null, null]);
	});
});

describe('arrange', () => {
	const layout: MenuLayout = { items: ['divider', 'playNext', 'divider', 'divider', 'like', 'radio', 'divider', 'save', 'divider'], hidden: ['like'], buttons: ['save', 'download', null] };

	it('leaves out hidden and unavailable actions, and extra dividers', () => {
		const { buttons, list } = arrange(layout, { playNext: ['next'], like: ['like'], radio: ['radio'], save: ['save'] }, false);
		expect(buttons).toEqual([]);
		expect(list).toEqual(['next', null, 'radio', null, 'save']);
	});

	it('puts the available big buttons first, not again in the list', () => {
		const { buttons, list } = arrange(layout, { playNext: ['next'], save: ['save'] }, true);
		expect(buttons).toEqual(['save']);
		expect(list).toEqual(['next']);
	});

	it('shows every entry of an action, like several artists', () => {
		expect(arrange({ ...defaultLayout(), items: [...DEFAULT_ITEMS] }, { goArtist: ['a', 'b'] }, false).list).toEqual(['a', 'b']);
	});
});

import { describe, expect, it } from 'vitest';
import { moveIntoView } from './keepInView';

const box = (left: number, top: number, w: number, h: number) => ({ left, top, right: left + w, bottom: top + h });

describe('moveIntoView', () => {
	it('leaves a menu that fits where it is', () => {
		expect(moveIntoView(box(100, 100, 200, 300), null, 1920, 1080)).toEqual({ dx: 0, dy: 0 });
	});

	it('moves a menu off the right edge to the left', () => {
		expect(moveIntoView(box(1648, 200, 320, 300), null, 1920, 1080).dx).toBe(1920 - 8 - 1968);
	});

	it('keeps it off the left edge when wider than the room', () => {
		expect(moveIntoView(box(-20, 100, 200, 100), null, 400, 800).dx).toBe(28);
	});

	it('flips a menu that runs over the bottom above its button, with the same gap', () => {
		const button = box(500, 900, 40, 40); // 900–940
		const menu = box(480, 948, 200, 300); // 8 px under it
		expect(moveIntoView(menu, button, 1920, 1080).dy).toBe(900 - 8 - 300 - 948);
	});

	it('opens a menu at the pointer above it when there is no room below', () => {
		const at = { left: 300, right: 300, top: 1000, bottom: 1000 };
		expect(moveIntoView(box(300, 1000, 256, 400), at, 1920, 1080).dy).toBe(-400);
	});

	it('pushes it up just enough when there is no room above either', () => {
		const button = box(10, 100, 40, 40);
		expect(moveIntoView(box(10, 140, 200, 500), button, 400, 600).dy).toBe(600 - 8 - 640);
	});

	it('flips a menu that opens upward below its button when there is no room above', () => {
		const button = box(500, 40, 40, 40); // 40–80
		const menu = box(480, -268, 200, 300); // ends 8 px above it
		expect(moveIntoView(menu, button, 1920, 1080).dy).toBe(88 - -268);
	});

	it('never above the top edge, even when taller than the window', () => {
		expect(moveIntoView(box(0, 50, 100, 2000), null, 400, 800).dy).toBe(8 - 50);
	});
});

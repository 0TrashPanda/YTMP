import { describe, expect, it, vi } from 'vitest';
import { CLOSE_AFTER, swipeDown } from './swipeDown';

type Listener = (e: unknown) => void;

/** Just enough of an element: its listeners, style, scroll and parent. */
function element(parent: object | null = null, scrollTop = 0) {
	const listeners: Record<string, Listener> = {};
	return {
		parentElement: parent,
		scrollTop,
		style: {} as Record<string, string>,
		addEventListener: (type: string, f: Listener) => (listeners[type] = f),
		removeEventListener: (type: string) => delete listeners[type],
		fire: (type: string, target: object, y?: number) => listeners[type]?.({ target, touches: y === undefined ? [] : [{ clientY: y }] })
	};
}

function drag(sheet: ReturnType<typeof element>, from: object, ys: number[]) {
	sheet.fire('touchstart', from, ys[0]);
	for (const y of ys.slice(1)) sheet.fire('touchmove', from, y);
	sheet.fire('touchend', from);
}

describe('swipeDown', () => {
	it('follows the finger down and closes when let go far enough', () => {
		const sheet = element();
		const onClose = vi.fn();
		swipeDown(sheet as unknown as HTMLElement, onClose);
		sheet.fire('touchstart', sheet, 100);
		sheet.fire('touchmove', sheet, 140);
		expect(sheet.style.transform).toBe('translateY(40px)');
		sheet.fire('touchmove', sheet, 100 + CLOSE_AFTER + 20);
		sheet.fire('touchend', sheet);
		expect(onClose).toHaveBeenCalledOnce();
	});

	it('springs back after a short pull', () => {
		const sheet = element();
		const onClose = vi.fn();
		swipeDown(sheet as unknown as HTMLElement, onClose);
		drag(sheet, sheet, [100, 130]);
		expect(onClose).not.toHaveBeenCalled();
		expect(sheet.style.transform).toBe('');
	});

	it('leaves a swipe that starts upwards, or in a list scrolled down, to scrolling', () => {
		const sheet = element();
		const list = element(sheet, 50);
		const row = element(list);
		const onClose = vi.fn();
		swipeDown(sheet as unknown as HTMLElement, onClose);
		drag(sheet, row, [100, 300]);
		drag(sheet, sheet, [300, 280, 500]);
		expect(onClose).not.toHaveBeenCalled();
		list.scrollTop = 0;
		drag(sheet, row, [100, 300]);
		expect(onClose).toHaveBeenCalledOnce();
	});
});

// Holding a button for its choices (docs/features/ui.md#hold-for-choices): a long press on a
// touch screen or with the mouse, or a right-click. The click that follows a hold is swallowed,
// so the button doesn't also do its usual thing.
import type { Action } from 'svelte/action';

/** How long a press is a hold. */
export const HOLD_MS = 450;

/** [onHold] when the element is held (null: holding does nothing, the button just clicks). */
export const hold: Action<HTMLElement, (() => void) | null | undefined> = (node, initial) => {
	let onHold = initial;
	let timer: ReturnType<typeof setTimeout> | null = null;
	let start: { x: number; y: number } | null = null;
	let held = false;

	const cancel = () => {
		if (timer) clearTimeout(timer);
		timer = null;
		start = null;
	};
	const fire = () => {
		cancel();
		held = true;
		navigator.vibrate?.(10);
		onHold?.();
	};
	const down = (e: PointerEvent) => {
		if (!onHold || e.button !== 0) return;
		held = false;
		start = { x: e.clientX, y: e.clientY };
		timer = setTimeout(fire, HOLD_MS);
	};
	const move = (e: PointerEvent) => {
		// Scrolling the list isn't holding.
		if (start && Math.hypot(e.clientX - start.x, e.clientY - start.y) > 10) cancel();
	};
	const click = (e: MouseEvent) => {
		if (!held) return;
		held = false;
		e.preventDefault();
		e.stopImmediatePropagation();
	};
	const context = (e: MouseEvent) => {
		if (!onHold) return;
		e.preventDefault();
		// A long press on a phone also says "context menu": only once.
		if (held) return;
		cancel();
		held = true;
		onHold();
		// A right-click has no click after it.
		setTimeout(() => (held = false), 400);
	};

	node.addEventListener('pointerdown', down);
	node.addEventListener('pointermove', move);
	node.addEventListener('pointerup', cancel);
	node.addEventListener('pointerleave', cancel);
	node.addEventListener('pointercancel', cancel);
	node.addEventListener('click', click, true);
	node.addEventListener('contextmenu', context);
	return {
		update(next) {
			onHold = next;
		},
		destroy() {
			cancel();
			node.removeEventListener('pointerdown', down);
			node.removeEventListener('pointermove', move);
			node.removeEventListener('pointerup', cancel);
			node.removeEventListener('pointerleave', cancel);
			node.removeEventListener('pointercancel', cancel);
			node.removeEventListener('click', click, true);
			node.removeEventListener('contextmenu', context);
		}
	};
};

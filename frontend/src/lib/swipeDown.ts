// A sheet from the bottom that follows the finger down and closes when let go far enough, like
// YouTube Music's menus: `use:swipeDown={onClose}` on the sheet. A swipe that starts in a list
// scrolled down scrolls that list instead; one that starts upwards doesn't move the sheet.

/** How far (px) the sheet must be pulled down to close when let go. */
export const CLOSE_AFTER = 80;

/** Whether a touch on [target] inside [sheet] starts in something scrolled down from its top. */
export function inScrolledList(target: EventTarget | null, sheet: HTMLElement): boolean {
	for (let el = target as Element | null; el && el !== sheet; el = el.parentElement) {
		if ((el.scrollTop ?? 0) > 0) return true;
	}
	return false;
}

export function swipeDown(node: HTMLElement, onClose: () => void) {
	let close = onClose;
	let swipe: { y: number; ok: boolean } | null = null;
	let dragY = 0;

	function move(y: number) {
		dragY = y;
		node.style.transform = y ? `translateY(${y}px)` : '';
		node.style.transition = y ? 'none' : 'transform 150ms ease-out';
	}
	function start(e: TouchEvent) {
		swipe = { y: e.touches[0].clientY, ok: !inScrolledList(e.target, node) };
	}
	function touchMove(e: TouchEvent) {
		if (!swipe?.ok) return;
		const dy = e.touches[0].clientY - swipe.y;
		if (dragY === 0 && dy < 0) swipe.ok = false;
		else move(Math.max(0, dy));
	}
	function end() {
		const far = dragY > CLOSE_AFTER;
		swipe = null;
		if (far) close();
		else if (dragY) move(0);
	}

	node.addEventListener('touchstart', start, { passive: true });
	node.addEventListener('touchmove', touchMove, { passive: true });
	node.addEventListener('touchend', end);
	node.addEventListener('touchcancel', end);
	return {
		update(onClose: () => void) {
			close = onClose;
		},
		destroy() {
			node.removeEventListener('touchstart', start);
			node.removeEventListener('touchmove', touchMove);
			node.removeEventListener('touchend', end);
			node.removeEventListener('touchcancel', end);
		}
	};
}

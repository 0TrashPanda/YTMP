// A message (toast) that follows the finger down and goes away when swiped far enough, like
// Android's snackbars: `use:swipeAway={dismiss}`. A short swipe springs back; a tap is a tap.

const GONE_PX = 40;

export function swipeAway(node: HTMLElement, onGone: () => void) {
	let gone = onGone;
	let startY: number | null = null;
	let dy = 0;

	function start(event: TouchEvent) {
		startY = event.touches[0].clientY;
		dy = 0;
		node.style.transition = 'none';
	}
	function move(event: TouchEvent) {
		if (startY === null) return;
		dy = Math.max(0, event.touches[0].clientY - startY);
		node.style.transform = dy ? `translateY(${dy}px)` : '';
		node.style.opacity = dy ? String(Math.max(0.2, 1 - dy / 120)) : '';
	}
	function end() {
		if (startY === null) return;
		startY = null;
		node.style.transition = '';
		if (dy >= GONE_PX) return gone();
		node.style.transform = '';
		node.style.opacity = '';
	}

	node.addEventListener('touchstart', start, { passive: true });
	node.addEventListener('touchmove', move, { passive: true });
	node.addEventListener('touchend', end);
	node.addEventListener('touchcancel', end);
	return {
		update(next: () => void) {
			gone = next;
		},
		destroy() {
			node.removeEventListener('touchstart', start);
			node.removeEventListener('touchmove', move);
			node.removeEventListener('touchend', end);
			node.removeEventListener('touchcancel', end);
		}
	};
}

// Menus and popovers stay inside the window: `use:keepInView` on one. Placed where its own CSS puts
// it (under its button, at the pointer), then moved: left when it runs off the right edge, above
// its button when there's no room below (or below when there's none above), else as far as it
// fits; taller than the window, it scrolls inside. Again whenever its size changes (choices that
// open inside it), the window is resized or the page scrolls.

export interface Box {
	top: number;
	bottom: number;
	left: number;
	right: number;
}

/** What it's opened from: by default its positioned parent (the button's wrapper). */
export type Anchor = HTMLElement | Box | (() => HTMLElement | Box | null | undefined);

/** The gap kept to the window's edges. */
export const EDGE = 8;

/**
 * How far to move [box] (where it would be) to be inside a [width] × [height] window: flipped to
 * the other side of [anchor] when that fits, else pushed in from the edge it runs over.
 */
export function moveIntoView(box: Box, anchor: Box | null, width: number, height: number, edge = EDGE): { dx: number; dy: number } {
	let dx = 0;
	if (box.right > width - edge) dx = width - edge - box.right;
	if (box.left + dx < edge) dx = edge - box.left;

	const tall = box.bottom - box.top;
	// Opened under (or at) the anchor, or above it.
	const below = !anchor || box.top >= (anchor.top + anchor.bottom) / 2;
	let dy = 0;
	if (box.bottom > height - edge) {
		// Runs over the bottom: above the anchor, with the same gap, if that fits.
		const gap = anchor ? Math.max(0, box.top - anchor.bottom) : 0;
		const flipped = anchor && below ? anchor.top - gap - tall : null;
		dy = flipped !== null && flipped >= edge ? flipped - box.top : height - edge - box.bottom;
	} else if (box.top < edge) {
		// Runs over the top: below the anchor if that fits.
		const gap = anchor ? Math.max(0, anchor.top - box.bottom) : 0;
		const flipped = anchor && !below ? anchor.bottom + gap : null;
		dy = flipped !== null && flipped + tall <= height - edge ? flipped - box.top : edge - box.top;
	}
	if (box.top + dy < edge) dy = edge - box.top;
	return { dx, dy };
}

function toBox(a: HTMLElement | Box): Box {
	return a instanceof HTMLElement ? a.getBoundingClientRect() : a;
}

export function keepInView(node: HTMLElement, anchor?: Anchor) {
	let current = anchor;
	let dx = 0;
	let dy = 0;
	let frame = 0;

	function fit() {
		frame = 0;
		const width = document.documentElement.clientWidth || window.innerWidth;
		const height = window.innerHeight;
		node.style.maxHeight = `${height - 2 * EDGE}px`;
		node.style.maxWidth = `${width - 2 * EDGE}px`;
		node.style.overflowY = 'auto';
		const rect = node.getBoundingClientRect();
		// Where it would be without the move made last time.
		const box = { top: rect.top - dy, bottom: rect.bottom - dy, left: rect.left - dx, right: rect.right - dx };
		const given = typeof current === 'function' ? current() : current;
		const from = given ?? (node.offsetParent instanceof HTMLElement && node.offsetParent !== document.body ? node.offsetParent : null);
		({ dx, dy } = moveIntoView(box, from ? toBox(from) : null, width, height));
		node.style.transform = dx || dy ? `translate(${Math.round(dx)}px, ${Math.round(dy)}px)` : '';
	}
	const later = (e?: Event) => {
		if (e?.target instanceof Node && node.contains(e.target)) return; // its own list scrolled
		if (!frame) frame = requestAnimationFrame(fit);
	};

	fit();
	const resize = new ResizeObserver(() => later());
	resize.observe(node);
	window.addEventListener('resize', later);
	window.addEventListener('scroll', later, true);
	return {
		update(next?: Anchor) {
			current = next;
			later();
		},
		destroy() {
			resize.disconnect();
			window.removeEventListener('resize', later);
			window.removeEventListener('scroll', later, true);
			if (frame) cancelAnimationFrame(frame);
		}
	};
}

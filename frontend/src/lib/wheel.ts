/**
 * Scrolling over a slider moves it: one [step] per mouse-wheel notch (by default 2% of its
 * range, smaller than YouTube Music's jumps). Trackpads, which scroll in many small bits,
 * move it as far as a notch per notch's worth of scrolling. Up is more.
 *
 * Use: `<input type="range" use:wheelAdjust … />`. It fires the slider's own input and
 * change events, so its handlers run as if it was dragged.
 */
export function wheelAdjust(input: HTMLInputElement, step?: number) {
	/** Scrolled so far, in pixels, that didn't make a whole step yet. */
	let pending = 0;
	const NOTCH_PX = 100;

	function onWheel(event: WheelEvent) {
		if (input.disabled) return;
		event.preventDefault();
		// Firefox may count in lines.
		pending += event.deltaMode === WheelEvent.DOM_DELTA_LINE ? event.deltaY * 33 : event.deltaY;
		const notches = Math.trunc(pending / NOTCH_PX) || (Math.abs(event.deltaY) >= NOTCH_PX / 2 ? Math.sign(pending) : 0);
		if (!notches) return;
		pending = 0;
		const min = Number(input.min || 0);
		const max = Number(input.max || 100);
		const size = step ?? (max - min) / 50;
		const value = Math.min(max, Math.max(min, Number(input.value) - notches * size));
		if (value === Number(input.value)) return;
		input.value = String(Math.round(value / size) * size);
		input.dispatchEvent(new Event('input', { bubbles: true }));
		input.dispatchEvent(new Event('change', { bubbles: true }));
	}

	input.addEventListener('wheel', onWheel, { passive: false });
	return {
		update(next?: number) {
			step = next;
		},
		destroy() {
			input.removeEventListener('wheel', onWheel);
		}
	};
}

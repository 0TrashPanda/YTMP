// Dialogs and sheets that cover the page: `use:modal` on the dialog itself (role="dialog").
// While it's open, keyboard focus is inside it (it starts on its [autofocus] field or on the
// dialog), Tab goes round inside it, the page behind can't be reached (`inert`, where the browser
// knows it) and, given [onClose], Escape closes it from anywhere. Closed, focus goes back to
// where it was. Dialogs opened on top of each other: only the top one does this.

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** The open dialogs, the top one last. */
const stack: HTMLElement[] = [];

/** The elements made inert for each dialog (everything around it, up to the body). */
function makeOthersInert(node: HTMLElement): HTMLElement[] {
	const inerted: HTMLElement[] = [];
	for (let el: HTMLElement | null = node; el && el !== document.body; el = el.parentElement) {
		for (const sibling of el.parentElement?.children ?? []) {
			if (sibling === el || !(sibling instanceof HTMLElement) || sibling.inert) continue;
			// Toasts and live messages stay readable.
			if (sibling.matches('[aria-live], script, style')) continue;
			// The dialog's own backdrop next to it: a tap on it still closes the dialog.
			if (el === node && sibling.matches('[role="presentation"], [data-backdrop]')) continue;
			sibling.inert = true;
			inerted.push(sibling);
		}
	}
	return inerted;
}

function focusables(node: HTMLElement): HTMLElement[] {
	return [...node.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => el.getClientRects().length > 0 && !el.closest('[inert]'));
}

export function modal(node: HTMLElement, onClose?: () => void) {
	let close = onClose;
	const before = document.activeElement instanceof HTMLElement ? document.activeElement : null;
	if (!node.hasAttribute('tabindex')) node.tabIndex = -1;
	node.setAttribute('aria-modal', 'true');
	stack.push(node);
	const inerted = makeOthersInert(node);

	// After it's drawn (and its own autofocus had a chance).
	const start = requestAnimationFrame(() => {
		if (node.contains(document.activeElement)) return;
		(node.querySelector<HTMLElement>('[autofocus]') ?? node).focus({ preventScroll: true });
	});

	function keydown(event: KeyboardEvent) {
		if (stack[stack.length - 1] !== node) return;
		if (event.key === 'Escape' && close) {
			event.preventDefault();
			event.stopPropagation();
			close();
		} else if (event.key === 'Tab') {
			const items = focusables(node);
			if (items.length === 0) {
				event.preventDefault();
				node.focus({ preventScroll: true });
				return;
			}
			const first = items[0];
			const last = items[items.length - 1];
			const at = document.activeElement;
			if (event.shiftKey && (at === first || at === node || !node.contains(at))) {
				event.preventDefault();
				last.focus();
			} else if (!event.shiftKey && (at === last || !node.contains(at))) {
				event.preventDefault();
				first.focus();
			}
		}
	}
	// Capture: before the page's own shortcuts (Space plays, / searches).
	window.addEventListener('keydown', keydown, true);

	return {
		update(next?: () => void) {
			close = next;
		},
		destroy() {
			cancelAnimationFrame(start);
			window.removeEventListener('keydown', keydown, true);
			const at = stack.indexOf(node);
			if (at >= 0) stack.splice(at, 1);
			for (const el of inerted) el.inert = false;
			// Back where it was, unless focus already moved on (to another dialog, a page).
			const active = document.activeElement;
			if (before?.isConnected && (!active || active === document.body || node.contains(active))) before.focus({ preventScroll: true });
		}
	};
}

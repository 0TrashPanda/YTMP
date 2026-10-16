// Pictures that don't load (say local music without a cover) leave their grey tile instead
// of the browser's broken-picture icon.

/** `use:hideIfBroken` on an <img>: hidden while its picture can't load. */
export function hideIfBroken(img: HTMLImageElement) {
	const hide = () => (img.style.visibility = 'hidden');
	const show = () => (img.style.visibility = '');
	img.addEventListener('error', hide);
	img.addEventListener('load', show);
	if (img.complete && img.naturalWidth === 0 && img.getAttribute('src')) hide();
	return {
		destroy() {
			img.removeEventListener('error', hide);
			img.removeEventListener('load', show);
		}
	};
}

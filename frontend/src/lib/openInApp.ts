import { nativeBridge } from './native';

/**
 * A room link opened in a phone's browser (a scanned QR code, a link in a chat) opens in the
 * YTMP app when it's installed, like YouTube Music's links: an intent: link to the app
 * (`ytmp://open?url=…`, android/.../MainActivity.kt). Without the app, the browser goes on to
 * the same link with `?web=1`, and stays.
 *
 * Null: stay here (not Android, already in the app, or the browser was chosen).
 */
export function appLink(): string | null {
	if (nativeBridge) return null;
	const ua = navigator.userAgent;
	// Chrome and the browsers built on it (Samsung Internet, Edge, …) know intent: links; the WebViews in other apps don't.
	if (!/Android/.test(ua) || !/Chrome\//.test(ua) || /; wv\)/.test(ua)) return null;
	const url = new URL(location.href);
	if (!/^\/room\/[^/]+\/?$/.test(url.pathname) || url.searchParams.has('web')) return null;
	return `intent://open?url=${encodeURIComponent(url.href)}#Intent;scheme=ytmp;package=dev.trashpanda.ytmp;S.browser_fallback_url=${encodeURIComponent(webLink())};end`;
}

/** This page, staying in the browser. */
export function webLink(): string {
	const url = new URL(location.href);
	url.searchParams.set('web', '1');
	return url.href;
}

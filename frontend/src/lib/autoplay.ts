// Whether a page may start sound by itself. Browsers only allow sound after a tap or key press
// on the page, unless the site may autoplay (Firefox says so: the site's Autoplay permission).

type AutoplayNavigator = Navigator & {
	getAutoplayPolicy?: (type: 'mediaelement') => string;
	userActivation?: { hasBeenActive: boolean };
};

/** This browser lets a page start sound without any tap first (also right after a reload). */
export function autoplayAllowed(): boolean {
	try {
		return (navigator as AutoplayNavigator).getAutoplayPolicy?.('mediaelement') === 'allowed';
	} catch {
		return false;
	}
}

/**
 * Sound may start now without a tap: autoplay is allowed, or the page has been tapped or typed
 * in (Safari wants the audio itself started from a tap, so there only [unlocked] counts).
 */
export function mayPlayWithoutTap(unlocked: boolean, userAgent = navigator.userAgent): boolean {
	if (unlocked || autoplayAllowed()) return true;
	const safari = /Safari\//.test(userAgent) && !/Chrome\/|Chromium\/|CriOS|Edg\/|Firefox\/|FxiOS/.test(userAgent);
	return !safari && ((navigator as AutoplayNavigator).userActivation?.hasBeenActive ?? false);
}

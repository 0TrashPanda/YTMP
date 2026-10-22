import { updated } from '$app/state';

/**
 * A new version of the web app on the host (a server update): an open page reloads itself to
 * get it, so old pages don't miss new features or break on them. SvelteKit notices the new
 * version (see `version` in vite.config.ts); this picks a moment that doesn't interrupt you:
 * not while sound plays in this browser (a reloaded page needs a tap before it may play again),
 * and not while you're typing.
 */

/** Sound comes out of this browser now (the browser player says). */
let soundHere = $state(false);

export const appUpdate = {
	/** A new version is there, but this page waits: sound plays here. */
	get waiting(): boolean {
		return updated.current && soundHere;
	},
	set soundHere(on: boolean) {
		soundHere = on;
	},
	/** Look now, e.g. after the connection came back (an update restarts the server). */
	check(): void {
		void updated.check();
	},
	reload(): void {
		location.reload();
	}
};

/** A field you type in, with the keyboard up. */
function typing(): boolean {
	const field = document.activeElement;
	if (field instanceof HTMLTextAreaElement) return true;
	if (field instanceof HTMLElement && field.isContentEditable) return true;
	return field instanceof HTMLInputElement && !['button', 'checkbox', 'radio', 'range', 'submit', 'reset', 'file', 'color'].includes(field.type);
}

/** When this tab last reloaded for an update. */
const RELOADED_KEY = 'ytmp.reloadedForUpdate';

/**
 * Reloads, unless it just did: if something between here and the host keeps an old page,
 * reloading again wouldn't help, and would never stop.
 */
function reloadOnce(): void {
	try {
		if (Date.now() - Number(sessionStorage.getItem(RELOADED_KEY) ?? 0) < 60_000) return;
		sessionStorage.setItem(RELOADED_KEY, String(Date.now()));
	} catch {
		return; // Can't tell whether it just did.
	}
	location.reload();
}

/** Call once, from the root layout: reloads the page when a new version is there and it's calm. */
export function reloadOnUpdate(): void {
	const reloadIfCalm = () => {
		if (updated.current && !soundHere && !typing()) reloadOnce();
	};
	$effect(reloadIfCalm);
	$effect(() => {
		// Done typing: focus has moved on by the next task.
		const afterTyping = () => setTimeout(reloadIfCalm);
		addEventListener('focusout', afterTyping);
		return () => removeEventListener('focusout', afterTyping);
	});
}

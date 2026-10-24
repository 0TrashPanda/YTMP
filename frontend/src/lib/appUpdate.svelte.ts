import { updated } from '$app/state';

/**
 * A new version of the web app on the host (a server update): an open page reloads itself to
 * get it, so old pages don't miss new features or break on them. SvelteKit notices the new
 * version (see `version` in vite.config.ts); this picks a moment that doesn't interrupt you:
 * not while you're typing, and not while this page plays the room (a reloaded page needs a tap
 * before it may play again). Playing, it reloads between two songs when the browser lets the
 * reloaded page play on by itself (autoplay allowed: see autoplay.ts); otherwise it asks.
 */

/** This page plays the room ("Play here" on in the browser). */
let playingHere = $state(false);

/** The room this tab plays on in after reloading for an update. */
const RESUME_KEY = 'ytmp.resumeAfterUpdate';

export const appUpdate = {
	/** A new version is there, but this page waits: it plays the room. */
	get waiting(): boolean {
		return updated.current && playingHere;
	},
	set playingHere(on: boolean) {
		playingHere = on;
	},
	/** Look now, e.g. after the connection came back (an update restarts the server). */
	check(): void {
		void updated.check();
	},
	reload(): void {
		location.reload();
	},
	/**
	 * A new song starts on this page's player in [roomCode]: with a new version there and
	 * [canPlayOn] (the reloaded page may start sound by itself), it reloads now and plays on
	 * after (see [resumes]). True when it reloads.
	 */
	betweenSongs(roomCode: string, canPlayOn: boolean): boolean {
		if (!updated.current || !canPlayOn) return false;
		try {
			sessionStorage.setItem(RESUME_KEY, roomCode);
		} catch {
			return false;
		}
		if (reloadOnce()) return true;
		sessionStorage.removeItem(RESUME_KEY);
		return false;
	},
	/** This page just reloaded for an update while it played [roomCode]: it plays on (asked once). */
	resumes(roomCode: string): boolean {
		try {
			const code = sessionStorage.getItem(RESUME_KEY);
			sessionStorage.removeItem(RESUME_KEY);
			return code === roomCode;
		} catch {
			return false;
		}
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
function reloadOnce(): boolean {
	try {
		if (Date.now() - Number(sessionStorage.getItem(RELOADED_KEY) ?? 0) < 60_000) return false;
		sessionStorage.setItem(RELOADED_KEY, String(Date.now()));
	} catch {
		return false; // Can't tell whether it just did.
	}
	location.reload();
	return true;
}

/** Call once, from the root layout: reloads the page when a new version is there and it's calm. */
export function reloadOnUpdate(): void {
	const reloadIfCalm = () => {
		if (updated.current && !playingHere && !typing()) reloadOnce();
	};
	$effect(reloadIfCalm);
	$effect(() => {
		// Done typing: focus has moved on by the next task.
		const afterTyping = () => setTimeout(reloadIfCalm);
		addEventListener('focusout', afterTyping);
		return () => removeEventListener('focusout', afterTyping);
	});
}

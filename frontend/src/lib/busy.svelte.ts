// Feedback for what takes a moment (docs/features/ui.md#feedback): at first nothing; when it
// isn't done after the wait set in Settings → Adding songs, a message saying what's going on
// ("Adding Liked music…"), until it's done and the result's own message takes its place.
import { settings } from './settings.svelte';

/** The messages of what's going on now, oldest first. */
export const busy = $state<{ id: number; text: string }[]>([]);
let lastId = 0;

/** Runs [work], saying [text] while it takes longer than the set wait. Resolves (or fails) with it. */
export async function whileBusy<T>(text: string, work: Promise<T> | (() => Promise<T>)): Promise<T> {
	const id = ++lastId;
	const show = () => busy.push({ id, text });
	const delay = settings.busyDelay.value;
	const timer = delay > 0 ? setTimeout(show, delay) : (show(), undefined);
	try {
		return await (typeof work === 'function' ? work() : work);
	} finally {
		clearTimeout(timer);
		const at = busy.findIndex((b) => b.id === id);
		if (at >= 0) busy.splice(at, 1);
	}
}

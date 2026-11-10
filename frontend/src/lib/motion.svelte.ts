// Less moving on screen (Settings → Look → Reduce motion): as the phone or computer asks
// (`prefers-reduced-motion`), always, or never. CSS animations and transitions follow the
// `motion-reduce` / `motion-full` class on <html> (app.css); what moves from code (Svelte's
// transitions, smooth scrolling) asks [reducedMotion].
import { MediaQuery } from 'svelte/reactivity';
import { settings } from './settings.svelte';

const system = typeof window === 'undefined' ? null : new MediaQuery('prefers-reduced-motion: reduce');

/** Things should move less (or not at all) now. Reactive. */
export function reducedMotion(): boolean {
	const set = settings.reduceMotion.value;
	return set === 'on' || (set === 'system' && !!system?.current);
}

/** [ms], or 0 with reduced motion: for a transition's `duration`. */
export function motionMs(ms: number): number {
	return reducedMotion() ? 0 : ms;
}

/** 'smooth', or 'instant' with reduced motion: for scrolling from code. */
export function scrollBehavior(): ScrollBehavior {
	return reducedMotion() ? 'instant' : 'smooth';
}

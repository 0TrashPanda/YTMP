// What tapping, swiping and holding a song does, in song lists (search, albums, artists,
// playlists, Local) and in Up next, as set in Settings → Taps and swipes. Saved on this device.
import { read, write } from './storage';

export type Gesture = 'tap' | 'right' | 'left' | 'hold';
export type SongAction = 'playNow' | 'playNext' | 'addToQueue' | 'radio' | 'menu' | 'none';
export type QueueAction = SongAction | 'remove';
export type Where = 'list' | 'queue';

export const GESTURES: [Gesture, string][] = [
	['tap', 'Tap'],
	['right', 'Swipe right'],
	['left', 'Swipe left'],
	['hold', 'Hold']
];

export const ACTION_NAMES: Record<QueueAction, string> = {
	playNow: 'Play now',
	playNext: 'Play next',
	addToQueue: 'Add to queue',
	radio: 'Start radio',
	menu: 'Song menu',
	remove: 'Remove',
	none: 'Nothing'
};

export const ACTIONS: Record<Where, QueueAction[]> = {
	list: ['playNow', 'playNext', 'addToQueue', 'radio', 'menu', 'none'],
	queue: ['playNow', 'playNext', 'addToQueue', 'remove', 'radio', 'menu', 'none']
};

export const DEFAULTS: Record<Where, Record<Gesture, QueueAction>> = {
	list: { tap: 'playNext', right: 'addToQueue', left: 'playNow', hold: 'menu' },
	queue: { tap: 'playNow', right: 'playNext', left: 'remove', hold: 'menu' }
};

function load(where: Where): Record<Gesture, QueueAction> {
	const out = { ...DEFAULTS[where] };
	try {
		const stored = JSON.parse(read(`ytmp.gestures.${where}`) ?? '{}') as Record<string, unknown>;
		for (const [gesture] of GESTURES) {
			const action = stored[gesture];
			if (typeof action === 'string' && (ACTIONS[where] as string[]).includes(action)) out[gesture] = action as QueueAction;
		}
	} catch {
		// Unreadable: the defaults.
	}
	return out;
}

export const gestures = $state({ list: load('list'), queue: load('queue') });

export function setGesture(where: Where, gesture: Gesture, action: QueueAction): void {
	gestures[where][gesture] = action;
	write(`ytmp.gestures.${where}`, JSON.stringify(gestures[where]));
}

export function resetGestures(where: Where): void {
	gestures[where] = { ...DEFAULTS[where] };
	write(`ytmp.gestures.${where}`, JSON.stringify(gestures[where]));
}

/** How far (part of the row's width) a song must be swiped before letting go does it. */
const SWIPE_DONE = 0.3;
/** The finger must move this far before a swipe and a scroll can be told apart. */
const SWIPE_DECIDE_PX = 10;

/**
 * A song being swiped left or right (touch). Rows call [start], [move] and [end] from their
 * touch events and show [offset]; [end] says which way, when far enough.
 */
export class RowSwipe {
	/** [horizontal]: null until it's clear the finger swipes (and which way, [right]) rather than scrolls. */
	active = $state<{ key: string; x: number; y: number; dx: number; width: number; horizontal: boolean | null; right: boolean } | null>(null);

	start(event: TouchEvent, key: string): void {
		// A drag handle moves the song instead.
		if ((event.target as HTMLElement).closest('.touch-none')) return;
		const t = event.touches[0];
		this.active = { key, x: t.clientX, y: t.clientY, dx: 0, width: (event.currentTarget as HTMLElement).offsetWidth, horizontal: null, right: false };
	}

	/** [can]: whether the song may go that way (an action other than Nothing, and allowed). */
	move(event: TouchEvent, can: (direction: 'left' | 'right') => boolean): void {
		const s = this.active;
		if (!s) return;
		const t = event.touches[0];
		const dx = t.clientX - s.x;
		const dy = t.clientY - s.y;
		if (s.horizontal === null) {
			if (Math.abs(dx) < SWIPE_DECIDE_PX && Math.abs(dy) < SWIPE_DECIDE_PX) return;
			s.right = dx > 0;
			s.horizontal = Math.abs(dx) > Math.abs(dy) && can(s.right ? 'right' : 'left');
		}
		// Only the way it started: back past the start stops at the start.
		if (s.horizontal) s.dx = s.right ? Math.max(0, dx) : Math.min(0, dx);
	}

	/** Let go: the way it was swiped far enough, else null (it slides back). */
	end(): { key: string; direction: 'left' | 'right' } | null {
		const s = this.active;
		this.active = null;
		if (!s?.horizontal || Math.abs(s.dx) < s.width * SWIPE_DONE) return null;
		return { key: s.key, direction: s.dx > 0 ? 'right' : 'left' };
	}

	cancel(): void {
		this.active = null;
	}

	/** How far song [key] is swiped (px), and which way. */
	offset(key: string): number {
		const s = this.active;
		return s?.key === key && s.horizontal ? s.dx : 0;
	}

	/** Far enough that letting go does it (to show the action more strongly). */
	far(key: string): boolean {
		const s = this.active;
		return !!s && s.key === key && !!s.horizontal && Math.abs(s.dx) >= s.width * SWIPE_DONE;
	}
}

/** The icon for an action, under a song while it's swiped. */
export const ACTION_ICONS = {
	playNow: 'play',
	playNext: 'playNext',
	addToQueue: 'playlistAdd',
	radio: 'radio',
	menu: 'more',
	remove: 'remove',
	none: 'close'
} as const;

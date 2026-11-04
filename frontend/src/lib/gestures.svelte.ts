// What tapping, swiping and holding a song does, in song lists (search, albums, artists,
// playlists, Local), in the queue and in Autoplay, as set in Settings → Taps and swipes: one of your settings (settings.svelte.ts).
import { settings } from './settings.svelte';
import type { RoomState } from './protocol.gen';

export type Gesture = 'tap' | 'right' | 'left' | 'hold';
export type SongAction = 'playNow' | 'playFromHere' | 'playNext' | 'addToQueue' | 'radio' | 'menu' | 'none';
export type QueueAction = SongAction | 'remove';
export type Where = 'list' | 'queue' | 'autoplay';

export const GESTURES: [Gesture, string][] = [
	['tap', 'Tap'],
	['right', 'Swipe right'],
	['left', 'Swipe left'],
	['hold', 'Hold']
];

export const ACTION_NAMES: Record<QueueAction, string> = {
	playNow: 'Play now',
	playFromHere: 'Play from here',
	playNext: 'Play next',
	addToQueue: 'Add to queue',
	radio: 'Start radio',
	menu: 'Song menu',
	remove: 'Remove',
	none: 'Nothing'
};

export const ACTIONS: Record<Where, QueueAction[]> = {
	list: ['playNow', 'playNext', 'addToQueue', 'radio', 'menu', 'none'],
	queue: ['playNow', 'playNext', 'addToQueue', 'remove', 'radio', 'menu', 'none'],
	autoplay: ['playNow', 'playNext', 'addToQueue', 'remove', 'radio', 'menu', 'none']
};

export const DEFAULTS: Record<Where, Record<Gesture, QueueAction>> = {
	list: { tap: 'addToQueue', right: 'playNext', left: 'playNow', hold: 'menu' },
	queue: { tap: 'playNow', right: 'playNext', left: 'remove', hold: 'menu' },
	// Play now keeps the autoplay songs before it (see QueuePanel).
	autoplay: { tap: 'addToQueue', right: 'playNow', left: 'remove', hold: 'menu' }
};

/**
 * Saved with each change. A saved value from an older version had all four gestures, also the
 * ones never changed: one that is a default of then ([OLD_DEFAULTS]) is taken as not chosen and
 * follows today's default.
 */
const VERSION = 3;

/** Earlier defaults that changed since. */
const OLD_DEFAULTS: Record<Where, Partial<Record<Gesture, QueueAction[]>>> = {
	list: { tap: ['playNext'], right: ['addToQueue'] },
	queue: {},
	autoplay: { tap: ['playNow'], right: ['playNext'] }
};

export function parse(where: Where, stored: Record<string, unknown>): Record<Gesture, QueueAction> {
	const out = { ...DEFAULTS[where] };
	const old = stored.v !== VERSION;
	for (const [gesture] of GESTURES) {
		const action = stored[gesture];
		if (old && (OLD_DEFAULTS[where][gesture] as unknown[] | undefined)?.includes(action)) continue;
		if (typeof action === 'string' && (ACTIONS[where] as string[]).includes(action)) out[gesture] = action as QueueAction;
	}
	return out;
}

const SETTINGS = { list: settings.gesturesList, queue: settings.gesturesQueue, autoplay: settings.gesturesAutoplay };

/** The setting for [where]. */
export const gestureSetting = (where: Where) => SETTINGS[where];

/**
 * Autoplay's songs used to do what Up next's do: until Autoplay is set, it keeps what the queue
 * had (so the queue's own changes are pinned on Autoplay first, see [setGesture]).
 */
const autoplaySet = () => Object.keys(settings.gesturesAutoplay.value).length > 0;

/** What each gesture does, in lists, in the queue and in Autoplay. Reactive. */
export const gestures = {
	get list() {
		return parse('list', settings.gesturesList.value);
	},
	get queue() {
		return parse('queue', settings.gesturesQueue.value);
	},
	get autoplay() {
		return autoplaySet() ? parse('autoplay', settings.gesturesAutoplay.value) : parse('autoplay', settings.gesturesQueue.value);
	}
};

/** Before the queue changes: Autoplay keeps what it has. */
function pinAutoplay(): void {
	if (!autoplaySet()) settings.gesturesAutoplay.set({ v: VERSION, ...gestures.autoplay });
}

export function setGesture(where: Where, gesture: Gesture, action: QueueAction): void {
	if (where === 'queue') pinAutoplay();
	gestureSetting(where).set({ v: VERSION, ...gestures[where], [gesture]: action });
}

/**
 * What tapping a song on an album or playlist page does: with [fromHere] on (Settings →
 * Playback) and the room empty (nothing queued and nothing playing), it plays the album or
 * playlist from that song, like YouTube Music; else what a tap in lists does.
 */
export function pageTap(state: Pick<RoomState, 'queue' | 'nowPlaying' | 'playback'> | null, fromHere: boolean, listTap: QueueAction): QueueAction {
	const empty = !!state && state.queue.length === 0 && !(state.nowPlaying && state.playback.playing);
	return fromHere && empty ? 'playFromHere' : listTap;
}

export function resetGestures(where: Where): void {
	if (where === 'queue') pinAutoplay();
	// Autoplay's own defaults, not the queue's (what unset means).
	if (where === 'autoplay') settings.gesturesAutoplay.set({ v: VERSION, ...DEFAULTS.autoplay });
	else gestureSetting(where).reset();
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
	playFromHere: 'play',
	playNext: 'playNext',
	addToQueue: 'playlistAdd',
	radio: 'radio',
	menu: 'more',
	remove: 'remove',
	none: 'close'
} as const;

/**
 * Taps, swipes and holds on the rows of a list ([where]: a song list, the queue or Autoplay), each row
 * being a [Row]. What a gesture does is in [gestures]; [allowed] says whether the room lets you
 * (the song menu always opens), [run] does it, [menu] opens the song menu. A right-click always
 * opens the menu; holding a finger does the Hold action.
 */
export class RowGestures<Row> {
	readonly swipe = new RowSwipe();
	/** Touch or mouse, for the last press. */
	private pointer = 'mouse';

	constructor(
		/** Where [row] is: a song list, the queue (with what played) or Autoplay. */
		private readonly where: (row: Row) => Where,
		private readonly allowed: (action: QueueAction, row: Row) => boolean,
		private readonly run: (action: QueueAction, row: Row, event: MouseEvent | null, element: HTMLElement | null) => void,
		private readonly menu: (row: Row, event: MouseEvent, atPointer: boolean) => void,
		/** What a tap does here, when it isn't the list's tap (album and playlist pages). */
		private readonly tap?: () => QueueAction
	) {}

	/** For the row's `onpointerdown`. */
	pressed(event: PointerEvent): void {
		this.pointer = event.pointerType;
	}

	/** Does what [gesture] is set to for [row]; [event] and [element] place the menu. */
	act(gesture: Gesture, row: Row, event: MouseEvent | null, element: HTMLElement | null): void {
		const action = this.of(row)[gesture];
		if (action !== 'menu' && !this.allowed(action, row)) return;
		this.run(action, row, event, element);
	}

	/** For the row's `ontouchmove`: may it be swiped that way. */
	canSwipe(row: Row): (way: 'left' | 'right') => boolean {
		return (way) => {
			const action = this.of(row)[way];
			return action !== 'none' && this.allowed(action, row);
		};
	}

	/** For the row's `ontouchend`. */
	swiped(row: Row, element: HTMLElement): void {
		const done = this.swipe.end();
		if (done) this.act(done.direction, row, null, element);
	}

	/** For the row's `oncontextmenu`: a right-click, or a finger held down. */
	held(event: MouseEvent, row: Row): void {
		if (this.pointer === 'touch' && this.of(row).hold !== 'menu') {
			event.preventDefault();
			this.swipe.cancel();
			this.act('hold', row, null, event.currentTarget as HTMLElement);
		} else {
			this.menu(row, event, true);
		}
	}

	/** What letting go of a swipe of [offset] px on [row] would do. */
	actionFor(row: Row, offset: number): QueueAction {
		return this.of(row)[offset > 0 ? 'right' : 'left'];
	}

	/** What each gesture does for [row] (a page's own tap, when it has one). */
	of(row: Row): Record<Gesture, QueueAction> {
		const set = gestures[this.where(row)];
		return this.tap ? { ...set, tap: this.tap() } : set;
	}
}

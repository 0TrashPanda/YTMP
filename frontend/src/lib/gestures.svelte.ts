// What tapping, swiping and holding a song does, in song lists (search, albums, artists,
// playlists, Local) and in Up next, as set in Settings → Taps and swipes: one of your settings (settings.svelte.ts).
import { settings } from './settings.svelte';

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

function parse(where: Where, stored: Record<string, unknown>): Record<Gesture, QueueAction> {
	const out = { ...DEFAULTS[where] };
	for (const [gesture] of GESTURES) {
		const action = stored[gesture];
		if (typeof action === 'string' && (ACTIONS[where] as string[]).includes(action)) out[gesture] = action as QueueAction;
	}
	return out;
}

const setting = (where: Where) => (where === 'list' ? settings.gesturesList : settings.gesturesQueue);

/** What each gesture does, in lists and in Up next. Reactive. */
export const gestures = {
	get list() {
		return parse('list', settings.gesturesList.value);
	},
	get queue() {
		return parse('queue', settings.gesturesQueue.value);
	}
};

export function setGesture(where: Where, gesture: Gesture, action: QueueAction): void {
	setting(where).set({ ...gestures[where], [gesture]: action });
}

export function resetGestures(where: Where): void {
	setting(where).reset();
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

/**
 * Taps, swipes and holds on the rows of a list ([where]: a song list or Up next), each row
 * being a [Row]. What a gesture does is in [gestures]; [allowed] says whether the room lets you
 * (the song menu always opens), [run] does it, [menu] opens the song menu. A right-click always
 * opens the menu; holding a finger does the Hold action.
 */
export class RowGestures<Row> {
	readonly swipe = new RowSwipe();
	/** Touch or mouse, for the last press. */
	private pointer = 'mouse';

	constructor(
		private readonly where: Where,
		private readonly allowed: (action: QueueAction, row: Row) => boolean,
		private readonly run: (action: QueueAction, row: Row, event: MouseEvent | null, element: HTMLElement | null) => void,
		private readonly menu: (row: Row, event: MouseEvent, atPointer: boolean) => void
	) {}

	/** For the row's `onpointerdown`. */
	pressed(event: PointerEvent): void {
		this.pointer = event.pointerType;
	}

	/** Does what [gesture] is set to for [row]; [event] and [element] place the menu. */
	act(gesture: Gesture, row: Row, event: MouseEvent | null, element: HTMLElement | null): void {
		const action = gestures[this.where][gesture];
		if (action !== 'menu' && !this.allowed(action, row)) return;
		this.run(action, row, event, element);
	}

	/** For the row's `ontouchmove`: may it be swiped that way. */
	canSwipe(row: Row): (way: 'left' | 'right') => boolean {
		return (way) => {
			const action = gestures[this.where][way];
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
		if (this.pointer === 'touch' && gestures[this.where].hold !== 'menu') {
			event.preventDefault();
			this.swipe.cancel();
			this.act('hold', row, null, event.currentTarget as HTMLElement);
		} else {
			this.menu(row, event, true);
		}
	}

	/** What letting go of a swipe of [offset] px would do. */
	actionFor(offset: number): QueueAction {
		return gestures[this.where][offset > 0 ? 'right' : 'left'];
	}
}

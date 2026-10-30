// The pages you opened in a room, in order: Home at the bottom, then Library or Local (the
// bottom bar starts over from there), then search, find similar, artist, album, playlist,
// podcast, genre, folder, history and import pages. Back (the phone's, or the back arrow) goes to the
// one before, scrolled as you left it. A new kind of page is a new View.
import { forgetLocalPlace, localPlace } from './local.svelte';
import type { RadioFrom, Song } from './protocol.gen';

/** The bottom bar's pages. */
export type Section = 'home' | 'library' | 'local';

/** Pages opened on top of them. */
export type View =
	| { kind: 'search'; query: string }
	| { kind: 'similar'; song: Song; from?: RadioFrom }
	| { kind: 'artist'; id: string; name: string }
	| { kind: 'album'; id: string; title: string }
	| { kind: 'playlist'; id: string; title: string; personal?: boolean }
	| { kind: 'podcast'; id: string; title: string }
	| { kind: 'genre'; library: string; name: string }
	| { kind: 'folder'; library: string; path: string }
	| { kind: 'history' }
	| { kind: 'import'; jobId?: string };

export type Page = ({ kind: Section } | View) & { scrollTop?: number };

const MAX_PAGES = 50;

const isSection = (p: Page): p is Page & { kind: Section } => p.kind === 'home' || p.kind === 'library' || p.kind === 'local';

/** What the stack needs from the room page around it. */
interface PageScroller {
	/** How far the page shown now is scrolled. */
	scrollTop(): number;
	/** Scrolls a page that was just shown to [y] (once it's that long). */
	scrollTo(y: number): void;
	/** The bottom bar's page shown already was picked again: back to its top. */
	toTop(): void;
	/** Another page is about to show (the full player closes). */
	leaving(): void;
}

export class PageStack {
	pages = $state<Page[]>([{ kind: 'home' }]);
	/** The page shown now. */
	readonly top = $derived(this.pages.at(-1)!);
	/** The page shown now when it's not one of the bottom bar's. */
	readonly view = $derived(isSection(this.top) ? null : this.top);
	/** The bottom bar's page under the pages opened from it. */
	readonly section = $derived((this.pages.findLast(isSection)?.kind ?? 'home') as Section);
	/** The search shown in the search box: the last one. */
	readonly query = $derived(this.pages.findLast((v): v is Extract<View, { kind: 'search' }> => v.kind === 'search')?.query ?? '');
	/** The page has a Back link: there's a page before it that isn't the bottom bar's. */
	readonly hasBackLink = $derived(this.pages.length > 1 && !isSection(this.pages.at(-2)!));

	constructor(private readonly scroller: PageScroller) {}

	/** Opens [next] on top of the page shown now. */
	show(next: Page) {
		this.leave();
		// A new search replaces the results on top instead of piling up.
		const below = next.kind === 'search' && this.top.kind === 'search' ? this.pages.slice(0, -1) : this.pages;
		const kept = [...below, next].slice(-MAX_PAGES);
		this.pages = kept[0].kind === 'home' ? kept : [{ kind: 'home' }, ...kept.slice(1)];
		this.scroller.scrollTo(next.scrollTop ?? 0);
	}

	/** Back to the page before, scrolled as you left it. False when there's none. */
	back(): boolean {
		if (this.pages.length < 2) return false;
		this.leave();
		this.pages = this.pages.slice(0, -1);
		this.scroller.scrollTo(this.top.scrollTop ?? 0);
		return true;
	}

	/**
	 * A page of the bottom bar: the pages start over from it, Home under Library and Local (so
	 * Back from those goes Home). The one shown already goes back to its top.
	 */
	pick(next: Section) {
		if (this.top.kind === next) {
			this.scroller.leaving();
			this.scroller.toTop();
			return;
		}
		this.leave();
		// Local opens where you were, or (Remember where you were off) at the start.
		if (next === 'local' && !localPlace.remember) forgetLocalPlace();
		const scrollTop = next === 'local' ? localPlace.scrollTop : 0;
		this.pages = next === 'home' ? [{ kind: 'home' }] : [{ kind: 'home' }, { kind: next, scrollTop }];
		this.scroller.scrollTo(scrollTop);
	}

	/** Remembers how far the page shown now is scrolled, for coming back to it. */
	private leave() {
		this.scroller.leaving();
		const y = this.scroller.scrollTop();
		this.top.scrollTop = y;
		if (this.top.kind === 'local') localPlace.scrollTop = y;
	}
}

// Searching the settings (SettingsScreen.svelte). Not a list kept by hand: the search shows every
// settings page at once and keeps only the rows whose words match, so a new setting is found
// as soon as it's on a page. A row is found by its label (the bold name of a setting: Toggle,
// Choice and CountList have one, custom rows a <p class="font-medium">): the biggest part of
// the page around the label that holds no other label. A heading (a card's <h3>) matches with
// its whole card.

/** What counts as a setting's name. */
export const LABELS = 'p.font-medium, label.font-medium, h3, h4';

/** Lower case, without accents and with plain quotes, for comparing. */
export function fold(text: string): string {
	return text
		.normalize('NFD')
		.replace(/[̀-ͯ]/g, '')
		.replace(/[‘’`]/g, "'")
		.replace(/[“”]/g, '"')
		.toLowerCase();
}

/** The words of a search. */
export function searchWords(query: string): string[] {
	return fold(query)
		.split(/[^\p{L}\p{N}']+/u)
		.map((w) => w.replace(/^'+|'+$/g, ''))
		.filter(Boolean);
}

/** Every search word starts a word of [text] (so "lyr" finds Lyrics, "play" doesn't find "display"). */
export function matches(text: string, words: string[]): boolean {
	if (!words.length) return false;
	const own = fold(text)
		.split(/[^\p{L}\p{N}']+/u)
		.map((w) => w.replace(/^'+|'+$/g, ''));
	return words.every((word) => own.some((w) => w.startsWith(word)));
}

/** The part of the DOM this needs (a tree of elements; tests use a small fake one). */
export interface Node {
	parentElement: Node | null;
	tagName: string;
}

/** For each label under [page]: the row it names (see the top). */
export function rowsOf<T extends Node>(page: T, labels: T[]): Map<T, T> {
	// How many labels each element holds.
	const count = new Map<Node, number>();
	for (const label of labels) {
		for (let e: Node | null = label; e && e !== page; e = e.parentElement) count.set(e, (count.get(e) ?? 0) + 1);
	}
	const rows = new Map<T, T>();
	for (const label of labels) {
		let row: Node = label;
		if (/^H\d$/.test(label.tagName)) {
			// A heading: its card, when the card is more than the page itself.
			if (label.parentElement && label.parentElement !== page) row = label.parentElement;
		} else {
			while (row.parentElement && row.parentElement !== page && count.get(row.parentElement) === 1) row = row.parentElement;
		}
		rows.set(label, row as T);
	}
	return rows;
}

/** The words in [element], without the choices of a <select> (those are the same in every row). */
export function textOf(element: Element): string {
	let text = '';
	const walk = (e: Element) => {
		for (const child of Array.from(e.childNodes)) {
			if (child.nodeType === 3) text += child.textContent + ' ';
			else if (child.nodeType === 1 && (child as Element).tagName !== 'OPTION' && (child as Element).tagName !== 'SELECT') walk(child as Element);
		}
	};
	walk(element);
	return text;
}

/**
 * Marks what [words] find in one page of the search: `data-hit` on the matching rows,
 * `data-path` on everything between them and [page] (the rest is hidden by CSS). Returns how
 * many rows matched. `data-search-keep` (a Save bar) shows whenever something on the page matched.
 */
export function mark(page: HTMLElement, words: string[]): number {
	for (const e of Array.from(page.querySelectorAll('[data-hit], [data-path]'))) {
		e.removeAttribute('data-hit');
		e.removeAttribute('data-path');
	}
	const labels = Array.from(page.querySelectorAll<HTMLElement>(LABELS));
	const rows = rowsOf(page, labels);
	const hits = new Set<HTMLElement>();
	for (const [label, row] of rows) {
		if (matches(/^H\d$/.test(label.tagName) ? textOf(label) : textOf(row), words)) hits.add(row);
	}
	// A row inside another hit shows with it anyway.
	const shown = [...hits].filter((row) => ![...hits].some((other) => other !== row && other.contains(row)));
	// What always shows with a matching row, like a room's Save bar.
	const kept = shown.length ? Array.from(page.querySelectorAll<HTMLElement>('[data-search-keep]')) : [];
	for (const row of [...shown, ...kept]) {
		row.setAttribute('data-hit', '');
		for (let e = row.parentElement; e && e !== page; e = e.parentElement) e.setAttribute('data-path', '');
	}
	return shown.length;
}

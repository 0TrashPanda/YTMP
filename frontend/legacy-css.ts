import type { Plugin } from 'vite';

/**
 * Chrome before 99 (older Chromecasts run Chrome 90) ignores `@layer` blocks, and Tailwind puts
 * all its styles in layers, so such a browser shows the page unstyled. This adds the same
 * styles without layers, in layer order and before the unlayered ones, inside
 * `@supports not (color: color-mix(in lab, red, red))`: only browsers older than Chrome 111 use
 * it, newer ones keep the layered CSS exactly as it is.
 */
export function legacyLayers(): Plugin {
	return {
		name: 'ytmp-legacy-layers',
		apply: 'build',
		generateBundle(_, bundle) {
			for (const file of Object.values(bundle)) {
				if (file.type !== 'asset' || !file.fileName.endsWith('.css')) continue;
				const css = typeof file.source === 'string' ? file.source : new TextDecoder().decode(file.source);
				const flat = unlayer(css);
				if (flat !== null) file.source = `${css}@supports not (color:color-mix(in lab,red,red)){${flat}}`;
			}
		}
	};
}

/** The rules of all top-level `@layer` blocks (in the order the layers are first named), or null if there are none. */
export function unlayer(css: string): string | null {
	const layers = new Map<string, string[]>();
	let i = 0;
	let found = false;
	while (i < css.length) {
		const start = css.indexOf('@layer', i);
		if (start < 0) break;
		// Only top-level ones: skip any inside another block.
		if (depthAt(css, start) !== 0) {
			i = start + 6;
			continue;
		}
		const open = css.indexOf('{', start);
		const semi = css.indexOf(';', start);
		const names = css.slice(start + 6, semi >= 0 && (open < 0 || semi < open) ? semi : open).split(',').map((n) => n.trim());
		for (const name of names) if (!layers.has(name)) layers.set(name, []);
		if (open < 0 || (semi >= 0 && semi < open)) {
			i = semi + 1; // `@layer a, b;`: only the order.
			continue;
		}
		const close = matching(css, open);
		layers.get(names[0])!.push(css.slice(open + 1, close));
		found = true;
		i = close + 1;
	}
	return found ? [...layers.values()].flat().join('') : null;
}

/** How deep in `{…}` blocks [at] is (outside strings). */
function depthAt(css: string, at: number): number {
	let depth = 0;
	let quote: string | null = null;
	for (let i = 0; i < at; i++) {
		const c = css[i];
		if (quote) {
			if (c === '\\') i++;
			else if (c === quote) quote = null;
		} else if (c === '"' || c === "'") quote = c;
		else if (c === '{') depth++;
		else if (c === '}') depth--;
	}
	return depth;
}

/** The `}` that closes the `{` at [open]. */
function matching(css: string, open: number): number {
	let depth = 0;
	let quote: string | null = null;
	for (let i = open; i < css.length; i++) {
		const c = css[i];
		if (quote) {
			if (c === '\\') i++;
			else if (c === quote) quote = null;
		} else if (c === '"' || c === "'") quote = c;
		else if (c === '{') depth++;
		else if (c === '}' && --depth === 0) return i;
	}
	return css.length;
}

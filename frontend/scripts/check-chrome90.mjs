// Older Chromecasts run Chrome 90 (a TV showing the party screen). The build rewrites newer
// syntax for it, but not newer built-ins: those need src/lib/polyfills.ts. This fails the build
// when the built app uses one that isn't polyfilled (`pnpm build` runs it after Vite).
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// A path on every system (`.pathname` gives /C:/… on Windows).
const root = fileURLToPath(new URL('..', import.meta.url));

// Built-ins newer than Chrome 90 (the Chrome version that added them), and how they look in the built code.
const NEWER = [
	['structuredClone', 98, /\bstructuredClone\b/],
	['at', 92, /\.at\(/],
	['hasOwn', 93, /\bObject\.hasOwn\b/],
	['findLast', 97, /\.findLast\(/],
	['findLastIndex', 97, /\.findLastIndex\(/],
	['timeout', 103, /\bAbortSignal\.timeout\b/],
	['any', 116, /\bAbortSignal\.any\b/],
	['randomUUID', 92, /\brandomUUID\b/],
	['toSorted', 110, /\.toSorted\(/],
	['toReversed', 110, /\.toReversed\(/],
	['toSpliced', 110, /\.toSpliced\(/],
	['with', 110, /\.with\(/],
	['groupBy', 117, /\b(Object|Map)\.groupBy\b/],
	['withResolvers', 119, /\bPromise\.withResolvers\b/],
	['try', 128, /\bPromise\.try\b/],
	['fromAsync', 121, /\bArray\.fromAsync\b/],
	['union', 122, /\.(union|intersection|difference|symmetricDifference|isSubsetOf|isSupersetOf|isDisjointFrom)\(/],
	['isWellFormed', 111, /\.(isWellFormed|toWellFormed)\(/],
	['escape', 136, /\bRegExp\.escape\b/],
	['from', 122, /\bIterator\.from\b/],
	['supportedValuesOf', 99, /\bIntl\.supportedValuesOf\b/],
	['checkVisibility', 105, /\bcheckVisibility\b/],
	['showPicker', 99, /\bshowPicker\b/],
	['showPopover', 114, /\b(show|hide|toggle)Popover\b/],
	['CloseWatcher', 120, /\bCloseWatcher\b/],
	['postTask', 94, /\bscheduler\.postTask\b/]
];

// What polyfills.ts adds: every define(…, 'name', …).
const polyfilled = new Set([...readFileSync(join(root, 'src/lib/polyfills.ts'), 'utf8').matchAll(/define\([^,]+,\s*'([^']+)'/g)].map((m) => m[1]));

function files(dir) {
	return readdirSync(dir, { withFileTypes: true }).flatMap((e) => (e.isDirectory() ? files(join(dir, e.name)) : e.name.endsWith('.js') ? [join(dir, e.name)] : []));
}

const code = files(join(root, 'build')).map((f) => readFileSync(f, 'utf8')).join('\n');
const missing = NEWER.filter(([name, , pattern]) => !polyfilled.has(name) && pattern.test(code));
if (missing.length) {
	console.error('The web app uses built-ins that Chrome 90 (older Chromecasts) lacks. Add them to src/lib/polyfills.ts:');
	for (const [name, chrome, pattern] of missing) console.error(`  ${name} (Chrome ${chrome}), found as ${pattern}`);
	process.exit(1);
}
console.log(`Chrome 90: fine (${polyfilled.size} built-ins polyfilled)`);

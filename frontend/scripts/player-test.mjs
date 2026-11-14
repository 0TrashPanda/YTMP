#!/usr/bin/env node
// The web player (src/lib/player.svelte.ts) playing real audio in a real browser, with a fake
// room (tests/player/harness.ts): songs' lengths, PlayerReady, PlayerEnded, waiting at the end.
//
//   cd frontend && pnpm test:player            (or: node scripts/player-test.mjs [test …])
//
// Needs a Chromium-based browser: YTMP_CHROME, else Chromium, Chrome or Edge where they're
// usually installed. It runs headless and muted (nothing is heard), on its own local server.
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

const frontend = fileURLToPath(new URL('..', import.meta.url));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** How late this computer's host and the own module answer (ms): the player must not use the last song meanwhile. */
const LATE_MS = 1200;
/**
 * The own module's lookup of a song of 5 s or more (the second song) takes longer than the song
 * before it plays (looked up ahead of time, it's still not there when that one ends).
 */
const LONG_LOOKUP_MS = 5000;

function findChrome() {
	const candidates = [
		process.env.YTMP_CHROME,
		'/usr/bin/chromium',
		'/usr/bin/chromium-browser',
		'/usr/bin/google-chrome',
		'/usr/bin/microsoft-edge',
		'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
		'C:/Program Files/Google/Chrome/Application/chrome.exe',
		'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
		'C:/Program Files/Microsoft/Edge/Application/msedge.exe'
	];
	const found = candidates.find((c) => c && existsSync(c));
	if (!found) throw new Error('No Chromium, Chrome or Edge found: set YTMP_CHROME to one');
	return found;
}

/** A WAV file of [seconds] of a quiet tone (8 kHz, mono, 16 bit). */
function wav(seconds) {
	const rate = 8000;
	const samples = rate * seconds;
	const b = Buffer.alloc(44 + samples * 2);
	b.write('RIFF', 0);
	b.writeUInt32LE(36 + samples * 2, 4);
	b.write('WAVEfmt ', 8);
	b.writeUInt32LE(16, 16);
	b.writeUInt16LE(1, 20);
	b.writeUInt16LE(1, 22);
	b.writeUInt32LE(rate, 24);
	b.writeUInt32LE(rate * 2, 28);
	b.writeUInt16LE(2, 32);
	b.writeUInt16LE(16, 34);
	b.write('data', 36);
	b.writeUInt32LE(samples * 2, 40);
	for (let i = 0; i < samples; i++) b.writeInt16LE(Math.round(Math.sin((2 * Math.PI * 440 * i) / rate) * 3000), 44 + i * 2);
	return b;
}

/** The WAV for a song: its ID says its length (`ytm:len3-…`). */
const files = new Map();
function audioFor(songId) {
	const seconds = Number(/len(\d+)/.exec(songId)?.[1]);
	if (!seconds) return null;
	if (!files.has(seconds)) files.set(seconds, wav(seconds));
	return files.get(seconds);
}

function sendAudio(req, res, data) {
	const range = /bytes=(\d+)-(\d*)/.exec(req.headers.range ?? '');
	const headers = { 'Content-Type': 'audio/wav', 'Accept-Ranges': 'bytes', 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' };
	if (!range) {
		res.writeHead(200, { ...headers, 'Content-Length': data.length });
		return res.end(data);
	}
	const start = Number(range[1]);
	const end = range[2] ? Math.min(Number(range[2]), data.length - 1) : data.length - 1;
	res.writeHead(206, { ...headers, 'Content-Length': end - start + 1, 'Content-Range': `bytes ${start}-${end}/${data.length}` });
	res.end(data.subarray(start, end + 1));
}

const json = (res, value, status = 200) => {
	res.writeHead(status, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
	res.end(JSON.stringify(value));
};

async function startServer(script) {
	const server = createServer(async (req, res) => {
		const url = new URL(req.url, 'http://x');
		const path = url.pathname;
		let m;
		if (path === '/') {
			res.writeHead(200, { 'Content-Type': 'text/html' });
			return res.end('<!doctype html><meta charset="utf-8"><title>Player test</title><script type="module" src="/harness.js"></script>');
		}
		if (path === '/harness.js') {
			res.writeHead(200, { 'Content-Type': 'text/javascript' });
			return res.end(script);
		}
		// The host: its audio, and whether it has the song saved.
		if ((m = /^\/(?:computer\/)?api\/audio\/([^/]+)\/saved$/.exec(path))) return json(res, { saved: true });
		if ((m = /^\/(?:computer\/|module\/files\/)?api\/audio\/([^/]+)$/.exec(path))) {
			const data = audioFor(decodeURIComponent(m[1]));
			if (!data) return json(res, { error: { message: 'no such song' } }, 404);
			return sendAudio(req, res, data);
		}
		// This computer's host (the desktop app): it hears about a song before it's asked for it, late.
		if (req.method === 'OPTIONS') {
			res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': '*' });
			return res.end();
		}
		if (path === '/computer/api/desktop/started' || path === '/computer/api/desktop/known') {
			for await (const _ of req);
			await sleep(path.endsWith('started') ? LATE_MS : 0);
			return json(res, { here: true });
		}
		// Your own module: a song's link, late.
		if (path === '/module/info') return json(res, { name: 'ytmp-ytm', version: 'test' });
		if ((m = /^\/module\/songs\/([^/]+)\/stream$/.exec(path))) {
			const id = decodeURIComponent(m[1]);
			const seconds = Number(/len(\d+)/.exec(id)?.[1]);
			// A song YouTube won't give it (`…-fail-…`).
			if (id.includes('-fail-')) return json(res, { error: { code: 'unavailable', message: 'This song is not available' } }, 404);
			await sleep(seconds >= 5 ? LONG_LOOKUP_MS : LATE_MS);
			return json(res, { url: `http://${req.headers.host}/module/files/api/audio/${m[1]}`, expiresAt: Date.now() + 3600_000 });
		}
		// Problem reports and the like.
		if (path.startsWith('/api/')) {
			for await (const _ of req);
			res.writeHead(204);
			return res.end();
		}
		res.writeHead(404);
		res.end();
	});
	await new Promise((r) => server.listen(0, '127.0.0.1', r));
	return { url: `http://127.0.0.1:${server.address().port}`, stop: () => server.close() };
}

async function buildHarness() {
	const out = mkdtempSync(join(tmpdir(), 'ytmp-player-test-'));
	await build({
		configFile: false,
		root: frontend,
		logLevel: 'warn',
		plugins: [
			svelte({ compilerOptions: { runes: true } }),
			{
				// SvelteKit's `$app/state` (appUpdate): no new version of the page here.
				name: 'app-state',
				resolveId: (id) => (id === '$app/state' ? '\0app-state' : null),
				load: (id) => (id === '\0app-state' ? 'export const updated = { current: false };' : null)
			}
		],
		build: {
			outDir: out,
			emptyOutDir: true,
			minify: false,
			target: 'esnext',
			lib: { entry: join(frontend, 'tests/player/harness.ts'), formats: ['es'], fileName: () => 'harness.js' }
		}
	});
	const script = readFileSync(join(out, 'harness.js'), 'utf8');
	rmSync(out, { recursive: true, force: true });
	return script;
}

/** The browser over the DevTools protocol: one page, where JavaScript runs and errors are kept. */
async function startChrome() {
	const profile = mkdtempSync(join(tmpdir(), 'ytmp-player-profile-'));
	const chrome = spawn(
		findChrome(),
		[
			'--headless=new',
			'--mute-audio',
			'--autoplay-policy=no-user-gesture-required',
			'--no-first-run',
			'--no-default-browser-check',
			'--disable-extensions',
			'--remote-debugging-port=0',
			`--user-data-dir=${profile}`,
			'about:blank'
		],
		{ stdio: ['ignore', 'ignore', 'pipe'] }
	);
	const wsUrl = await new Promise((resolve, reject) => {
		let out = '';
		chrome.stderr.on('data', (d) => {
			out += d;
			const m = /DevTools listening on (ws:\S+)/.exec(out);
			if (m) resolve(m[1]);
		});
		chrome.on('exit', () => reject(new Error(`The browser didn't start: ${out}`)));
	});
	const ws = new WebSocket(wsUrl);
	await new Promise((r) => ws.addEventListener('open', r, { once: true }));
	let nextId = 0;
	const waiting = new Map();
	const listeners = [];
	ws.addEventListener('message', (e) => {
		const msg = JSON.parse(e.data);
		if (msg.id !== undefined) {
			const w = waiting.get(msg.id);
			waiting.delete(msg.id);
			if (msg.error) w.reject(new Error(msg.error.message));
			else w.resolve(msg.result);
		} else for (const l of listeners) l(msg);
	});
	const send = (method, params = {}, sessionId) =>
		new Promise((resolve, reject) => {
			const id = ++nextId;
			waiting.set(id, { resolve, reject });
			ws.send(JSON.stringify({ id, method, params, sessionId }));
		});
	const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
	const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
	const errors = [];
	listeners.push((msg) => {
		if (msg.sessionId !== sessionId) return;
		if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') errors.push(msg.params.args.map((a) => a.value ?? a.description).join(' '));
		if (msg.method === 'Runtime.exceptionThrown') errors.push(msg.params.exceptionDetails.exception?.description ?? msg.params.exceptionDetails.text);
	});
	await send('Runtime.enable', {}, sessionId);
	await send('Page.enable', {}, sessionId);
	const evaluate = async (expression) => {
		const { result, exceptionDetails } = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, sessionId);
		if (exceptionDetails) throw new Error(exceptionDetails.exception?.description ?? exceptionDetails.text);
		return result.value;
	};
	return {
		errors,
		evaluate,
		goto: (url) => send('Page.navigate', { url }, sessionId),
		stop: async () => {
			chrome.kill();
			await new Promise((r) => (chrome.exitCode !== null ? r() : chrome.once('exit', r)));
			// Windows keeps the profile locked a moment longer.
			for (let i = 0; i < 10; i++) {
				try {
					rmSync(profile, { recursive: true, force: true });
					return;
				} catch {
					await sleep(300);
				}
			}
		}
	};
}

const only = process.argv.slice(2);
const script = await buildHarness();
const server = await startServer(script);
const page = await startChrome();
let failed = 0;
try {
	await page.goto(server.url);
	for (let i = 0; i < 50 && !(await page.evaluate('!!window.playerTests').catch(() => false)); i++) await sleep(100);
	const names = await page.evaluate('window.playerTests.names');
	for (const name of names.filter((n) => only.length === 0 || only.includes(n))) {
		// Each on a fresh page: nothing carries over from the last test.
		await page.goto(server.url);
		for (let i = 0; i < 50 && !(await page.evaluate('!!window.playerTests').catch(() => false)); i++) await sleep(100);
		const results = await page.evaluate(`window.playerTests.run(${JSON.stringify(name)})`);
		for (const r of results) {
			console.log(`${r.ok ? 'ok  ' : 'FAIL'} ${r.name}${r.ok ? '' : `\n       ${r.detail.length > 400 ? `${r.detail.slice(0, 400)}…` : r.detail}`}`);
			if (!r.ok) failed++;
		}
	}
	if (page.errors.length) console.log(`\nErrors in the page:\n  ${page.errors.join('\n  ')}`);
} finally {
	await page.stop();
	server.stop();
}
if (failed) {
	console.log(`\n${failed} failed`);
	process.exit(1);
}
console.log('\nThe player: all fine');

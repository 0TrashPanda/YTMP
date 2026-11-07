#!/usr/bin/env node
// The web app in Chrome 90, the browser of older Chromecasts (a TV showing the party screen):
// a room page joins and is styled, and a party screen as the phone casts it joins the room.
//
//   node tools/chrome90-test.mjs
//
// Needs Chromium 90 (downloaded once, ~150 MB, from Google's Chromium build archive into
// ~/.cache/ytmp/chromium-90), the built web app (cd frontend && pnpm build) and the server
// (./gradlew :server:installDist, Java 21). It starts its own server on a free port.
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir, homedir } from 'node:os';
import { join } from 'node:path';

const repo = new URL('..', import.meta.url).pathname;
const CHROME_DIR = join(homedir(), '.cache/ytmp/chromium-90');
const CHROME = join(CHROME_DIR, 'chrome-linux/chrome');
// Chromium 90.0.4430.0 (the build archive has no build at exactly the branch point).
const CHROME_ZIP = 'https://commondatastorage.googleapis.com/chromium-browser-snapshots/Linux_x64/857949/chrome-linux.zip';
const SERVER = join(repo, 'server/build/install/server/bin/server');
const JAVA_HOME = process.env.JAVA_HOME ?? '/usr/lib/jvm/java-21-openjdk';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const failures = [];
const check = (ok, what) => {
	console.log(`${ok ? 'ok  ' : 'FAIL'} ${what}`);
	if (!ok) failures.push(what);
};

function freePort() {
	return new Promise((resolve) => {
		const s = createServer().listen(0, () => {
			const { port } = s.address();
			s.close(() => resolve(port));
		});
	});
}

async function ensureChrome() {
	if (existsSync(CHROME)) return;
	console.log(`Downloading Chromium 90 into ${CHROME_DIR}…`);
	mkdirSync(CHROME_DIR, { recursive: true });
	const zip = join(CHROME_DIR, 'chrome-linux.zip');
	const response = await fetch(CHROME_ZIP);
	if (!response.ok) throw new Error(`Chromium 90 download: HTTP ${response.status}`);
	writeFileSync(zip, Buffer.from(await response.arrayBuffer()));
	await run('unzip', ['-q', '-o', zip, '-d', CHROME_DIR]);
	rmSync(zip);
}

function run(cmd, args) {
	return new Promise((resolve, reject) => {
		spawn(cmd, args, { stdio: 'inherit' }).on('exit', (code) => (code === 0 ? resolve() : reject(new Error(`${cmd} exited with ${code}`))));
	});
}

async function startServer() {
	if (!existsSync(join(repo, 'frontend/build/index.html'))) throw new Error('Build the web app first: cd frontend && pnpm build');
	if (!existsSync(SERVER)) throw new Error('Build the server first: ./gradlew :server:installDist');
	const dir = mkdtempSync(join(tmpdir(), 'ytmp-chrome90-'));
	writeFileSync(join(dir, 'ytmp.toml'), '');
	const port = await freePort();
	const server = spawn(SERVER, ['ytmp.toml'], {
		cwd: dir,
		env: { ...process.env, JAVA_HOME, YTMP_PORT: String(port), YTMP_FRONTEND: join(repo, 'frontend/build') },
		stdio: 'ignore'
	});
	const url = `http://127.0.0.1:${port}`;
	for (let i = 0; i < 60; i++) {
		if (await fetch(`${url}/api/host`).then((r) => r.ok, () => false)) return { url, stop: () => (server.kill(), rmSync(dir, { recursive: true, force: true })) };
		await sleep(500);
	}
	server.kill();
	throw new Error('The server did not start');
}

/** Chrome 90 over the DevTools protocol (today's Playwright can't drive a browser this old). */
async function startChrome() {
	const profile = mkdtempSync(join(tmpdir(), 'ytmp-chrome90-profile-'));
	const chrome = spawn(CHROME, ['--headless', '--no-sandbox', '--disable-gpu', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--autoplay-policy=no-user-gesture-required', 'about:blank'], {
		stdio: ['ignore', 'ignore', 'pipe']
	});
	const wsUrl = await new Promise((resolve, reject) => {
		let out = '';
		chrome.stderr.on('data', (d) => {
			out += d;
			const m = /DevTools listening on (ws:\S+)/.exec(out);
			if (m) resolve(m[1]);
		});
		chrome.on('exit', () => reject(new Error(`Chrome 90 didn't start: ${out}`)));
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

	/** A new tab: its errors are collected, and it can open a URL and run JavaScript. */
	async function newPage() {
		const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
		const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
		const errors = [];
		listeners.push((msg) => {
			if (msg.sessionId !== sessionId) return;
			if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') errors.push(msg.params.args.map((a) => a.value ?? a.description).join(' '));
			if (msg.method === 'Runtime.exceptionThrown') {
				const d = msg.params.exceptionDetails;
				errors.push(d.exception?.description?.split('\n')[0] ?? d.text);
			}
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
			/** Waits until [expression] is true (up to [ms]). */
			async waitFor(expression, ms = 10_000) {
				for (const end = Date.now() + ms; Date.now() < end; await sleep(200)) {
					if (await evaluate(expression).catch(() => false)) return true;
				}
				return false;
			}
		};
	}
	return { newPage, stop: () => (chrome.kill(), rmSync(profile, { recursive: true, force: true })) };
}

// Tailwind's styles apply (they sit in CSS layers, newer than Chrome 90).
const STYLED = `(() => { const el = document.querySelector('.flex'); return !!el && getComputedStyle(el).display === 'flex'; })()`;
const text = (s) => `document.body.innerText.includes(${JSON.stringify(s)})`;

await ensureChrome();
const server = await startServer();
const chrome = await startChrome();
try {
	const room = await (await fetch(`${server.url}/api/rooms`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"name":"Chrome 90"}' })).json();

	// Someone joins the room in Chrome 90.
	const page = await chrome.newPage();
	await page.goto(`${server.url}/room/${room.code}`);
	check(await page.waitFor(`!!document.querySelector('input[placeholder="Your name"]')`), 'room page: the join form shows');
	check(await page.evaluate(STYLED), 'room page: styled');
	await page.evaluate(`(() => {
		const input = document.querySelector('input[placeholder="Your name"]');
		input.value = 'Tester';
		input.dispatchEvent(new Event('input', { bubbles: true }));
	})()`);
	await sleep(300);
	await page.evaluate(`document.querySelector('input[placeholder="Your name"]').form.requestSubmit()`);
	check(await page.waitFor(`${text('Nothing playing')} && !${text('Connecting')}`), 'room page: joined the room');
	const guestToken = await page.evaluate(`Object.keys(localStorage).filter((k) => k.startsWith('ytmp.guest.')).map((k) => localStorage.getItem(k))[0] ?? null`);
	check(!!guestToken, 'room page: has a guest token');
	check(page.errors.length === 0, `room page: no errors${page.errors.length ? `: ${page.errors.join('; ')}` : ''}`);

	// The party screen as the phone casts it to a Chromecast (/tv#cast=CODE.TOKEN).
	if (!guestToken) throw new Error(`Couldn't join the room: ${await page.evaluate('document.body.innerText')}`);
	const linked = await (
		await fetch(`${server.url}/api/screens/cast`, {
			method: 'POST',
			headers: { 'content-type': 'application/json' },
			body: JSON.stringify({ roomCode: room.code, guestToken, name: 'Chrome 90 TV' })
		})
	).json();
	const tv = await chrome.newPage();
	await tv.goto(`${server.url}/tv#cast=${encodeURIComponent(linked.roomCode)}.${encodeURIComponent(linked.guestToken)}`);
	check(await tv.waitFor(text('Nothing playing yet')), 'party screen: shows the room');
	check(await tv.waitFor(`!${text('Reconnecting')}`, 5000), 'party screen: joined the room (not reconnecting)');
	check(await tv.evaluate(STYLED), 'party screen: styled');
	check(tv.errors.length === 0, `party screen: no errors${tv.errors.length ? `: ${tv.errors.join('; ')}` : ''}`);
} finally {
	chrome.stop();
	server.stop();
}
if (failures.length) {
	console.log(`\n${failures.length} failed in Chrome 90`);
	process.exit(1);
}
console.log('\nChrome 90: all fine');

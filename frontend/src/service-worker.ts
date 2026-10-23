/// <reference types="@sveltejs/kit" />
/// <reference no-default-lib="true"/>
/// <reference lib="esnext" />
/// <reference lib="webworker" />
import { assets, immutable } from '$app/manifest';
import { version } from '$app/env';

// Makes the web app installable and quick to open: the app's own files (they never change for a
// version) come from the cache; the page itself is asked from the host first, so a server update
// shows at once. The API, the room connection and all audio are never touched.
const sw = self as unknown as ServiceWorkerGlobalScope;
const CACHE = `ytmp-${version}`;
const ASSETS = new Set<string>([...immutable, ...assets].map((file) => `/${file.path}`));

sw.addEventListener('install', (event) => {
	event.waitUntil(
		(async () => {
			const cache = await caches.open(CACHE);
			await cache.addAll([...ASSETS]);
			// The app's one page (the host answers it for every path).
			await cache.add(new Request('/', { cache: 'reload' }));
			await sw.skipWaiting();
		})()
	);
});

sw.addEventListener('activate', (event) => {
	event.waitUntil(
		(async () => {
			for (const key of await caches.keys()) if (key !== CACHE) await caches.delete(key);
			await sw.clients.claim();
		})()
	);
});

sw.addEventListener('fetch', (event) => {
	const request = event.request;
	if (request.method !== 'GET') return;
	const url = new URL(request.url);
	if (url.origin !== sw.location.origin || url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws')) return;

	// The app's files: from the cache.
	if (ASSETS.has(url.pathname)) {
		event.respondWith(caches.match(request).then((hit) => hit ?? fetch(request)));
		return;
	}

	// The page: the host's, and the last one seen when it can't be reached.
	if (request.mode === 'navigate') {
		event.respondWith(
			fetch(request).catch(async () => (await caches.match('/')) ?? Response.error())
		);
	}
});

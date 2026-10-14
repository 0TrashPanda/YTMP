import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';
import { legacyLayers } from './legacy-css';

// During `pnpm dev`, API and WebSocket calls go to the Kotlin server.
const host = process.env.YTMP_DEV_SERVER ?? 'http://localhost:8080';

export default defineConfig({
	plugins: [
		tailwindcss(),
		legacyLayers(),
		sveltekit({
			compilerOptions: {
				// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			// A single-page app: the host serves index.html for every unknown path.
			adapter: adapter({ fallback: 'index.html' })
		})
	],
	// Also older Chromecasts (Chrome 90) showing the party screen: newer syntax is rewritten, and
	// src/lib/polyfills.ts adds the few newer built-ins.
	build: { target: ['chrome90', 'edge90', 'firefox102', 'safari15'] },
	server: {
		proxy: {
			'/api': host,
			'/ws': { target: host.replace(/^http/, 'ws'), ws: true }
		}
	}
});

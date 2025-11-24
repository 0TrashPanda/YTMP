import adapterStatic from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
const config = {
  preprocess: vitePreprocess(),
  kit: {
    adapter: adapterStatic({
      pages: '../server/src/main/resources/static',   // Build output for Ktor
      assets: '../server/src/main/resources/static',
      fallback: 'index.html'                  // SPA fallback
    }),
    paths: { base: '' }
  }
};

export default config;

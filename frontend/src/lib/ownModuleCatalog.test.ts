import { beforeEach, describe, expect, it, vi } from 'vitest';

// Search and pages from your own module first, the room's host when it can't (api.ts, lookUp).

const stored = new Map<string, string>();
vi.stubGlobal('localStorage', {
	getItem: (key: string) => stored.get(key) ?? null,
	setItem: (key: string, value: string) => void stored.set(key, value),
	removeItem: (key: string) => void stored.delete(key)
});

const MODULE = 'http://localhost:8401/';
/** How the module answers: as it should, not at all, YouTube's bot check, or "no such thing". */
let module: 'works' | 'down' | 'blocked' | 'not_found' = 'works';
/** What the module and the host were asked (the module's paths without its address). */
let asked: string[] = [];
let lyrics: { lrclib: object | null; own: object | null; host: object | null } = { lrclib: null, own: null, host: null };

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });

vi.stubGlobal(
	'fetch',
	vi.fn(async (input: URL | string) => {
		const url = String(input);
		if (url.startsWith(MODULE)) {
			const path = url.slice(MODULE.length);
			asked.push(`module ${path}`);
			if (path === 'info') return json({ name: 'ytm', version: '1.0' });
			if (module === 'down') throw new TypeError('Failed to fetch');
			if (module === 'blocked') return json({ error: { code: 'blocked', message: 'bot check' } }, 503);
			if (module === 'not_found') return json({ error: { code: 'not_found', message: 'No such album' } }, 404);
			if (path.startsWith('songs/') && path.endsWith('/lyrics')) return lyrics.own ? json(lyrics.own) : json({ error: { code: 'not_found', message: 'none' } }, 404);
			return json({ from: 'module', path, sections: [], items: ['from module'] });
		}
		asked.push(`host ${url}`);
		if (url.startsWith('/api/lyrics')) {
			const answer = url.includes('from=lrclib') ? lyrics.lrclib : lyrics.host;
			return answer ? json(answer) : json({ error: { code: 'not_found', message: 'none' } }, 404);
		}
		return json({ from: 'host', path: url, sections: [], items: ['from host'] });
	})
);

async function load(on = true) {
	if (on) stored.set('ytmp.ownModule', JSON.stringify({ url: 'http://localhost:8401', key: 'k', on: true }));
	const own = await import('./ownModule.svelte');
	await own.checkOwnModule();
	asked = [];
	return { api: await import('./api'), own };
}

const song = { id: 'ytm:abc', title: 'One More Time', artists: [{ id: null, name: 'Daft Punk' }], album: null, durationMs: 320_000, thumbnails: [] };
const timed = (source: string) => ({ plain: 'x', synced: [{ timeMs: 0, text: 'x' }], source });
const plain = (source: string) => ({ plain: 'x', synced: null, source });

beforeEach(() => {
	vi.resetModules();
	stored.clear();
	module = 'works';
	lyrics = { lrclib: null, own: null, host: null };
});

describe('search and pages with your own module', () => {
	it('come from the module when it’s on', async () => {
		const { api } = await load();
		expect(await api.search('Daft Punk', 'all')).toMatchObject({ from: 'module', path: 'search?q=daft%20punk&type=all&limit=20' });
		expect(await api.suggestions('daf')).toEqual(['from module']);
		expect(await api.getArtist('UCabc')).toMatchObject({ from: 'module', path: 'artists/UCabc' });
		expect(await api.getAlbum('MPREb_1')).toMatchObject({ from: 'module' });
		expect(await api.getPlaylist('VLPL1')).toMatchObject({ from: 'module', path: 'playlists/VLPL1' });
		expect(await api.getPlaylistPart('VLPL1', 'tok')).toMatchObject({ from: 'module', path: 'playlists/VLPL1?more=tok' });
		expect(await api.getPodcast('MPSP1')).toMatchObject({ from: 'module' });
		expect(await api.getSong('ytm:abc')).toMatchObject({ from: 'module', path: 'songs/ytm%3Aabc' });
		expect(await api.getHome()).toMatchObject({ from: 'module', path: 'home' });
		expect(asked.every((a) => a.startsWith('module '))).toBe(true);
	});

	it('come from the host when there’s no module, or it’s switched off', async () => {
		const { api } = await load(false);
		expect(await api.search('x', 'songs')).toMatchObject({ from: 'host', path: '/api/search?q=x&type=songs' });

		vi.resetModules();
		stored.set('ytmp.ownModule', JSON.stringify({ url: 'http://localhost:8401', key: 'k', on: false }));
		const off = await import('./api');
		expect(await off.getArtist('UCabc')).toMatchObject({ from: 'host', path: '/api/artists/UCabc' });
	});

	it('fall back to the host when the module is down, and skip it after that', async () => {
		const { api, own } = await load();
		module = 'down';
		expect(await api.getAlbum('MPREb_1')).toMatchObject({ from: 'host', path: '/api/albums/MPREb_1' });
		await vi.waitFor(() => expect(own.moduleStatus.state).toBe('down'));
		asked = [];
		expect(await api.getAlbum('MPREb_2')).toMatchObject({ from: 'host' });
		expect(asked).toEqual(['host /api/albums/MPREb_2']);
	});

	it('fall back to the host on YouTube’s bot check, and keep asking the module next time', async () => {
		const { api, own } = await load();
		module = 'blocked';
		expect(await api.search('x', 'all')).toMatchObject({ from: 'host' });
		expect(own.moduleStatus.state).toBe('works');
		module = 'works';
		expect(await api.search('y', 'all')).toMatchObject({ from: 'module' });
	});

	it('say "not found" like the host does, without asking it again', async () => {
		const { api } = await load();
		module = 'not_found';
		await expect(api.getAlbum('MPREb_x')).rejects.toMatchObject({ status: 404, message: 'No such album' });
		expect(asked).toEqual(['module albums/MPREb_x']);
	});

	it('leave the host’s own music (local libraries) to the host', async () => {
		const { api } = await load();
		expect(await api.getAlbum('local:lib:album:x')).toMatchObject({ from: 'host' });
		expect(asked).toEqual(['host /api/albums/local%3Alib%3Aalbum%3Ax']);
	});

	it('leave your YouTube Music sign-in’s Home, and with personalize its searches, to the host', async () => {
		const { api } = await load();
		api.useYoutubeHere(true, false);
		expect(await api.getHome()).toMatchObject({ from: 'host', path: '/api/home' });
		expect(await api.search('a', 'all')).toMatchObject({ from: 'module' });
		api.useYoutubeHere(true, true);
		expect(await api.search('b', 'all')).toMatchObject({ from: 'host' });
		expect(await api.getArtist('UCabc')).toMatchObject({ from: 'module' });
	});

	it('ask Home where it came from last time, before the host says whether you’re signed in', async () => {
		let { api } = await load();
		expect(await api.getHome()).toMatchObject({ from: 'module' });
		// The host then says you're signed in: the signed-out Home is forgotten and asked again.
		expect(api.useYoutubeHere(true, false)).toBe(true);
		expect(await api.getHome()).toMatchObject({ from: 'host', path: '/api/home' });
		expect(api.useYoutubeHere(true, false)).toBe(false);
		// Next start: straight from the host, before it has said so again.
		vi.resetModules();
		({ api } = await load());
		stored.delete('ytmp.last.:home');
		expect(await api.getHome()).toMatchObject({ from: 'host' });
	});

	it('leave similar songs (radio) to the host', async () => {
		const { api } = await load();
		await api.similar('ytm:abc');
		expect(asked).toEqual(['host /api/similar?id=ytm%3Aabc']);
	});
});

describe('lyrics with your own module', () => {
	it('LRCLIB’s timed ones from the host come first', async () => {
		const { api } = await load();
		lyrics.lrclib = timed('LRCLIB');
		expect(await api.getLyrics(song as never)).toMatchObject({ source: 'LRCLIB' });
		expect(asked).toHaveLength(1);
		expect(asked[0]).toContain('from=lrclib');
	});

	it('else YouTube Music’s from the module: timed ones win, else LRCLIB’s plain ones', async () => {
		let { api } = await load();
		lyrics = { lrclib: plain('LRCLIB'), own: timed('YouTube Music'), host: null };
		expect(await api.getLyrics(song as never)).toMatchObject({ source: 'YouTube Music' });

		vi.resetModules();
		({ api } = await load());
		lyrics = { lrclib: plain('LRCLIB'), own: plain('YouTube Music'), host: null };
		expect(await api.getLyrics(song as never)).toMatchObject({ source: 'LRCLIB' });

		vi.resetModules();
		({ api } = await load());
		lyrics = { lrclib: null, own: null, host: timed('host') };
		expect(await api.getLyrics(song as never)).toBeNull();
	});

	it('the host’s whole lookup when the module can’t', async () => {
		const { api } = await load();
		module = 'blocked';
		lyrics = { lrclib: null, own: null, host: timed('host') };
		expect(await api.getLyrics(song as never)).toMatchObject({ source: 'host' });
		expect(asked.at(-1)).not.toContain('from=lrclib');
	});
});

describe('in the desktop app', () => {
	it('every module request goes through its shell (stream links, the check, pages), with the same fallback', async () => {
		const shell = vi.fn(async (path: string) => {
			if (path === 'info') return { status: 200, body: JSON.stringify({ name: 'ytm', version: '2.0' }) };
			if (path.endsWith('/stream')) return { status: 200, body: JSON.stringify({ url: 'https://youtube/song', expiresAt: Date.now() + 3_600_000 }) };
			if (path.startsWith('albums/')) return { status: 503, body: JSON.stringify({ error: { code: 'blocked', message: 'bot check' } }) };
			return { status: 200, body: JSON.stringify({ from: 'shell', path }) };
		});
		vi.stubGlobal('window', { YtmpDesktop: { moduleRequest: shell } });
		try {
			const { api, own } = await load();
			expect(own.moduleStatus.text).toBe('ytm module 2.0');
			expect(await own.ownStreamUrl('ytm:abc')).toBe('https://youtube/song');
			expect(await api.getArtist('UCabc')).toMatchObject({ from: 'shell', path: 'artists/UCabc' });
			expect(await api.getAlbum('MPREb_1')).toMatchObject({ from: 'host' });
			expect(asked).toEqual(['host /api/albums/MPREb_1']);
			expect(shell.mock.calls.map(([path]) => path)).toEqual(['info', 'songs/ytm%3Aabc/stream', 'artists/UCabc', 'albums/MPREb_1']);
		} finally {
			vi.stubGlobal('window', undefined);
		}
	});
});

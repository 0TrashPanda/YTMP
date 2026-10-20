// Links pasted in the search box (docs/features/ui.md#search): YouTube Music and YouTube
// links open their song, album, playlist, artist or podcast; a YTMP room link opens the room.

export type Link =
	| { kind: 'song'; id: string; /** The playlist the link plays it in, if any (not YouTube's mixes). */ playlist: string | null }
	| { kind: 'album'; id: string }
	| { kind: 'playlist'; id: string }
	| { kind: 'artist'; id: string }
	| { kind: 'podcast'; id: string }
	| { kind: 'room'; url: string; code: string };

const VIDEO = /^[A-Za-z0-9_-]{11}$/;
const YOUTUBE = ['youtube.com', 'music.youtube.com', 'youtu.be', 'youtube-nocookie.com'];

/** What a pasted link points at, or null when [text] isn't one of those links. */
export function parseLink(text: string): Link | null {
	const t = text.trim();
	if (!t || /\s/.test(t) || !t.includes('/')) return null;
	let url: URL;
	try {
		url = new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(t) ? t : `${guessScheme(t)}://${t}`);
	} catch {
		return null;
	}
	if (url.protocol !== 'https:' && url.protocol !== 'http:') return null;
	const host = url.hostname.toLowerCase().replace(/^(www|m)\./, '');
	const path = url.pathname.replace(/\/+$/, '');
	if (YOUTUBE.includes(host)) return youtube(host, path, url.searchParams);
	const room = path.match(/^\/room\/([A-Za-z0-9]{3,12})$/);
	return room ? { kind: 'room', url: url.href, code: room[1].toUpperCase() } : null;
}

function youtube(host: string, path: string, query: URLSearchParams): Link | null {
	const song = (id: string): Link => ({ kind: 'song', id: `ytm:${id}`, playlist: songPlaylist(query.get('list')) });
	if (host === 'youtu.be') {
		const id = path.slice(1);
		return VIDEO.test(id) ? song(id) : null;
	}
	const v = query.get('v');
	if (path === '/watch' && v && VIDEO.test(v)) return song(v);
	const video = path.match(/^\/(?:shorts|live|embed)\/([A-Za-z0-9_-]{11})$/);
	if (video) return song(video[1]);
	const list = query.get('list');
	// An album's link is its playlist (OLAK5uy_…); the host finds the album.
	if (path === '/playlist' && list) return list.startsWith('OLAK5uy_') ? { kind: 'album', id: list } : { kind: 'playlist', id: list };
	const browse = path.match(/^\/browse\/([A-Za-z0-9_-]+)$/)?.[1];
	if (browse?.startsWith('MPREb_')) return { kind: 'album', id: browse };
	if (browse?.startsWith('MPSP')) return { kind: 'podcast', id: browse };
	if (browse?.startsWith('VL')) return { kind: 'playlist', id: browse.slice(2) };
	if (browse?.startsWith('UC')) return { kind: 'artist', id: browse };
	const channel = path.match(/^\/channel\/(UC[A-Za-z0-9_-]+)$/)?.[1];
	return channel ? { kind: 'artist', id: channel } : null;
}

/** A song link's playlist, unless it's one only that person has (likes, watch later) or YouTube's endless mix. */
function songPlaylist(list: string | null): string | null {
	return list && !list.startsWith('RD') && !['LL', 'WL', 'LM'].includes(list) ? list : null;
}

/** Without a scheme: a phone or a server on the network (an address, a port) is http, the rest https. */
function guessScheme(text: string): 'http' | 'https' {
	const host = text.split('/')[0];
	return /^\d+\.\d+\.\d+\.\d+(:\d+)?$/.test(host) || /:\d+$/.test(host) || host.startsWith('localhost') ? 'http' : 'https';
}

/** What opening it does, for the search box. */
export function linkLabel(link: Link): string {
	switch (link.kind) {
		case 'song':
			return 'Show this song';
		case 'album':
			return 'Open this album';
		case 'playlist':
			return 'Open this playlist';
		case 'artist':
			return 'Open this artist';
		case 'podcast':
			return 'Open this podcast';
		case 'room':
			return `Open room ${link.code}`;
	}
}

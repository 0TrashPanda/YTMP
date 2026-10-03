import type {
	AlbumPage,
	ApiError,
	ArtistPage,
	CreateRoomResponse,
	HomePage,
	HostInfo,
	PlaylistPage,
	PodcastPage,
	RoomInfo,
	RoomListResponse,
	RoomVisibility,
	SearchPage,
	SearchResponse,
	SearchType,
	SuggestionsResponse,
	YoutubeAccountStatus,
	Song
} from './protocol.gen';

export class ApiRequestError extends Error {}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
	const response = await fetch(path, init);
	if (!response.ok) {
		const body = (await response.json().catch(() => null)) as ApiError | null;
		throw new ApiRequestError(body?.error.message ?? `Request failed (${response.status})`);
	}
	return (response.status === 204 ? null : response.json()) as Promise<T>;
}

/** [accountToken] makes your account the owner, on every device you log in on. */
export function createRoom(name: string, visibility: RoomVisibility = 'public', accountToken: string | null = null): Promise<CreateRoomResponse> {
	const headers: Record<string, string> = { 'Content-Type': 'application/json' };
	if (accountToken) headers['Authorization'] = `Bearer ${accountToken}`;
	return request('/api/rooms', { method: 'POST', headers, body: JSON.stringify({ name, visibility }) });
}

/** On the phone: use a YTMP server for accounts, or none (null). */
export async function linkAuthServer(url: string | null): Promise<void> {
	await request<null>('/api/host/auth-server', {
		method: 'PUT',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ url })
	});
}

/** What kind of host served this page (a server, or a phone). */
export function getHost(): Promise<HostInfo> {
	return request('/api/host');
}

/** The rooms on this phone (only works on the phone itself). */
export async function listRooms(): Promise<RoomInfo[]> {
	return (await request<RoomListResponse>('/api/rooms')).rooms;
}

export async function closeRoom(code: string): Promise<void> {
	const response = await fetch(`/api/rooms/${encodeURIComponent(code)}`, { method: 'DELETE' });
	if (!response.ok) throw new ApiRequestError(`Couldn't close the room (${response.status})`);
}

export function getRoom(code: string): Promise<RoomInfo> {
	return request(`/api/rooms/${encodeURIComponent(code)}`);
}

// Browsing (search, similar, artist, album and playlist pages) is remembered for a while, so going
// back and forth is instant. The host caches too; this saves the round trip.
const CACHE_MS = 30 * 60_000;
const cache = new Map<string, { at: number; value: Promise<unknown> }>();

function cached<T>(key: string, load: () => Promise<T>): Promise<T> {
	const hit = cache.get(key);
	if (hit && Date.now() - hit.at < CACHE_MS) return hit.value as Promise<T>;
	const value = load();
	cache.set(key, { at: Date.now(), value });
	// Don't remember failures (or aborted requests).
	value.catch(() => cache.get(key)?.value === value && cache.delete(key));
	if (cache.size > 300) cache.delete(cache.keys().next().value!);
	return value;
}

/** A cached request that one caller aborting doesn't break for the others. */
function abortable<T>(value: Promise<T>, signal?: AbortSignal): Promise<T> {
	if (!signal) return value;
	return new Promise((resolve, reject) => {
		signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
		value.then(resolve, reject);
	});
}

export function search(query: string, type: SearchType, signal?: AbortSignal): Promise<SearchPage> {
	const q = query.trim().toLowerCase();
	return abortable(
		cached(`search:${type}:${q}`, () => request<SearchPage>(`/api/search?q=${encodeURIComponent(q)}&type=${type}`)),
		signal
	);
}

/** What to search for, while typing. Empty when the host has no suggestions. */
export function suggestions(query: string, signal?: AbortSignal): Promise<string[]> {
	const q = query.toLowerCase();
	return abortable(
		cached(`suggest:${q}`, async () => (await request<SuggestionsResponse>(`/api/search/suggestions?q=${encodeURIComponent(q)}`)).items),
		signal
	);
}

/** Songs similar to a song (YTM's radio), for Find similar. */
export function similar(songId: string, signal?: AbortSignal): Promise<Song[]> {
	return abortable(
		cached(`similar:${songId}`, async () => (await request<SearchResponse>(`/api/similar?id=${encodeURIComponent(songId)}`)).items),
		signal
	);
}

export function getArtist(id: string): Promise<ArtistPage> {
	return cached(`artist:${id}`, () => request(`/api/artists/${encodeURIComponent(id)}`));
}

export function getAlbum(id: string): Promise<AlbumPage> {
	return cached(`album:${id}`, () => request(`/api/albums/${encodeURIComponent(id)}`));
}

/** [personal]: one of your own (from your library, e.g. Liked music "LM"), through your YouTube Music sign-in. */
export function getPlaylist(id: string, personal = false): Promise<PlaylistPage> {
	return personal
		? cached(`me:playlist:${id}`, () => request(`/api/me/playlists/${encodeURIComponent(id)}`))
		: cached(`playlist:${id}`, () => request(`/api/playlists/${encodeURIComponent(id)}`));
}

export function getPodcast(id: string): Promise<PodcastPage> {
	return cached(`podcast:${id}`, () => request(`/api/podcasts/${encodeURIComponent(id)}`));
}

/** Suggestions for the home page: quick picks, new releases, mixes. */
export function getHome(): Promise<HomePage> {
	return cached('home', () => request('/api/home'));
}

/** Your YouTube Music sign-in on this host (the phone app only). */
export function getYoutubeAccount(): Promise<YoutubeAccountStatus> {
	return request('/api/me/youtube');
}

export async function signOutOfYoutube(): Promise<void> {
	await request<null>('/api/me/youtube', { method: 'DELETE' });
	forgetPersonal();
}

/** Your playlists (Liked music first) and podcasts in YouTube Music. */
export function getLibrary(): Promise<HomePage> {
	return cached('me:library', () => request('/api/me/library'));
}

/** After signing in or out: the home page and your library change. */
export function forgetPersonal(): void {
	for (const key of [...cache.keys()]) if (key === 'home' || key.startsWith('me:')) cache.delete(key);
}

const pages = { artist: getArtist, album: getAlbum, playlist: getPlaylist, podcast: getPodcast };

/** Starts loading a page you're likely to open (hovering it), so it opens instantly. */
export function prefetch(kind: keyof typeof pages, id: string): void {
	pages[kind](id).catch(() => {});
}

/** Audio streamed through the host, for when the direct stream URL doesn't work. */
export function proxiedAudioUrl(songId: string): string {
	return `/api/audio/${encodeURIComponent(songId)}`;
}

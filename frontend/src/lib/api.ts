import type {
	DownloadItems,
	LocalLinkRequest,
	SongCacheSettings,
	SongCacheStatus,
	TakeoutPreview,
	UnmatchedSongs,
	AlbumPage,
	ApiError,
	ArtistPage,
	ScreenLinked,
	ScreenCastRequest,
	ScreenLinkRequest,
	ScreenUnlinkRequest,
	ScreenPairing,
	CreatePlaylistRequest,
	CreatePlaylistResponse,
	CreateRoomResponse,
	LikeStatus,
	LocalLibraries,
	LocalLibraryPage,
	Lyrics,
	MyPlaylists,
	PlaylistSummary,
	SaveSongsRequest,
	HomePage,
	HostInfo,
	PlaylistPage,
	PodcastPage,
	PodcastSavedRequest,
	RoomInfo,
	RoomListResponse,
	RoomVisibility,
	SearchPage,
	SearchResponse,
	SearchType,
	SuggestionsResponse,
	YoutubeAccountStatus,
	YoutubeHistory,
	YoutubeHistorySetting,
	YoutubeAccount,
	YoutubeSignInRequest,
	SyncedRoomsResponse,
	MovedRoomResponse,
	Song,
	YoutubePersonalizeSetting,
	RoomClone,
	PlaylistItemRef,
	RemovePlaylistItemsRequest,
	MovePlaylistItemRequest,
	RenamePlaylistRequest
} from './protocol.gen';

import { identity } from './account';
import { settings } from './settings.svelte';
import { read, saved, write } from './storage';

export class ApiRequestError extends Error {
	/** The HTTP status, e.g. 404. */
	constructor(
		message: string,
		readonly status = 0
	) {
		super(message);
	}
}

/**
 * Every request carries your account (the host token for this page), if you logged in: on a
 * server it finds your rooms and your YouTube Music. The token only works on this host.
 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
	const headers = new Headers(init?.headers);
	// Your YouTube Music from your own server, in a room on another host (see useYoutubeFrom).
	const remote = path.startsWith('/api/me/') ? youtubeFrom : null;
	const token = remote ? remote.token : identity.token;
	if (token && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`);
	if (remote && init?.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
	return answer<T>(await fetch(remote ? remote.server + path : path, { ...init, headers }));
}

/** What a YTMP host or server answered: its JSON (null for "no content"), or an [ApiRequestError] with its message. */
export async function answer<T>(response: Response): Promise<T> {
	if (!response.ok) {
		const body = (await response.json().catch(() => null)) as ApiError | null;
		throw new ApiRequestError(body?.error.message ?? `Request failed (${response.status})`, response.status);
	}
	return (response.status === 204 ? null : await response.json()) as T;
}

// --- party screens on a TV (docs/features/ui.md#party-screen) ---

/** The TV: a code to show, and the secret to wait with. */
export function startScreenPairing(): Promise<ScreenPairing> {
	return request('/api/screens', { method: 'POST' });
}

/** The TV: waits ~25 s for someone to link it; null means ask again. Throws when the code expired. */
export function waitForScreenLink(secret: string): Promise<ScreenLinked | null> {
	return request(`/api/screens/${encodeURIComponent(secret)}`);
}

/** In a room: show it on the TV that shows [code]. */
export async function linkScreen(code: string, roomCode: string, guestToken: string): Promise<void> {
	await request('/api/screens/link', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ code, roomCode, guestToken } satisfies ScreenLinkRequest)
	});
}

/** A party screen for a TV on your own network, which the app starts (it plays the music too). */
export async function castScreen(roomCode: string, guestToken: string, name: string): Promise<ScreenLinked> {
	return request<ScreenLinked>('/api/screens/cast', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ roomCode, guestToken, name } satisfies ScreenCastRequest)
	});
}

/** That screen stopped: it leaves the room. */
export async function unlinkScreen(roomCode: string, guestToken: string): Promise<void> {
	await request('/api/screens/unlink', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ roomCode, guestToken } satisfies ScreenUnlinkRequest)
	});
}

/** [accountToken] makes your account the owner, on every device you log in on. */
export function createRoom(
	name: string,
	visibility: RoomVisibility = 'public',
	accountToken: string | null = null,
	clone: RoomClone | null = null,
	/** A code of your own choosing; null: a random one. */
	code: string | null = null
): Promise<CreateRoomResponse> {
	const headers: Record<string, string> = { 'Content-Type': 'application/json' };
	if (accountToken) headers['Authorization'] = `Bearer ${accountToken}`;
	return request('/api/rooms', { method: 'POST', headers, body: JSON.stringify({ name, visibility, clone, code }) });
}

/** On the phone's own page: clone a room onto your server (the phone forwards it, with your login). */
export function cloneToServer(name: string, visibility: RoomVisibility, clone: RoomClone, accountToken: string): Promise<CreateRoomResponse> {
	return request('/api/rooms/clone-to-server', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${accountToken}` },
		body: JSON.stringify({ name, visibility, clone })
	});
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

/** Proves you made a room, without an account (see OWNER_TOKEN_HEADER on the host). */
function ownerHeaders(code: string): Record<string, string> {
	const token = saved.ownerToken(code);
	return token ? { 'X-Ytmp-Owner': token } : {};
}

export async function closeRoom(code: string): Promise<void> {
	await request<null>(`/api/rooms/${encodeURIComponent(code)}`, { method: 'DELETE', headers: ownerHeaders(code) });
}

/** Your rooms that live on your other hosts (phone or server), with your account. */
export function getRoomsElsewhere(): Promise<SyncedRoomsResponse> {
	return request('/api/rooms/elsewhere');
}

/** Moves one of your rooms here from your other host; resolves with its code here. */
export async function moveRoomHere(syncId: string): Promise<string> {
	return (await request<MovedRoomResponse>(`/api/rooms/elsewhere/${encodeURIComponent(syncId)}/move`, { method: 'POST' })).code;
}

export function getRoom(code: string): Promise<RoomInfo> {
	return request(`/api/rooms/${encodeURIComponent(code)}`, { headers: ownerHeaders(code) });
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

/**
 * Searches (and suggestions, similar songs) go through your account on your server: in a room
 * on another host, with your YouTube Music from there and "Use your account for search and
 * radio" on (see [useYoutubeFrom]). Else through this host, as usual.
 */
const viaYourServer = () => !!youtubeFrom?.personalize;

export function search(query: string, type: SearchType, signal?: AbortSignal): Promise<SearchPage> {
	const q = query.trim().toLowerCase();
	const base = viaYourServer() ? '/api/me/search' : '/api/search';
	return abortable(
		cached(`${viaYourServer() ? 'me:' : ''}search:${type}:${q}`, () => request<SearchPage>(`${base}?q=${encodeURIComponent(q)}&type=${type}`)),
		signal
	);
}

/** One song by its ID (a link to it was pasted in the search box). */
export function getSong(id: string, signal?: AbortSignal): Promise<Song> {
	return abortable(cached(`song:${id}`, () => request<Song>(`/api/songs/${encodeURIComponent(id)}`)), signal);
}

/** What to search for, while typing. Empty when the host has no suggestions. */
export function suggestions(query: string, signal?: AbortSignal): Promise<string[]> {
	const q = query.toLowerCase();
	const base = viaYourServer() ? '/api/me/search/suggestions' : '/api/search/suggestions';
	return abortable(
		cached(`${viaYourServer() ? 'me:' : ''}suggest:${q}`, async () => (await request<SuggestionsResponse>(`${base}?q=${encodeURIComponent(q)}`)).items),
		signal
	);
}

/** Songs similar to a song (YTM's radio), for Find similar. */
export function similar(songId: string, signal?: AbortSignal): Promise<Song[]> {
	const base = viaYourServer() ? '/api/me/similar' : '/api/similar';
	return abortable(
		cached(`${viaYourServer() ? 'me:' : ''}similar:${songId}`, async () => (await request<SearchResponse>(`${base}?id=${encodeURIComponent(songId)}`)).items),
		signal
	);
}

/** Whether [yourRadio] brings one (it comes from your server), or the room's radio is used. */
export const bringsOwnRadio = () => viaYourServer();

/**
 * A radio you bring to this room (your own account's, from your server), for Start radio and
 * Autoplay this; null when the room's own radio is used (it's the room's, or your account is
 * on this host anyway).
 */
export async function yourRadio(songId: string): Promise<Song[] | null> {
	if (!bringsOwnRadio()) return null;
	try {
		return await similar(songId);
	} catch {
		return null;
	}
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

/** The next songs of a playlist, after the part(s) loaded ([PlaylistPage.more]); [personal]: one of yours. */
export function getPlaylistPart(id: string, more: string, personal = false): Promise<PlaylistPage> {
	const base = personal ? '/api/me/playlists' : '/api/playlists';
	return cached(`${personal ? 'me:' : ''}playlist:${id}:${more}`, () => request(`${base}/${encodeURIComponent(id)}?more=${encodeURIComponent(more)}`));
}

/** The song's lyrics (LRCLIB, or its source's own), or null when nobody has them. */
export function getLyrics(song: Song): Promise<Lyrics | null> {
	const query = new URLSearchParams({
		id: song.id,
		title: song.title,
		artist: song.artists[0]?.name ?? '',
		album: song.album?.name ?? '',
		durationMs: String(song.durationMs)
	});
	return cached(`lyrics:${song.id}`, () =>
		request<Lyrics>(`/api/lyrics?${query}`).catch((e: unknown) => {
			if (e instanceof ApiRequestError && e.status === 404) return null;
			throw e;
		})
	);
}

export function getPodcast(id: string): Promise<PodcastPage> {
	return cached(`podcast:${id}`, () => request(`/api/podcasts/${encodeURIComponent(id)}`));
}

/** A podcast as you (your YouTube Music): whether you're subscribed ([PodcastPage.saved]). */
export function getMyPodcast(id: string): Promise<PodcastPage> {
	return request(`/api/me/podcasts/${encodeURIComponent(id)}`);
}

/** Subscribes to a podcast (adds it to your YouTube Music library), or unsubscribes. Library and Home change with it. */
export async function setPodcastSaved(id: string, saved: boolean): Promise<void> {
	await request<null>(`/api/me/podcasts/${encodeURIComponent(id)}/saved`, { method: 'PUT', body: JSON.stringify({ saved } satisfies PodcastSavedRequest) });
	forgetPersonal();
}

/** Suggestions for the home page: quick picks, new releases, mixes (yours, with your YouTube Music). */
export function getHome(): Promise<HomePage> {
	const path = youtubeFrom ? '/api/me/home' : `/api/home${refreshHome ? '?fresh=1' : ''}`;
	const forced = refreshHome;
	return cached('home', () => sinceLast('home', forced, () => request<HomePage>(path).finally(() => (refreshHome = false))));
}

// The last Home and library are kept in this browser: they show right away, and new ones are
// only loaded once they're older than set in Settings → Home (or when you pull down).
type Last = 'home' | 'me:library';
type Kept = { at: number; page: HomePage };
const lastKey = (key: Last) => `ytmp.last.${youtubeFrom?.server ?? ''}:${key}`;

function kept(key: Last): Kept | null {
	try {
		const value = JSON.parse(read(lastKey(key)) ?? 'null') as Kept | HomePage | null;
		// Kept before they had a time: as old as can be.
		return value && 'sections' in value ? { at: 0, page: value } : value;
	} catch {
		return null;
	}
}

/** The [key] page as it was last loaded here (null: never, or since signing out). */
export function lastSeen<T extends HomePage>(key: Last): T | null {
	return (kept(key)?.page as T | undefined) ?? null;
}

/** The kept [key] page while it's new enough (and not [forced]); else [load]s a new one and keeps that. */
async function sinceLast<T extends HomePage>(key: Last, forced: boolean, load: () => Promise<T>): Promise<T> {
	const last = kept(key);
	const wait = settings.homeRefresh.value * 60_000;
	if (last && !forced && (wait === 0 || Date.now() - last.at < wait)) return last.page as T;
	const page = await load();
	write(lastKey(key), JSON.stringify({ at: Date.now(), page } satisfies Kept));
	return page;
}

let refreshLibrary = false;

/** Your library changed (or you pulled it down): load it again next time (the kept one shows meanwhile). */
export function forgetLibrary(): void {
	cache.delete('me:library');
	refreshLibrary = true;
}

/**
 * Your YouTube Music comes from your own server ([server], with a [token] just for that), not
 * from this host: a room on someone else's phone or server. Null: from this host, as usual.
 */
let youtubeFrom: { server: string; token: string; personalize?: boolean } | null = null;
export function useYoutubeFrom(remote: { server: string; token: string; personalize?: boolean } | null): void {
	if (remote?.server === youtubeFrom?.server && remote?.token === youtubeFrom?.token && remote?.personalize === youtubeFrom?.personalize) return;
	youtubeFrom = remote;
	forgetPersonal();
	cache.delete('home');
}

/** Your YouTube Music sign-in on this host (or, after [useYoutubeFrom], on your server). */
export function getYoutubeAccount(): Promise<YoutubeAccountStatus> {
	return request('/api/me/youtube');
}

export async function setYoutubeHistory(history: YoutubeHistory): Promise<void> {
	await request<null>('/api/me/youtube/history', { method: 'PUT', body: JSON.stringify({ history } satisfies YoutubeHistorySetting) });
}

export async function setYoutubePersonalize(on: boolean): Promise<void> {
	await request<null>('/api/me/youtube/personalize', { method: 'PUT', body: JSON.stringify({ on } satisfies YoutubePersonalizeSetting) });
}

/** On a server: keep this YouTube Music sign-in (cookies, or text that contains them) in your account. */
export function signInToYoutube(cookie: string): Promise<YoutubeAccount> {
	return request('/api/me/youtube', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ cookie } satisfies YoutubeSignInRequest) });
}

export async function signOutOfYoutube(): Promise<void> {
	await request<null>('/api/me/youtube', { method: 'DELETE' });
	forgetPersonal();
}

/** Your playlists (Liked music first) and podcasts in YouTube Music. */
/** The local music this host reaches (a Raspberry Pi's music folder, the music on the hosting phone). */
export function getLocalLibraries(fresh = false): Promise<LocalLibraries> {
	if (fresh) {
		cache.delete('local');
		cache.delete('local:phone');
	}
	return cached('local', () => request('/api/local'));
}

/** One local library, whole: its albums, artists and songs. */
export function getLocalLibrary(id: string): Promise<LocalLibraryPage> {
	return cached(`local:${id}`, () => request(`/api/local/${encodeURIComponent(id)}`));
}

export function getLibrary(): Promise<HomePage> {
	const forced = refreshLibrary;
	return cached('me:library', () => sinceLast('me:library', forced, () => request<HomePage>('/api/me/library').finally(() => (refreshLibrary = false))));
}

/** Your thumbs on a song in YouTube Music: up (liked), down (disliked), or neither. */
export function getRating(songId: string): Promise<LikeStatus> {
	return request<LikeStatus>(`/api/me/likes/${encodeURIComponent(songId)}`);
}

export async function setRating(songId: string, rating: LikeStatus): Promise<void> {
	await request<null>(`/api/me/likes/${encodeURIComponent(songId)}`, {
		method: 'PUT',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(rating satisfies LikeStatus)
	});
	cache.delete('me:playlist:LM');
}

// Your own playlists (PlaylistPage.owned): remove songs, move one, rename, delete.

export async function removeFromPlaylist(playlistId: string, items: PlaylistItemRef[]): Promise<void> {
	await request<null>(`/api/me/playlists/${encodeURIComponent(playlistId)}/remove`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ items } satisfies RemovePlaylistItemsRequest)
	});
	cache.delete(`me:playlist:${playlistId}`);
}

/** [itemId] goes right before [beforeItemId] (null: to the end). */
export async function movePlaylistItem(playlistId: string, itemId: string, beforeItemId: string | null): Promise<void> {
	await request<null>(`/api/me/playlists/${encodeURIComponent(playlistId)}/move`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ itemId, beforeItemId } satisfies MovePlaylistItemRequest)
	});
	cache.delete(`me:playlist:${playlistId}`);
}

export async function renamePlaylist(playlistId: string, title: string): Promise<void> {
	await request<null>(`/api/me/playlists/${encodeURIComponent(playlistId)}`, {
		method: 'PUT',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ title } satisfies RenamePlaylistRequest)
	});
	cache.delete(`me:playlist:${playlistId}`);
	forgetLibrary();
}

export async function deletePlaylist(playlistId: string): Promise<void> {
	await request<null>(`/api/me/playlists/${encodeURIComponent(playlistId)}`, { method: 'DELETE' });
	cache.delete(`me:playlist:${playlistId}`);
	forgetLibrary();
}

/** Your own playlists, to save songs to. */
export async function getMyPlaylists(): Promise<PlaylistSummary[]> {
	return (await request<MyPlaylists>('/api/me/playlists')).playlists;
}

export async function saveToPlaylist(playlistId: string, songIds: string[]): Promise<void> {
	await request<null>(`/api/me/playlists/${encodeURIComponent(playlistId)}/songs`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ songIds } satisfies SaveSongsRequest)
	});
	cache.delete(`me:playlist:${playlistId}`);
}

/** A new private playlist with these songs; returns its ID. */
export async function createPlaylist(title: string, songIds: string[]): Promise<string> {
	const response = await request<CreatePlaylistResponse>('/api/me/playlists', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ title, songIds } satisfies CreatePlaylistRequest)
	});
	forgetLibrary();
	return response.id;
}

let refreshHome = false;

/** Pulled down to refresh: load new suggestions, also past the host's cache. */
export function forgetHome(): void {
	cache.delete('home');
	refreshHome = true;
}

/** After signing in or out: the home page, your library and (with your account) search results change. */
export function forgetPersonal(): void {
	for (const key of [...cache.keys()]) {
		if (key === 'home' || key.startsWith('me:') || key.startsWith('search:') || key.startsWith('suggest:')) cache.delete(key);
	}
	for (const key of ['home', 'me:library'] as Last[]) write(lastKey(key), 'null');
}

const pages = { artist: getArtist, album: getAlbum, playlist: getPlaylist, podcast: getPodcast };

/** Starts loading a page you're likely to open (hovering it), so it opens instantly. */
export function prefetch(kind: keyof typeof pages, id: string): void {
	pages[kind](id).catch(() => {});
}

/**
 * Tells the host about a playback problem on this device (a link that stopped working, long
 * buffering), for its log. Best effort: never fails.
 */
export function reportProblem(message: string, room?: string): void {
	// With your account: accounts-only servers turn away anything without one.
	const headers: Record<string, string> = { 'Content-Type': 'application/json' };
	if (identity.token) headers['Authorization'] = `Bearer ${identity.token}`;
	fetch('/api/log', { method: 'POST', headers, body: JSON.stringify({ message, room }), keepalive: true }).catch(() => {});
}

/** A song made into a file to keep by the host (docs/implementation/downloads.md): its bytes and Content-Disposition. */
export async function downloadFile(song: Song): Promise<{ blob: Blob; disposition: string | null }> {
	const headers: Record<string, string> = { 'Content-Type': 'application/json' };
	if (identity.token) headers['Authorization'] = `Bearer ${identity.token}`;
	const response = await fetch('/api/download', { method: 'POST', headers, body: JSON.stringify(song) });
	if (!response.ok) await answer(response);
	return { blob: await response.blob(), disposition: response.headers.get('Content-Disposition') };
}

// --- the song cache of the phone this page is on (Settings → Playback) ---

export function getSongCache(): Promise<SongCacheStatus> {
	return request('/api/cache');
}

export function setSongCache(settings: SongCacheSettings): Promise<SongCacheStatus> {
	return request('/api/cache', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(settings) });
}

export function clearSongCache(): Promise<SongCacheStatus> {
	return request('/api/cache/clear', { method: 'POST' });
}

/** A Google Takeout export (the zip, or the watch history in it): its YouTube Music plays, for the song cache. */
export function importTakeout(file: File): Promise<SongCacheStatus> {
	return request('/api/cache/takeout', { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: file });
}

export function forgetTakeout(): Promise<SongCacheStatus> {
	return request('/api/cache/takeout', { method: 'DELETE' });
}

/** Which Takeout songs [days] and [minPlays] would keep. */
export function previewTakeout(days: number, minPlays: number): Promise<TakeoutPreview> {
	return request('/api/cache/takeout/preview', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ days, minPlays }) });
}

export function getDownloadQueue(): Promise<DownloadItems> {
	return request('/api/cache/downloads');
}

export function downloadAction(action: 'cancel' | 'retry' | 'now', songId: string): Promise<DownloadItems> {
	return request(`/api/cache/downloads/${action}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ songId }) });
}

export function getUnmatchedSongs(): Promise<UnmatchedSongs> {
	return request('/api/cache/local/unmatched');
}

export function recheckLocalSongs(): Promise<SongCacheStatus> {
	return request('/api/cache/local/recheck', { method: 'POST' });
}

export function forgetLocalSongs(): Promise<SongCacheStatus> {
	return request('/api/cache/local/forget', { method: 'POST' });
}

export function linkLocalSong(link: LocalLinkRequest): Promise<SongCacheStatus> {
	return request('/api/cache/local/link', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(link) });
}

/** Audio streamed through the host, for when the direct stream URL doesn't work. */
export function proxiedAudioUrl(songId: string): string {
	return `/api/audio/${encodeURIComponent(songId)}`;
}

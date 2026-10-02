import type {
	ApiError,
	CreateRoomResponse,
	HostInfo,
	RoomInfo,
	RoomListResponse,
	RoomVisibility,
	SearchResponse,
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

export async function search(query: string, signal?: AbortSignal): Promise<Song[]> {
	const result = await request<SearchResponse>(`/api/search?q=${encodeURIComponent(query)}`, { signal });
	return result.items;
}

/** Audio streamed through the host, for when the direct stream URL doesn't work. */
export function proxiedAudioUrl(songId: string): string {
	return `/api/audio/${encodeURIComponent(songId)}`;
}

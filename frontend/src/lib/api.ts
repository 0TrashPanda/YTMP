import type {
	ApiError,
	CreateRoomResponse,
	RoomInfo,
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
	return response.json() as Promise<T>;
}

export function createRoom(name: string): Promise<CreateRoomResponse> {
	return request('/api/rooms', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ name })
	});
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

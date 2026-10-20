// Your YTMP listening history (docs/features/history-and-recommendations.md), in the room's
// Library. It's kept on your account's server, and pages reach it there: on that server's own
// pages with your login; in the phone's app with your login there (a token made for the app's
// own pages: those only reach your own phone); on another host's pages with a token just for
// this, from /connect. See docs/implementation/auth.md#your-history-on-other-pages.
import { ApiRequestError, getHost } from './api';
import { APP_HOME_ORIGIN, identity, session } from './account';
import type { ApiError, HistoryPage, HostInfo } from './protocol.gen';

interface Access {
	/** Your server's address. */
	server: string;
	token: string;
}

function accessFrom(host: HostInfo): Access | null {
	const login = session.current?.sessionToken;
	if (login && host.kind === 'server') return { server: location.origin, token: login };
	const mine = identity.get(host.authServers);
	if (mine && location.origin === APP_HOME_ORIGIN) {
		const issuer = mine.account.id.split('@').slice(1).join('@');
		const server = host.authServers.find((s) => s.issuer === issuer)?.url;
		if (server) return { server: server.replace(/\/$/, ''), token: mine.token };
	}
	return identity.history;
}

class ListeningHistory {
	/** Your history can be shown here. */
	available = $state(false);
	/** On your server's own pages: there you can also export it and delete days of it. */
	onServer = $state(false);
	#access: Access | null = null;

	async load(): Promise<void> {
		try {
			const host = await getHost();
			this.#access = accessFrom(host);
			this.onServer = this.#access?.server === location.origin;
		} catch {
			this.#access = null;
		}
		this.available = !!this.#access;
	}

	async #call<T>(path: string, method = 'GET'): Promise<T> {
		const access = this.#access;
		if (!access) throw new ApiRequestError('Log in with your YTMP account to see your history');
		let response: Response;
		try {
			response = await fetch(access.server + path, { method, headers: { Authorization: `Bearer ${access.token}` } });
		} catch {
			throw new ApiRequestError(`Can't reach ${new URL(access.server).host}, where your history is`);
		}
		if (!response.ok) {
			const body = (await response.json().catch(() => null)) as ApiError | null;
			throw new ApiRequestError(body?.error.message ?? `Request failed (${response.status})`, response.status);
		}
		return (response.status === 204 ? null : await response.json()) as T;
	}

	/** Newest first: the songs played before [before] (ms; null: the newest), matching [query]. */
	page(before: number | null, query: string): Promise<HistoryPage> {
		const params = new URLSearchParams({ limit: '50' });
		if (before !== null) params.set('before', String(before));
		if (query.trim()) params.set('q', query.trim());
		return this.#call<HistoryPage>(`/api/account/history?${params}`);
	}

	async remove(id: string): Promise<void> {
		await this.#call<null>(`/api/account/history/${encodeURIComponent(id)}`, 'DELETE');
	}
}

export const listening = new ListeningHistory();

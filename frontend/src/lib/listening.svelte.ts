// Your YTMP listening history (docs/features/history-and-recommendations.md), in the room's
// Library, and how far you got in podcast episodes (their played marks). It's kept on your account's server, and pages reach it there: on that server's own
// pages with your login; in the phone's app with your login there (a token made for the app's
// own pages: those only reach your own phone); on another host's pages with a token just for
// this, from /connect. See docs/implementation/auth.md#your-history-on-other-pages.
import { answer, ApiRequestError, getHost } from './api';
import { APP_HOME_ORIGIN, identity, session } from './account';
import type { EpisodeProgress, EpisodeProgressList, GraphBuildStatus, GraphTuning, GraphView, HistoryPage, HostInfo, MarkEpisodeRequest } from './protocol.gen';

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

	/** A call to your account's server (also for your song menu's layout, which is kept the same way). */
	async call<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
		const access = this.#access;
		if (!access) throw new ApiRequestError('Log in with your YTMP account to see your history');
		let response: Response;
		try {
			const headers: Record<string, string> = { Authorization: `Bearer ${access.token}` };
			if (body !== undefined) headers['Content-Type'] = 'application/json';
			response = await fetch(access.server + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
		} catch {
			throw new ApiRequestError(`Can't reach ${new URL(access.server).host}, where your history is`);
		}
		return answer<T>(response);
	}

	/** Newest first: the songs played before [before] (ms; null: the newest), matching [query]. */
	page(before: number | null, query: string): Promise<HistoryPage> {
		const params = new URLSearchParams({ limit: '50' });
		if (before !== null) params.set('before', String(before));
		if (query.trim()) params.set('q', query.trim());
		return this.call<HistoryPage>(`/api/account/history?${params}`);
	}

	async remove(id: string): Promise<void> {
		await this.call<null>(`/api/account/history/${encodeURIComponent(id)}`, 'DELETE');
	}

	/** How far you got in these podcast episodes (by song ID), for those that have any. */
	async episodes(ids: string[]): Promise<Map<string, EpisodeProgress>> {
		if (!ids.length) return new Map();
		const list = await this.call<EpisodeProgressList>(`/api/account/episodes?ids=${encodeURIComponent(ids.join(','))}`);
		return new Map(list.episodes.map((e) => [e.id, e]));
	}

	/** The song graph around [seed] (a song ID), or your most played songs without one, with raw counts (graph.ts weighs them). */
	graph(seed: string | null): Promise<GraphView> {
		return this.call<GraphView>(`/api/account/graph${seed ? `?seed=${encodeURIComponent(seed)}` : ''}`);
	}

	/** Whether the song graph is being built again on your server, and how far it got. */
	buildStatus(): Promise<GraphBuildStatus> {
		return this.call<GraphBuildStatus>('/api/account/graph/status');
	}

	/** How much each part of the song graph counts for your radios. */
	setTuning(tuning: GraphTuning): Promise<GraphTuning> {
		return this.call<GraphTuning>('/api/account/graph/tuning', 'PUT', tuning);
	}

	/** Marks an episode played, or unplayed (from the start). */
	async markEpisode(id: string, played: boolean): Promise<void> {
		await this.call<null>(`/api/account/episodes/${encodeURIComponent(id)}`, 'PUT', { played } satisfies MarkEpisodeRequest);
	}
}

export const listening = new ListeningHistory();

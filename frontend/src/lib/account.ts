// Accounts. Two sides (see docs/implementation/auth.md):
//
// - On the auth server's own pages (/account, /connect): a login *session*, used for the
//   account API. Passwords are only ever typed here.
// - On any host's pages: an *identity*, a host token the auth server made for this page's
//   origin, handed over by /connect in the URL fragment. It goes into the room hello.

import { answer, ApiRequestError } from './api';
import { nativeBridge } from './native';
import type {
	SongCacheStatus,
	AccountInfo,
	AccountSettings,
	HistoryPage,
	AccountListResponse,
	AuthServerInfo,
	AuthServerRef,
	HostInfo,
	HostTokenResponse,
	InviteResponse,
	InviteListResponse,
	UpdateAccountRequest,
	AdminSettings,
	AuditLogResponse,
	ServerSettingOverrides,
	AdminRoomList,
	ServerAbout,
	RoleTemplate,
	SessionResponse,
	YoutubeSignInRequest
} from './protocol.gen';

function read<T>(key: string): T | null {
	try {
		const value = localStorage.getItem(key);
		return value ? (JSON.parse(value) as T) : null;
	} catch {
		return null;
	}
}

function write(key: string, value: unknown): void {
	try {
		if (value === null) localStorage.removeItem(key);
		else localStorage.setItem(key, JSON.stringify(value));
	} catch {
		// Not saved; you'll have to log in again next time.
	}
}

// --- session (auth server pages) ----------------------------------------------------------

export const session = {
	get current(): SessionResponse | null {
		return read<SessionResponse>('ytmp.session');
	},
	set current(value: SessionResponse | null) {
		write('ytmp.session', value);
	}
};

async function call<T>(path: string, body?: unknown, method: string = body === undefined ? 'GET' : 'POST'): Promise<T> {
	const headers: Record<string, string> = {};
	if (body !== undefined) headers['Content-Type'] = 'application/json';
	const token = session.current?.sessionToken;
	if (token) headers['Authorization'] = `Bearer ${token}`;
	const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
	if (response.status === 401 && token && !path.endsWith('/login')) session.current = null;
	return answer<T>(response);
}

export const authServer = {
	info: () => call<AuthServerInfo>('/api/auth/info'),

	async signup(username: string, password: string, displayName: string, invite: string | null) {
		session.current = await call<SessionResponse>('/api/account/signup', { username, password, displayName, invite });
	},
	async login(username: string, password: string) {
		session.current = await call<SessionResponse>('/api/account/login', { username, password });
	},
	async logout() {
		await call<null>('/api/account/logout', {}).catch(() => {});
		session.current = null;
	},
	/** Checks the session is still valid (and refreshes the account info). */
	async refresh(): Promise<AccountInfo | null> {
		const current = session.current;
		if (!current) return null;
		try {
			const account = await call<AccountInfo>('/api/account');
			session.current = { ...current, account };
			return account;
		} catch {
			return session.current?.account ?? null;
		}
	},
	async changePassword(currentPassword: string, newPassword: string) {
		session.current = await call<SessionResponse>('/api/account/password', { currentPassword, newPassword });
	},
	hostToken: (origin: string) => call<HostTokenResponse>('/api/account/host-token', { origin }),
	/** Your YouTube Music sign-in kept in this account (only with a login here), to use it on your phone too. */
	youtubeCookie: async () => (await call<YoutubeSignInRequest>('/api/account/youtube/cookie')).cookie,

	roleTemplate: () => call<RoleTemplate>('/api/account/role-template'),
	saveRoleTemplate: (template: RoleTemplate) => call<RoleTemplate>('/api/account/role-template', template, 'PUT'),

	settings: () => call<AccountSettings>('/api/account/settings'),
	saveSettings: (settings: AccountSettings) => call<AccountSettings>('/api/account/settings', settings, 'PUT'),
	/** Your default room password (null: none). Answers your settings. */
	setRoomPassword: (password: string | null) => call<AccountSettings>('/api/account/room-password', { password }, 'PUT'),
	/** A token for your YouTube Music here, for the pages of rooms on other hosts. */
	youtubeToken: () => call<HostTokenResponse>('/api/account/youtube-token', undefined, 'POST'),
	/** A token for your listening history here, for the pages of rooms on other hosts. */
	historyToken: () => call<HostTokenResponse>('/api/account/history-token', undefined, 'POST'),
	history: (before: number | null, query: string) => {
		const params = new URLSearchParams({ limit: '50' });
		if (before !== null) params.set('before', String(before));
		if (query.trim()) params.set('q', query.trim());
		return call<HistoryPage>(`/api/account/history?${params}`);
	},
	deletePlay: (id: string) => call<null>(`/api/account/history/${encodeURIComponent(id)}`, undefined, 'DELETE'),
	/** Deletes plays from [from] up to [to] (ms); both null deletes everything. */
	deleteHistory: (from: number | null, to: number | null) => {
		const params = new URLSearchParams();
		if (from !== null) params.set('from', String(from));
		if (to !== null) params.set('to', String(to));
		if (from === null && to === null) params.set('all', 'true');
		return call<null>(`/api/account/history?${params}`, undefined, 'DELETE');
	},
	/** Downloads the whole history as a JSON file. */
	async exportHistory(username: string) {
		const response = await fetch('/api/account/history/export', { headers: { Authorization: `Bearer ${session.current?.sessionToken}` } });
		if (!response.ok) throw new ApiRequestError(`Export failed (${response.status})`);
		const link = document.createElement('a');
		link.href = URL.createObjectURL(await response.blob());
		link.download = `ytmp-history-${username}.json`;
		link.click();
		URL.revokeObjectURL(link.href);
	},

	// The server admin's page (Settings → Server admin).
	listAccounts: async () => (await call<AccountListResponse>('/api/admin/accounts')).accounts,
	createAccount: (username: string, password: string, displayName: string, isAdmin = false) =>
		call<AccountInfo>('/api/admin/accounts', { username, password, displayName, isAdmin }),
	/** Renames an account or makes it admin (or not); null leaves that as it is. */
	updateAccount: (username: string, change: Partial<UpdateAccountRequest>) =>
		call<AccountInfo>(`/api/admin/accounts/${encodeURIComponent(username)}`, { displayName: null, isAdmin: null, ...change }, 'PUT'),
	setPassword: (username: string, password: string) =>
		call<null>(`/api/admin/accounts/${encodeURIComponent(username)}/password`, { password }),
	/** Disables an account for [minutes], or (null) until it is enabled again. */
	disableAccount: (username: string, minutes: number | null) =>
		call<AccountInfo>(`/api/admin/accounts/${encodeURIComponent(username)}/disable`, { minutes }),
	enableAccount: (username: string) => call<AccountInfo>(`/api/admin/accounts/${encodeURIComponent(username)}/enable`, {}),
	auditLog: async () => (await call<AuditLogResponse>('/api/admin/audit')).entries,
	logOutEverywhere: (username: string) => call<null>(`/api/admin/accounts/${encodeURIComponent(username)}/logout`, {}),
	deleteAccount: (username: string) => call<null>(`/api/admin/accounts/${encodeURIComponent(username)}`, undefined, 'DELETE'),
	createInvite: () => call<InviteResponse>('/api/admin/invites', {}),
	listInvites: async () => (await call<InviteListResponse>('/api/admin/invites')).invites,
	revokeInvite: (id: string) => call<null>(`/api/admin/invites/${encodeURIComponent(id)}`, undefined, 'DELETE'),
	serverSettings: () => call<AdminSettings>('/api/admin/settings'),
	saveServerSettings: (overrides: ServerSettingOverrides) => call<AdminSettings>('/api/admin/settings', overrides, 'PUT'),
	listRooms: async () => (await call<AdminRoomList>('/api/admin/rooms')).rooms,
	closeRoom: (code: string) => call<null>(`/api/admin/rooms/${encodeURIComponent(code)}`, undefined, 'DELETE'),
	about: () => call<ServerAbout>('/api/admin/about'),
	/** The server's song cache: how full it is, and Clear cache (its size is in the server settings). */
	songCache: () => call<SongCacheStatus>('/api/admin/cache'),
	clearSongCache: () => call<SongCacheStatus>('/api/admin/cache/clear', {})
};

// --- identity (any host's pages) ----------------------------------------------------------

/** Who you are on this host's pages, if you logged in through an auth server it trusts. */
export type Identity = HostTokenResponse;

const FRAGMENT = 'ytmp-connect=';

export const identity = {
	/** The identity for this host, if it's still valid and from one of [servers]. */
	get(servers: AuthServerRef[]): Identity | null {
		const value = read<Identity>('ytmp.identity');
		if (!value || value.expiresAt < Date.now()) return null;
		const issuer = value.account.id.split('@').slice(1).join('@');
		return servers.some((s) => s.issuer === issuer) ? value : null;
	},
	/** The stored identity, without checking it against the host's auth servers. */
	get stored(): Identity | null {
		const value = read<Identity>('ytmp.identity');
		return value && value.expiresAt > Date.now() ? value : null;
	},
	get token(): string | null {
		return this.stored?.token ?? null;
	},
	forget() {
		write('ytmp.identity', null);
		write('ytmp.youtubeFrom', null);
		write('ytmp.historyFrom', null);
	},

	/** In the app, on the phone's own pages: takes a login that your server's pages handed over (see [logInOnce]). */
	takeFromApp(): void {
		const shared = nativeBridge?.takeIdentity?.();
		if (!shared) return;
		try {
			const value = JSON.parse(shared) as Identity;
			if (value.token && value.account && value.expiresAt > Date.now()) write('ytmp.identity', value);
		} catch {
			// Broken; ignore.
		}
	},

	/**
	 * Your YouTube Music on your own server, for this host's pages (from /connect, when you said
	 * so): [server] and a [token] that's only good for that.
	 */
	get youtube(): { server: string; token: string } | null {
		const value = read<{ server: string; token: string; expiresAt: number }>('ytmp.youtubeFrom');
		return value && value.expiresAt > Date.now() ? value : null;
	},

	/** Your listening history on your own server, for this host's pages (from /connect, with your YouTube Music). */
	get history(): { server: string; token: string } | null {
		const value = read<{ server: string; token: string; expiresAt: number }>('ytmp.historyFrom');
		return value && value.expiresAt > Date.now() ? value : null;
	},

	/**
	 * Takes an identity that /connect put in the URL fragment, and removes it from the URL. A
	 * quiet check ([connectQuietly]) may also come back with `none` (not logged in there) or
	 * `declined` ("Not now": don't ask again for a while).
	 */
	takeFromUrl(): void {
		if (!location.hash.startsWith(`#${FRAGMENT}`)) return;
		const answer = decodeURIComponent(location.hash.slice(FRAGMENT.length + 1));
		if (answer === 'declined') {
			write('ytmp.connectDeclined', Date.now());
		} else if (answer !== 'none') {
			try {
				type Remote = { server: string; token: string; expiresAt: number };
				const value = JSON.parse(answer) as Identity & { youtube?: Remote; history?: Remote };
				if (value.token && value.account) {
					const { youtube, history, ...mine } = value;
					write('ytmp.identity', mine);
					write('ytmp.youtubeFrom', youtube?.token ? youtube : null);
					write('ytmp.historyFrom', history?.token ? history : null);
				}
			} catch {
				// Not ours or broken; ignore.
			}
		}
		history.replaceState(history.state, '', location.pathname + location.search);
	},

	/** Where to go to log in with [server]; you come back to this page. [quiet]: see [connectQuietly]. */
	loginUrl(server: AuthServerRef, quiet = false): string {
		const base = server.url ?? '';
		return `${base}/connect?${quiet ? 'quiet=1&' : ''}return=${encodeURIComponent(location.href)}`;
	}
};

/** After "Not now" on a host, it doesn't ask again for this long. */
const DECLINED_MS = 7 * 24 * 3600_000;

/**
 * On another host's pages (a phone on the Wi-Fi) that don't know you yet: if this browser is
 * logged in on your server, you're logged in here too, without typing anything. The page goes
 * to the server's /connect for a moment; it comes straight back with your login (a host you
 * said yes to before), asks once ("Join rooms on … as …?"), or comes back without one (not
 * logged in there). Once per browser session per host, not on the server's own pages or the
 * app's, and not when the server can't be reached (a phone party without internet).
 * Returns true when the page is going there.
 */
export async function connectQuietly(host: HostInfo): Promise<boolean> {
	// The app's own pages get your login from the app.
	if (location.origin === APP_HOME_ORIGIN) return false;
	if (identity.get(host.authServers)) return false;
	const server = host.authServers.find((s) => s.url && new URL(s.url).origin !== location.origin);
	if (!server?.url) return false;
	const declined = read<number>('ytmp.connectDeclined');
	if (declined && Date.now() - declined < DECLINED_MS) return false;
	try {
		if (sessionStorage.getItem('ytmp.quietConnect')) return false;
		sessionStorage.setItem('ytmp.quietConnect', '1');
	} catch {
		return false;
	}
	try {
		// Only to see that it answers (it doesn't allow reading the answer from here).
		await fetch(`${server.url}/api/auth/info`, { mode: 'no-cors', signal: AbortSignal.timeout(2500) });
	} catch {
		return false;
	}
	location.href = identity.loginUrl(server, true);
	return true;
}

// --- log in once ---------------------------------------------------------------------------

/** The app's own pages (the phone's built-in host, android/.../LocalHost.kt). */
export const APP_HOME_ORIGIN = 'http://127.0.0.1:8765';

/** An identity this close to running out is renewed. */
const RENEW_MS = 7 * 24 * 3600_000;

/** How often the app gets a fresh login from this server's pages. */
const SHARE_EVERY_MS = 12 * 3600_000;

/**
 * Log in once (docs/features/accounts.md#log-in-once). On a server's own pages, a login there
 * (a session, e.g. from /account) is also your identity for its rooms, without /connect. In the
 * app, it's also handed to the phone's own pages. Returns your identity on this host.
 */
export async function logInOnce(host: HostInfo): Promise<Identity | null> {
	let mine = identity.get(host.authServers);
	const current = session.current;
	if (host.kind !== 'server' || !current) return mine;
	if (!mine || mine.account.id !== current.account.id || mine.expiresAt - Date.now() < RENEW_MS) {
		try {
			mine = await authServer.hostToken(location.origin);
			write('ytmp.identity', mine);
		} catch {
			// Logged out meanwhile, or offline: as before.
		}
	}
	void shareWithApp();
	return mine;
}

/** In the app: gives the phone's own pages a login too (the app only takes it from your linked server). */
async function shareWithApp(): Promise<void> {
	const current = session.current;
	if (!nativeBridge?.shareIdentity || !current) return;
	const last = read<{ session: string; at: number }>('ytmp.sharedWithApp');
	if (last && last.session === current.sessionToken && Date.now() - last.at < SHARE_EVERY_MS) return;
	try {
		nativeBridge.shareIdentity(JSON.stringify(await authServer.hostToken(APP_HOME_ORIGIN)));
		write('ytmp.sharedWithApp', { session: current.sessionToken, at: Date.now() });
	} catch {
		// Next time.
	}
}

/**
 * On /connect: sends [token] (and maybe [youtube] and [history], your YouTube Music and listening
 * history here) back to the page at [returnUrl].
 */
export function returnWithIdentity(returnUrl: string, token: HostTokenResponse, youtube?: HostTokenResponse, history?: HostTokenResponse): void {
	const url = new URL(returnUrl);
	const remote = (t: HostTokenResponse) => ({ server: location.origin, token: t.token, expiresAt: t.expiresAt });
	const extra = { ...(youtube ? { youtube: remote(youtube) } : {}), ...(history ? { history: remote(history) } : {}) };
	url.hash = FRAGMENT + encodeURIComponent(JSON.stringify({ ...token, ...extra }));
	location.href = url.toString();
}

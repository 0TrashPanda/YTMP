// Accounts. Two sides (see docs/implementation/auth.md):
//
// - On the auth server's own pages (/account, /connect): a login *session*, used for the
//   account API. Passwords are only ever typed here.
// - On any host's pages: an *identity*, a host token the auth server made for this page's
//   origin, handed over by /connect in the URL fragment. It goes into the room hello.

import { ApiRequestError } from './api';
import { nativeBridge } from './native';
import type {
	AccountInfo,
	AccountSettings,
	HistoryPage,
	AccountListResponse,
	ApiError,
	AuthServerInfo,
	AuthServerRef,
	HostInfo,
	HostTokenResponse,
	InviteResponse,
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
	if (!response.ok) {
		const error = (await response.json().catch(() => null)) as ApiError | null;
		throw new ApiRequestError(error?.error.message ?? `Request failed (${response.status})`);
	}
	return (response.status === 204 ? null : await response.json()) as T;
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

	listAccounts: async () => (await call<AccountListResponse>('/api/admin/accounts')).accounts,
	createAccount: (username: string, password: string, displayName: string) =>
		call<AccountInfo>('/api/admin/accounts', { username, password, displayName }),
	setPassword: (username: string, password: string) =>
		call<null>(`/api/admin/accounts/${encodeURIComponent(username)}/password`, { password }),
	createInvite: () => call<InviteResponse>('/api/admin/invites', {})
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

	/** Takes an identity that /connect put in the URL fragment, and removes it from the URL. */
	takeFromUrl(): void {
		if (!location.hash.startsWith(`#${FRAGMENT}`)) return;
		try {
			const value = JSON.parse(decodeURIComponent(location.hash.slice(FRAGMENT.length + 1))) as Identity;
			if (value.token && value.account) write('ytmp.identity', value);
		} catch {
			// Not ours or broken; ignore.
		}
		history.replaceState(history.state, '', location.pathname + location.search);
	},

	/** Where to go to log in with [server]; you come back to this page. */
	loginUrl(server: AuthServerRef): string {
		const base = server.url ?? '';
		return `${base}/connect?return=${encodeURIComponent(location.href)}`;
	}
};

// --- log in once ---------------------------------------------------------------------------

/** The app's own pages (the phone's built-in host, android/.../LocalHost.kt). */
const APP_HOME_ORIGIN = 'http://127.0.0.1:8765';

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

/** On /connect: sends [token] back to the page at [returnUrl]. */
export function returnWithIdentity(returnUrl: string, token: HostTokenResponse): void {
	const url = new URL(returnUrl);
	url.hash = FRAGMENT + encodeURIComponent(JSON.stringify(token));
	location.href = url.toString();
}

// Accounts. Two sides (see docs/implementation/auth.md):
//
// - On the auth server's own pages (/account, /connect): a login *session*, used for the
//   account API. Passwords are only ever typed here.
// - On any host's pages: an *identity*, a host token the auth server made for this page's
//   origin, handed over by /connect in the URL fragment. It goes into the room hello.

import { ApiRequestError } from './api';
import type {
	AccountInfo,
	AccountListResponse,
	ApiError,
	AuthServerInfo,
	AuthServerRef,
	HostTokenResponse,
	InviteResponse,
	SessionResponse
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

async function call<T>(path: string, body?: unknown, method = body === undefined ? 'GET' : 'POST'): Promise<T> {
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

/** On /connect: sends [token] back to the page at [returnUrl]. */
export function returnWithIdentity(returnUrl: string, token: HostTokenResponse): void {
	const url = new URL(returnUrl);
	url.hash = FRAGMENT + encodeURIComponent(JSON.stringify(token));
	location.href = url.toString();
}

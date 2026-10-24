// Your preferences, kept on your YTMP account (GET/PUT /api/account/preferences) so they're the
// same on all your devices, and in this browser: without an account, and to start with before
// the account answers. Each feature has its own keys (like "songMenu"). The account is reached
// the way your history is (listening.svelte.ts): this works where your history can be shown.
// Coming back to the page asks the account again, so a change on another device shows up; what
// came from the account is announced with a `ytmp:settings` event.
import { listening } from './listening.svelte';
import { read, write } from './storage';

const KEY = 'ytmp.preferences';
/** Coming back to the page asks the account again at most this often. */
const REFRESH_MS = 10_000;

function cached(): Record<string, unknown> {
	try {
		const value = JSON.parse(read(KEY) ?? '{}') as unknown;
		return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
	} catch {
		return {};
	}
}

class Preferences {
	#values = $state<Record<string, unknown>>(cached());
	/** Your account answered: changes go there too. */
	onAccount = $state(false);
	/** Saving on your account failed (they're kept in this browser). */
	error = $state(false);
	#loading: Promise<void> | null = null;
	/** Keys changed and not on the account yet. */
	#changed = new Set<string>();
	#saving: ReturnType<typeof setTimeout> | undefined;
	#asked = 0;

	constructor() {
		if (typeof document === 'undefined') return;
		document.addEventListener('visibilitychange', () => {
			if (document.visibilityState === 'visible') void this.refresh();
		});
	}

	/** The value of [key] (undefined: not set). Reactive: it changes when the account answers. */
	get<T>(key: string): T | undefined {
		return this.#values[key] as T | undefined;
	}

	/** Sets [key] (undefined: back to not set) here, and on your account shortly after. */
	set(key: string, value: unknown): void {
		const next = { ...this.#values };
		if (value === undefined) delete next[key];
		else next[key] = $state.snapshot(value);
		this.#values = next;
		write(KEY, JSON.stringify(next));
		this.#changed.add(key);
		// A few clicks in a row (moving something down a list) go up once.
		clearTimeout(this.#saving);
		if (this.onAccount) this.#saving = setTimeout(() => void this.#upload(), 600);
	}

	async #upload(): Promise<void> {
		if (!this.#changed.size) return;
		const change = Object.fromEntries([...this.#changed].map((k) => [k, this.#values[k] ?? null]));
		this.#changed.clear();
		try {
			await listening.call<unknown>('/api/account/preferences', 'PUT', change);
			this.error = false;
		} catch {
			for (const k of Object.keys(change)) this.#changed.add(k);
			this.error = true;
		}
	}

	/**
	 * Takes your preferences from your account, once per page. What this browser has and the
	 * account doesn't (set before logging in, or the first time) goes up to the account.
	 */
	load(): Promise<void> {
		return (this.#loading ??= (async () => {
			await listening.load();
			if (!listening.available) {
				this.#loading = null;
				return;
			}
			try {
				const stored = await this.#ask();
				const mine = this.#values;
				for (const k of Object.keys(mine)) if (!(k in stored) || this.#changed.has(k)) this.#changed.add(k);
				this.#take(stored);
				this.onAccount = true;
				await this.#upload();
			} catch {
				// Not reachable now: this browser's; the next page tries again.
				this.#loading = null;
			}
		})());
	}

	/** Asks the account again (a change made on another device); what changed here and isn't up yet stays. */
	async refresh(): Promise<void> {
		if (!this.onAccount || Date.now() - this.#asked < REFRESH_MS) return;
		try {
			this.#take(await this.#ask());
		} catch {
			// Next time.
		}
	}

	async #ask(): Promise<Record<string, unknown>> {
		this.#asked = Date.now();
		return (await listening.call<Record<string, unknown> | null>('/api/account/preferences')) ?? {};
	}

	/** Takes the account's [stored] preferences, keeping the keys changed here. */
	#take(stored: Record<string, unknown>): void {
		const mine = this.#values;
		const next = { ...stored };
		for (const k of this.#changed) {
			if (k in mine) next[k] = mine[k];
			else delete next[k];
		}
		if (JSON.stringify(next) === JSON.stringify(mine)) return;
		this.#values = next;
		write(KEY, JSON.stringify(next));
		if (typeof window !== 'undefined') window.dispatchEvent(new Event('ytmp:settings'));
	}
}

export const preferences = new Preferences();

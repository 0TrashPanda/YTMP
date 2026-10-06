// Your YouTube Music sign-in. On a phone it belongs to the phone's own host: signing in
// happens in the app (android/.../YoutubeLoginActivity.kt), and the cookies stay there. On a
// server it belongs to your YTMP account there: you sign in through the app, or paste the
// cookies from a browser, and the server keeps them (server/.../YoutubeLinks.kt).
import { forgetPersonal, getHost, getYoutubeAccount, setYoutubeHistory, setYoutubePersonalize, signInToYoutube, signOutOfYoutube } from './api';
import { identity } from './account';
import { nativeBridge } from './native';
import type { YoutubeAccount, YoutubeHistory } from './protocol.gen';

class YoutubeSignIn {
	/** You can sign in here: the phone app on its own host, or logged in to your account on a server. */
	available = $state(false);
	/** This host is a server, where your account keeps the sign-in. */
	onServer = $state(false);
	account = $state<YoutubeAccount | null>(null);
	/** Which songs played here go into your YouTube Music history. */
	history = $state<YoutubeHistory>('solo');
	/** Your searches go through this account (personal results, like in YouTube Music). */
	personalize = $state(true);
	/** On the phone: your linked YTMP server, if you're logged in there (its YouTube Music sign-in can be used here too). */
	linkedServer = $state<string | null>(null);
	/** Changes with every sign-in or sign-out, to reload what depends on it. */
	version = $state(0);
	busy = $state(false);

	/** On a server, in the app: the YouTube Music account the phone is signed in to, to use it here too. */
	get phoneAccount(): YoutubeAccount | null {
		if (!this.onServer) return null;
		try {
			const json = nativeBridge?.phoneYoutubeAccount?.();
			return json ? (JSON.parse(json) as YoutubeAccount) : null;
		} catch {
			return null;
		}
	}

	/** On the phone: use the YouTube Music sign-in of your account on [linkedServer] (its page hands it to the app). */
	useServerSignIn(): void {
		if (this.linkedServer) location.href = `${this.linkedServer}/account/youtube-app`;
	}

	/** On a server: the app can sign in for you (bridge version 3). */
	get canUseApp(): boolean {
		return this.onServer && !!nativeBridge?.youtubeCookie;
	}

	async load(): Promise<void> {
		try {
			const [status, host] = await Promise.all([getYoutubeAccount(), getHost()]);
			this.onServer = host.kind === 'server';
			const server = host.authServers.find((s) => s.url)?.url ?? null;
			this.linkedServer = !this.onServer && identity.get(host.authServers) ? server : null;
			this.available = status.available && (this.onServer || !!nativeBridge?.youtubeSignIn);
			this.account = status.account;
			this.history = status.history;
			this.personalize = status.personalize;
		} catch {
			// Offline, or an older host: no sign-in.
		}
	}

	/** Signs in (the app on a phone, or for a server). Resolves with null when signed in, else why not. */
	signIn(): Promise<string | null> {
		return this.onServer ? this.signInWithApp() : this.signInOnPhone();
	}

	/** On a server: keeps what you pasted (the cookies, or a request that has them) in your account. */
	async signInWithText(text: string): Promise<string | null> {
		this.busy = true;
		try {
			await signInToYoutube(text);
			await this.signedIn();
			return null;
		} catch (e) {
			return e instanceof Error ? e.message : "Couldn't sign in";
		} finally {
			this.busy = false;
		}
	}

	private signInOnPhone(): Promise<string | null> {
		return new Promise((resolve) => {
			if (!nativeBridge?.youtubeSignIn) return resolve('Signing in only works in the app');
			this.busy = true;
			window.__ytmpNative = {
				...window.__ytmpNative,
				onYoutubeSignIn: async (error) => {
					if (!error) await this.signedIn();
					this.busy = false;
					resolve(error);
				}
			};
			nativeBridge.youtubeSignIn();
		});
	}

	private signInWithApp(): Promise<string | null> {
		return new Promise((resolve) => {
			if (!nativeBridge?.youtubeCookie) return resolve('Signing in with the app needs a newer version of the app');
			this.busy = true;
			window.__ytmpNative = {
				...window.__ytmpNative,
				onYoutubeCookie: async (cookie, error) => {
					this.busy = false;
					// Cancelled: nothing to say.
					if (!cookie) return resolve(error ?? '');
					resolve(await this.signInWithText(cookie));
				}
			};
			nativeBridge.youtubeCookie();
		});
	}

	private async signedIn(): Promise<void> {
		forgetPersonal();
		await this.load();
		this.version++;
	}

	async setHistory(history: YoutubeHistory): Promise<void> {
		const before = this.history;
		this.history = history;
		try {
			await setYoutubeHistory(history);
		} catch (e) {
			this.history = before;
			throw e;
		}
	}

	async setPersonalize(on: boolean): Promise<void> {
		const before = this.personalize;
		this.personalize = on;
		try {
			await setYoutubePersonalize(on);
			forgetPersonal();
		} catch (e) {
			this.personalize = before;
			throw e;
		}
	}

	async signOut(): Promise<void> {
		await signOutOfYoutube();
		this.account = null;
		this.version++;
	}
}

export const youtube = new YoutubeSignIn();

// Your YouTube Music sign-in. Only the phone app has one, on its own host: signing in
// happens in the app (android/.../YoutubeLoginActivity.kt), and the cookies stay there.
import { forgetPersonal, getYoutubeAccount, setYoutubeHistory, signOutOfYoutube } from './api';
import { nativeBridge } from './native';
import type { YoutubeAccount, YoutubeHistory } from './protocol.gen';

class YoutubeSignIn {
	/** This host can sign in for you (the phone app). */
	available = $state(false);
	account = $state<YoutubeAccount | null>(null);
	/** Which songs played here go into your YouTube Music history. */
	history = $state<YoutubeHistory>('solo');
	/** Changes with every sign-in or sign-out, to reload what depends on it. */
	version = $state(0);
	busy = $state(false);

	async load(): Promise<void> {
		try {
			const status = await getYoutubeAccount();
			this.available = status.available && !!nativeBridge?.youtubeSignIn;
			this.account = status.account;
			this.history = status.history;
		} catch {
			// Offline, or an older host: no sign-in.
		}
	}

	/** Opens the sign-in in the app. Resolves with null when signed in, else why not. */
	signIn(): Promise<string | null> {
		return new Promise((resolve) => {
			if (!nativeBridge?.youtubeSignIn) return resolve('Signing in only works in the app');
			this.busy = true;
			window.__ytmpNative = {
				...window.__ytmpNative!,
				onYoutubeSignIn: async (error) => {
					if (!error) {
						forgetPersonal();
						await this.load();
						this.version++;
					}
					this.busy = false;
					resolve(error);
				}
			};
			nativeBridge.youtubeSignIn();
		});
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

	async signOut(): Promise<void> {
		await signOutOfYoutube();
		this.account = null;
		this.version++;
	}
}

export const youtube = new YoutubeSignIn();

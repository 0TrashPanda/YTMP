// Small things remembered in this browser. Storage can be unavailable (private mode),
// so every access is wrapped and falls back to "nothing saved".

function read(key: string): string | null {
	try {
		return localStorage.getItem(key);
	} catch {
		return null;
	}
}

function write(key: string, value: string): void {
	try {
		localStorage.setItem(key, value);
	} catch {
		// Not saved; the app still works.
	}
}

const code = (roomCode: string) => roomCode.toUpperCase();

/** How far the phone's Back button goes in a room: its home page, or out to the room list. */
export type BackLimit = 'home' | 'rooms';

/** When headphones are unplugged or Bluetooth disconnects (the app). */
export type HeadphonesAction = 'pause' | 'stop' | 'keep';

function pick<T extends string>(key: string, options: readonly T[], fallback: T): T {
	const value = read(key);
	return options.includes(value as T) ? (value as T) : fallback;
}

export const saved = {
	get displayName() {
		return read('ytmp.name') ?? '';
	},
	set displayName(name: string) {
		write('ytmp.name', name);
	},
	guestToken: (roomCode: string) => read(`ytmp.guest.${code(roomCode)}`),
	setGuestToken: (roomCode: string, token: string) => write(`ytmp.guest.${code(roomCode)}`, token),
	ownerToken: (roomCode: string) => read(`ytmp.owner.${code(roomCode)}`),
	setOwnerToken: (roomCode: string, token: string) => write(`ytmp.owner.${code(roomCode)}`, token),
	forgetOwnerToken: (roomCode: string) => {
		try {
			localStorage.removeItem(`ytmp.owner.${code(roomCode)}`);
		} catch {
			// Nothing saved anyway.
		}
	},
	/** Codes of the rooms made in this browser (it has their owner tokens). */
	get ownedRooms(): string[] {
		try {
			return Object.keys(localStorage)
				.filter((key) => key.startsWith('ytmp.owner.'))
				.map((key) => key.slice('ytmp.owner.'.length));
		} catch {
			return [];
		}
	},
	/** Keep me out of other people's listening history (sent when joining a room). */
	get hideFromHistory() {
		return read('ytmp.hideFromHistory') === '1';
	},
	set hideFromHistory(on: boolean) {
		write('ytmp.hideFromHistory', on ? '1' : '0');
	},
	get listening() {
		return read('ytmp.listening') === '1';
	},
	set listening(on: boolean) {
		write('ytmp.listening', on ? '1' : '0');
	},
	/** Sync adjustment for this device, in ms (positive = play earlier). */
	get syncOffsetMs() {
		const value = Number(read('ytmp.syncOffset'));
		return Number.isFinite(value) ? value : 0;
	},
	set syncOffsetMs(value: number) {
		write('ytmp.syncOffset', String(value));
	},
	/** You turned "this device" off in this solo room (solo rooms play here by default). */
	soloOff: (roomCode: string) => read(`ytmp.soloOff.${code(roomCode)}`) === '1',
	setSoloOff: (roomCode: string, off: boolean) => write(`ytmp.soloOff.${code(roomCode)}`, off ? '1' : '0'),
	/** Recent searches, newest first. */
	get recentSearches(): string[] {
		try {
			const list = JSON.parse(read('ytmp.recentSearches') ?? '[]');
			return Array.isArray(list) ? list.filter((q) => typeof q === 'string') : [];
		} catch {
			return [];
		}
	},
	set recentSearches(list: string[]) {
		write('ytmp.recentSearches', JSON.stringify(list.slice(0, 20)));
	},
	get volume() {
		const value = Number(read('ytmp.volume'));
		return Number.isFinite(value) && read('ytmp.volume') !== null ? value : 0.8;
	},
	set volume(value: number) {
		write('ytmp.volume', String(value));
	},
	/** Back at a room's home page: stay (the app goes to the background) or go to the room list. */
	backLimit: (solo: boolean): BackLimit => pick(solo ? 'ytmp.backLimit.solo' : 'ytmp.backLimit.party', ['home', 'rooms'], solo ? 'home' : 'rooms'),
	setBackLimit: (solo: boolean, limit: BackLimit) => write(solo ? 'ytmp.backLimit.solo' : 'ytmp.backLimit.party', limit),
	/** Pause the music (when you're the only one listening), stop playing here, or keep playing on the speaker. */
	get headphonesAction(): HeadphonesAction {
		return pick('ytmp.headphones', ['pause', 'stop', 'keep'], 'pause');
	},
	set headphonesAction(action: HeadphonesAction) {
		write('ytmp.headphones', action);
	}
};

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
	}
};

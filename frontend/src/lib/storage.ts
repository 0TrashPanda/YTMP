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
	get listening() {
		return read('ytmp.listening') === '1';
	},
	set listening(on: boolean) {
		write('ytmp.listening', on ? '1' : '0');
	},
	get volume() {
		const value = Number(read('ytmp.volume'));
		return Number.isFinite(value) && read('ytmp.volume') !== null ? value : 0.8;
	},
	set volume(value: number) {
		write('ytmp.volume', String(value));
	}
};

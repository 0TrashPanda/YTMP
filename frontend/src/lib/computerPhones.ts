// Songs from your phone (the desktop app, docs/implementation/apps/desktop-app.md#songs-from-your-phone-):
// copying your phone's songs into this computer's song cache over Wi-Fi. Only your phones:
// - Through your server: *Copy from my phone* asks it, and your phone (YTMP open on it, waiting
//   on the server) answers at once with where it is; then this computer copies straight from it.
// - Without the server: *Send songs to my computer* on the phone shows it on the Wi-Fi with your
//   account's mark; a computer that copied from it once (it has the phone's key) copies by itself.
// The app's host (desktop/host: PhoneCopy) does the copying.
import { authServer, session } from './account';
import { desktopApp } from './ownModule.svelte';
import type { MyPhone } from './protocol.gen';

export interface PhoneCopyStatus {
	phone: string;
	running: boolean;
	total: number;
	copied: number;
	bytes: number;
	here: number;
	noRoom: number;
	current: string | null;
	error: string | null;
	finishedAt: number | null;
}

/** Which of the phone's songs to copy: its song cache, its local songs (downloads and own music files), or both. */
export type CopyWhich = 'cache' | 'both' | 'local';

interface HostPhones {
	found: { id: string; name: string; url: string; owner: string | null; keyed: boolean }[];
	copy: PhoneCopyStatus | null;
	which: CopyWhich;
}

export interface PhonesNow {
	/** Your phones that answered your server or show themselves on this network, by id. */
	phones: { id: string; name: string; url: string; seenAt: number }[];
	copy: PhoneCopyStatus | null;
	/** Your server still waits for your phone to answer (until then, ms). */
	askedUntil: number | null;
	which: CopyWhich;
}

async function host<T>(path: string, body?: unknown): Promise<T> {
	const base = desktopApp()?.host;
	if (!base) throw new Error('Only in the desktop app');
	const response = await fetch(base + path, {
		method: body === undefined ? 'GET' : 'POST',
		headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
		body: body === undefined ? undefined : JSON.stringify(body)
	});
	const answer = await response.json().catch(() => null);
	if (!response.ok) throw new Error(answer?.error?.message ?? `this computer's host answered ${response.status}`);
	return answer as T;
}

/** Your phones now: what your server heard from them (logged in), and what shows itself here. */
export async function phonesNow(): Promise<PhonesNow> {
	const account = session.current?.account.id;
	const [server, here] = await Promise.all([
		session.current ? authServer.phoneCopy().catch(() => null) : Promise.resolve(null),
		host<HostPhones>(`/api/desktop/phones${account ? `?account=${encodeURIComponent(account)}` : ''}`)
	]);
	const phones = new Map<string, { id: string; name: string; url: string; seenAt: number }>();
	for (const p of here.found) phones.set(p.id, { id: p.id, name: p.name, url: p.url, seenAt: 0 });
	for (const p of server?.phones ?? []) phones.set(p.id, p);
	return { phones: [...phones.values()].sort((a, b) => a.name.localeCompare(b.name)), copy: here.copy, askedUntil: server?.askedUntil ?? null, which: here.which ?? 'cache' };
}

/** How long your server waits for your phone after you ask (AccountApi.PHONE_ASK_MS). */
const ASK_MS = 30 * 60_000;

/** Asks your phones through your server; the ones with YTMP open answer within a second. When it asked, in the server's clock. */
export async function askMyPhones(): Promise<number> {
	if (!session.current) throw new Error("Log in first, or press Send songs to my computer on your phone (once this computer has copied from it)");
	const state = await authServer.askPhones();
	return (state.askedUntil ?? Date.now() + ASK_MS) - ASK_MS;
}

/** Copies [phone]'s songs here: with its key when this computer has one, else with your login for it. */
export async function copyFrom(phone: MyPhone | { id: string; name: string; url: string }): Promise<void> {
	const token = session.current ? await authServer.hostToken(phone.url).then((t) => t.token).catch(() => null) : null;
	await host('/api/desktop/phones/copy', { phone: { id: phone.id, name: phone.name, url: phone.url }, token });
}

/** Which songs to copy from now on (also the copies that start by themselves). */
export const chooseWhich = (which: CopyWhich) => host('/api/desktop/phones/which', { which });

export const stopCopying = () => host('/api/desktop/phones/stop', {});

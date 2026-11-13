// Songs from your phone (the desktop app): the phones on this computer's network (YTMP open on
// them), and copying a phone's song cache into this computer's over Wi-Fi. The app's host
// (desktop/host: PhoneCopy) does the copying; the phone only gives its songs to its owner, so
// the window first gets a login for that phone from your server (a host token for its address).
import { authServer, session } from './account';
import { desktopApp } from './ownModule.svelte';

export interface PhoneHere {
	id: string;
	name: string;
	url: string;
}

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
}

export interface PhonesState {
	phones: PhoneHere[];
	copy: PhoneCopyStatus | null;
}

async function ask(path: string, body?: unknown): Promise<PhonesState> {
	const host = desktopApp()?.host;
	if (!host) throw new Error('Only in the desktop app');
	const response = await fetch(host + path, {
		method: body === undefined ? 'GET' : 'POST',
		headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
		body: body === undefined ? undefined : JSON.stringify(body)
	});
	const answer = await response.json().catch(() => null);
	if (!response.ok) throw new Error(answer?.error?.message ?? `this computer's host answered ${response.status}`);
	return answer as PhonesState;
}

export const phonesHere = () => ask('/api/desktop/phones');

/** Copies [phone]'s songs here, logged in as you (your server makes the login for the phone). */
export async function copyFromPhone(phone: PhoneHere): Promise<PhonesState> {
	if (!session.current) throw new Error('Log in first: the phone only gives its songs to its owner');
	const { token } = await authServer.hostToken(phone.url);
	return ask('/api/desktop/phones/copy', { phoneId: phone.id, token });
}

export const stopCopying = () => ask('/api/desktop/phones/stop', {});

import { beforeEach, describe, expect, it, vi } from 'vitest';

// A phone's page (another host than your server).
const page = { origin: 'http://192.168.1.23:8765', href: 'http://192.168.1.23:8765/room/ABCD' };
vi.stubGlobal('location', page);
const fetchMock = vi.fn();
vi.stubGlobal('fetch', fetchMock);

const { identity } = await import('./account');
const server = { url: 'https://ytmp.example', issuer: 'ytmp.example' };

describe('logging in on another host', () => {
	beforeEach(() => {
		fetchMock.mockReset();
		page.href = 'http://192.168.1.23:8765/room/ABCD';
	});

	it('brings the phone’s proof along to /connect', async () => {
		fetchMock.mockResolvedValue(new Response(JSON.stringify({ proof: 'a.b.c' }), { status: 200 }));
		await identity.logIn(server);
		expect(fetchMock.mock.calls[0][0]).toBe('/api/host/own-proof?origin=http%3A%2F%2F192.168.1.23%3A8765');
		const url = new URL(page.href);
		expect(url.origin + url.pathname).toBe('https://ytmp.example/connect');
		expect(url.searchParams.get('proof')).toBe('a.b.c');
		expect(url.searchParams.get('return')).toBe('http://192.168.1.23:8765/room/ABCD');
		expect(url.searchParams.get('quiet')).toBeNull();
	});

	it('goes without one when the host has none (not a phone, or nobody’s)', async () => {
		fetchMock.mockResolvedValue(new Response('{}', { status: 404 }));
		await identity.logIn(server, true);
		const url = new URL(page.href);
		expect(url.searchParams.get('proof')).toBeNull();
		expect(url.searchParams.get('quiet')).toBe('1');
		fetchMock.mockRejectedValue(new Error('offline'));
		page.href = 'http://192.168.1.23:8765/';
		await identity.logIn(server);
		expect(new URL(page.href).searchParams.get('return')).toBe('http://192.168.1.23:8765/');
	});

	it('leaves a click that opens a new tab to the link', async () => {
		const event = { button: 0, ctrlKey: true, metaKey: false, shiftKey: false, preventDefault: vi.fn() };
		await identity.logIn(server, false, event as unknown as MouseEvent);
		expect(event.preventDefault).not.toHaveBeenCalled();
		expect(page.href).toBe('http://192.168.1.23:8765/room/ABCD');
	});
});

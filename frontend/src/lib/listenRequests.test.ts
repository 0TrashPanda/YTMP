import { describe, expect, it } from 'vitest';
import { ListenRequests } from './listenRequests';
import type { Participant } from './protocol.gen';

const me = (listenRequest: boolean, listenRequests: number, id = 'p1') => ({ id, listenRequest, listenRequests }) as unknown as Participant;

describe('ListenRequests', () => {
	it('switches when another device of yours asks', () => {
		const r = new ListenRequests();
		expect(r.update(me(false, 0))).toBeNull();
		expect(r.update(me(true, 1))).toBe('on');
		// The same request again (another change to this participant) isn't a new one.
		expect(r.update(me(true, 1))).toBeNull();
		expect(r.update(me(false, 2))).toBe('off');
	});

	it("doesn't act on what was asked before this page was there", () => {
		expect(new ListenRequests().update(me(true, 3))).toBeNull();
		expect(new ListenRequests().update(null)).toBeNull();
		// Another participant (another room): counts from there.
		const r = new ListenRequests();
		r.update(me(false, 0));
		expect(r.update(me(true, 5, 'p2'))).toBeNull();
	});
});

import { describe, expect, it } from 'vitest';
import { ALL_PERMISSIONS, effectivePermissions, rank } from './permissions';
import type { Participant, Role } from './protocol.gen';

const roles = [
	{ id: 'admin', name: 'Admin', color: '#f00', permissions: ['kick', 'add_songs'] },
	{ id: 'listener', name: 'Listener', color: '#0f0', permissions: ['add_songs', 'vote_skip'] }
] as Role[];
const person = (roleId: string, more: Partial<Participant> = {}) => ({ id: 'p', isOwner: false, roleId, allow: [], deny: [], ...more }) as unknown as Participant;

describe('effectivePermissions', () => {
	it('is the role, plus allows, minus denies; the owner may do everything', () => {
		expect([...effectivePermissions(person('listener'), roles)].sort()).toEqual(['add_songs', 'vote_skip']);
		expect(effectivePermissions(person('listener', { allow: ['skip'], deny: ['vote_skip'] } as Partial<Participant>), roles)).toEqual(new Set(['add_songs', 'skip']));
		expect(effectivePermissions(person('admin', { isOwner: true }), roles).size).toBe(ALL_PERMISSIONS.length);
		expect(effectivePermissions(null, roles).size).toBe(0);
	});

	it('ranks the owner above every role', () => {
		expect(rank(person('admin', { isOwner: true }), roles)).toBe(-1);
		expect(rank(person('listener'), roles)).toBe(1);
		expect(rank(person('gone'), roles)).toBe(2);
	});
});

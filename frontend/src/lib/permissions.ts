// Permission names, descriptions and groups for the role editor (like Discord's), and the
// same "what can this person do" rule as the host (core/.../Permissions.kt).

import type { Participant, Permission, Role } from './protocol.gen';

export interface PermissionInfo {
	id: Permission;
	name: string;
	description: string;
}

export interface PermissionGroup {
	name: string;
	permissions: PermissionInfo[];
}

export const PERMISSION_GROUPS: PermissionGroup[] = [
	{
		name: 'Queue',
		permissions: [
			{ id: 'add_songs', name: 'Add Songs', description: 'Allows adding songs to the queue, at the end or to play next.' },
			{ id: 'play_now', name: 'Play Now', description: 'Allows starting a song right away, and jumping to any song in the queue or history.' },
			{ id: 'remove_own', name: 'Remove Own Songs', description: 'Allows removing songs they added themselves.' },
			{ id: 'remove_others', name: "Remove Others' Songs", description: 'Allows removing songs that anyone added.' },
			{ id: 'reorder', name: 'Reorder Queue', description: 'Allows dragging songs to a different place in the queue.' }
		]
	},
	{
		name: 'Playback',
		permissions: [
			{ id: 'play_pause', name: 'Play / Pause', description: 'Allows pausing and resuming the music for everyone.' },
			{ id: 'skip', name: 'Skip', description: 'Allows skipping to the next song, or back to the previous one.' },
			{ id: 'seek', name: 'Seek', description: 'Allows jumping to another point in the current song.' }
		]
	},
	{
		name: 'Listening',
		permissions: [
			{ id: 'listen_locally', name: 'Play On Own Device', description: 'Allows playing the music on their own phone or computer too, in sync.' }
		]
	},
	{
		name: 'Speakers',
		permissions: [
			{ id: 'change_outputs', name: 'Change Speakers', description: 'Allows turning speakers and TVs on and off for the room.' },
			{ id: 'output_volume', name: 'Speaker Volume', description: "Allows changing the volume of the room's speakers and TVs." }
		]
	},
	{
		name: 'People',
		permissions: [
			{ id: 'kick', name: 'Kick Members', description: 'Allows removing people from the room. They can join again right away.' },
			{ id: 'ban', name: 'Ban Members', description: 'Allows removing people for good, and lifting bans.' },
			{ id: 'assign_roles', name: 'Manage Members', description: 'Allows giving people roles below their own, and changing their own permissions.' },
			{ id: 'edit_roles', name: 'Manage Roles', description: 'Allows creating, editing, reordering and deleting roles below their own.' }
		]
	},
	{
		name: 'Room',
		permissions: [{ id: 'change_settings', name: 'Manage Room', description: "Allows changing the room's name and the role new people get." }]
	}
];

/** A per-person override, as in Discord: deny, follow the role, or allow. */
export type Override = 'deny' | 'inherit' | 'allow';

export const ALL_PERMISSIONS: Permission[] = PERMISSION_GROUPS.flatMap((g) => g.permissions.map((p) => p.id));

/** What someone can do: their role, plus their allows, minus their denies. The owner can do everything. */
export function effectivePermissions(person: Participant | null | undefined, roles: Role[]): Set<Permission> {
	if (!person) return new Set();
	if (person.isOwner) return new Set(ALL_PERMISSIONS);
	const role = roles.find((r) => r.id === person.roleId);
	const result = new Set<Permission>([...(role?.permissions ?? []), ...person.allow]);
	for (const p of person.deny) result.delete(p);
	return result;
}

/** 0 is the top role; the owner is above all roles. */
export function rank(person: Participant, roles: Role[]): number {
	if (person.isOwner) return -1;
	const index = roles.findIndex((r) => r.id === person.roleId);
	return index < 0 ? roles.length : index;
}

/** Discord's role colors. */
export const ROLE_COLORS = [
	'#1abc9c', '#2ecc71', '#3498db', '#9b59b6', '#e91e63', '#f1c40f', '#e67e22', '#e74c3c', '#95a5a6', '#607d8b',
	'#11806a', '#1f8b4c', '#206694', '#71368a', '#ad1457', '#c27c0e', '#a84300', '#992d22', '#979c9f', '#546e7a'
];

export const DEFAULT_ROLE_COLOR = '#949ba4';

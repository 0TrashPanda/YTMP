// What a solo room leaves out: the party and sharing bits you don't need when you listen
// alone, as set in Settings → Solo rooms. One of your settings (settings.svelte.ts). Everything shows by default.
import { settings } from './settings.svelte';
import type { RoomConnection } from './room.svelte';

export type SoloPart = 'code' | 'roomName' | 'cast' | 'playingOn' | 'karaoke' | 'partyMenu';

export const SOLO_PARTS: [SoloPart, string, string][] = [
	['code', 'Room code and share button', 'At the top, next to search.'],
	['roomName', 'Room name in the player', 'Above the album art.'],
	['cast', 'Play on buttons', 'In the player and the player bar. Play on stays in the menu behind your picture.'],
	['playingOn', '"Playing on" under the controls', 'Where the music plays (phones).'],
	['karaoke', 'Karaoke', 'The button on the Lyrics tab.'],
	['partyMenu', 'Party items in the menu', 'Party screen, Show on a TV and Clone room, behind your picture. Making the room public stays there.']
];

/** [part] is hidden in solo rooms. */
export function soloHidden(part: SoloPart): boolean {
	const hidden = settings.soloHidden.value;
	return Array.isArray(hidden) && hidden.includes(part);
}

export function setSoloHidden(part: SoloPart, hidden: boolean): void {
	const parts = SOLO_PARTS.map(([p]) => p).filter((p) => (p === part ? hidden : soloHidden(p)));
	settings.soloHidden.set(parts.length ? parts : undefined);
}

/** [part] shows in [room]: always in a party, in a solo room unless hidden. */
export function shows(room: RoomConnection | null | undefined, part: SoloPart): boolean {
	return room?.state?.room.visibility !== 'private' || !soloHidden(part);
}

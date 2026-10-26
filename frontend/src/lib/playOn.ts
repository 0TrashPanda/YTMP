// Holding Play on (Settings → Song menu → Holding): the music goes on the speaker or TV you
// picked last, without the sheet. Remembered in this browser.
import type { RoomConnection } from './room.svelte';
import { read, write } from './storage';

const KEY = 'ytmp.lastOutput';

/** Play on picked [outputId]. */
export const rememberOutput = (outputId: string) => write(KEY, outputId);

/** Plays on the speaker or TV picked last, if this room has it; false when there's none. */
export async function playOnLast(room: RoomConnection, onToast: (text: string) => void): Promise<boolean> {
	const id = read(KEY);
	const output = room.state?.outputs.find((o) => o.id === id);
	if (!output) return false;
	const error = await room.run({ kind: 'SetOutput', outputId: output.id, active: true, screen: false });
	onToast(error ? error.message : `Playing on ${output.name}`);
	return true;
}

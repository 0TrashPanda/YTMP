// What songs do when you tap, swipe or hold them, pick something in the song menu, or press a
// page's Play buttons, and what it says afterwards: the same everywhere.
import { bringsOwnRadio, yourRadio } from './api';
import type { Command, Song } from './protocol.gen';
import type { RoomConnection } from './room.svelte';

export type Toast = (text: string) => void;

/** How a message names one song. */
export const quoted = (song: Song) => `"${song.title}"`;

/**
 * Runs [command] in [room]: says [done] (if given) when it worked, right away when the room shows
 * it right away, or what went wrong (unless the room said so already). Returns whether it worked.
 */
export async function runCommand(room: RoomConnection, command: Command, onToast: Toast, done?: string): Promise<boolean> {
	const sent = room.send(command);
	if (sent.shown && done) onToast(done);
	const error = await sent.done;
	if (error && !room.wasNoticed(error)) onToast(error.message);
	else if (!error && !sent.shown && done) onToast(done);
	return !error;
}

// [name]: how the message names the songs, like `"One More Time"`, a playlist's name or "12 songs by Daft Punk".

export const playNext = (room: RoomConnection, songs: Song[], name: string, onToast: Toast) =>
	runCommand(room, { kind: 'AddSongs', songs, position: 'next' }, onToast, room.playNextText(name));

export const addToQueue = (room: RoomConnection, songs: Song[], name: string, onToast: Toast) =>
	runCommand(room, { kind: 'AddSongs', songs, position: 'end' }, onToast, `Added ${name} to the queue`);

export const playNow = (room: RoomConnection, song: Song, onToast: Toast) => runCommand(room, { kind: 'PlayNow', song }, onToast);

/**
 * Start radio: the queue becomes a radio from [song] (your own account's, when you bring one).
 * [name]: what it's from. The song starts right away; your own radio follows once it's loaded.
 */
export async function startRadio(room: RoomConnection, song: Song, onToast: Toast, name = quoted(song)) {
	const own = bringsOwnRadio();
	const radio = own ? yourRadio(song.id) : null;
	const started = await runCommand(room, { kind: 'StartRadio', song, songs: null, follows: own }, onToast, `Starting a radio from ${name}`);
	if (!started || !radio) return started;
	const songs = await radio;
	// Unless something else was played meanwhile: then that keeps playing.
	if (room.state?.nowPlaying?.item.song.id === song.id) await room.run({ kind: 'StartRadio', song, songs, follows: false });
	return started;
}

/** Autoplay this: the autoplay queue becomes a radio from [song]. */
export async function autoplayThis(room: RoomConnection, song: Song, onToast: Toast) {
	return runCommand(room, { kind: 'AutoplayFromHere', song, songs: await yourRadio(song.id) }, onToast, `Autoplay: songs like ${quoted(song)}`);
}

/** The song menu's width, to open it under the right end of a row or button. */
const MENU_WIDTH = 256;

/** Where the song menu opens: at the pointer for a right-click ([atPointer]), else under the right end of [element] (the ⋮ button, or the row). */
export function menuAt(element: Element, event?: MouseEvent | null, atPointer = false): { x: number; y: number } {
	if (atPointer && event) return { x: event.clientX, y: event.clientY };
	const rect = element.getBoundingClientRect();
	return { x: rect.right - MENU_WIDTH, y: rect.bottom };
}

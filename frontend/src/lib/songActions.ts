// What songs do when you tap, swipe or hold them, pick something in the song menu, or press a
// page's Play buttons, and what it says afterwards: the same everywhere.
import { bringsOwnRadio, yourRadio } from './api';
import { whileBusy } from './busy.svelte';
import { gestures } from './gestures.svelte';
import { settings } from './settings.svelte';
import { radioOptions } from './radio.svelte';
import type { Command, RadioOptions, Song } from './protocol.gen';
import type { RoomConnection } from './room.svelte';

/** A button on a message, like "Change" after saving to a playlist. */
export interface ToastAction {
	label: string;
	run: () => void;
	/** Undo of songs just added: the queue items it takes out again (merged messages take them all). */
	added?: () => string[];
}

/**
 * Messages of one kind that follow each other become one (Settings → Look → Combine messages):
 * [count] things, said by [text] ("Added 3 songs to the queue").
 */
export interface ToastGroup {
	key: string;
	count: number;
	text: (count: number) => string;
}

export type Toast = (text: string, action?: ToastAction, group?: ToastGroup) => void;

/** How a message names one song. */
export const quoted = (song: Song) => `"${song.title}"`;

/**
 * Runs [command] in [room]: says [done] (if given) when it worked, right away when the room shows
 * it right away, or what went wrong (unless the room said so already). Returns whether it worked.
 */
export async function runCommand(room: RoomConnection, command: Command, onToast: Toast, done?: string): Promise<boolean> {
	const added = command.kind === 'AddSongs' && command.position !== 'autoplay' ? addedExtras(room, command.songs, command.position) : null;
	const say = (text: string) => (added ? onToast(text, added.action, added.group) : onToast(text));
	const sent = room.send(command);
	if (sent.shown && done) say(done);
	const error = await sent.done;
	if (error && !room.wasNoticed(error)) onToast(error.message);
	else if (!error && !sent.shown && done) say(done);
	return !error;
}

/**
 * Songs added to the queue: Undo takes them out again, and messages about adding merge
 * ("Added 3 songs to the queue"). The songs are found afterwards: yours, of these songs, and not
 * in the queue before.
 */
function addedExtras(room: RoomConnection, songs: Song[], position: 'next' | 'end'): { action: ToastAction; group: ToastGroup } {
	const before = new Set((room.state?.queue ?? []).map((item) => item.itemId));
	const ids = new Set(songs.map((song) => song.id));
	const added = () =>
		(room.state?.queue ?? []).filter((item) => !before.has(item.itemId) && item.addedBy === room.participantId && ids.has(item.song.id)).map((item) => item.itemId);
	const plural = (n: number) => `${n} ${n === 1 ? 'song' : 'songs'}`;
	return {
		action: { label: 'Undo', added, run: () => removeAdded(room, added()) },
		group: {
			key: `added:${position}`,
			count: songs.length,
			text: (n) => (position === 'end' ? `Added ${plural(n)} to the queue` : room.playNextText(plural(n)))
		}
	};
}

function removeAdded(room: RoomConnection, itemIds: string[]) {
	if (itemIds.length) void room.run({ kind: 'RemoveItems', itemIds });
}

/** The button of two merged messages: both Undos at once, else the newer one's. */
export function mergeActions(room: RoomConnection | null, older: ToastAction | undefined, newer: ToastAction | undefined): ToastAction | undefined {
	if (!room || !older?.added || !newer?.added) return newer;
	const a = older.added;
	const b = newer.added;
	const added = () => [...new Set([...a(), ...b()])];
	return { label: 'Undo', added, run: () => removeAdded(room, added()) };
}

// [name]: how the message names the songs, like `"One More Time"`, a playlist's name or "12 songs by Daft Punk".

export const playNext = (room: RoomConnection, songs: Song[], name: string, onToast: Toast) =>
	runCommand(room, { kind: 'AddSongs', songs, position: 'next' }, onToast, room.playNextText(name));

export const addToQueue = (room: RoomConnection, songs: Song[], name: string, onToast: Toast) =>
	runCommand(room, { kind: 'AddSongs', songs, position: 'end' }, onToast, `Added ${name} to the queue`);

export const playNow = (room: RoomConnection, song: Song, onToast: Toast) => runCommand(room, { kind: 'PlayNow', song }, onToast);

/**
 * A tap on a song that isn't in a song list (Home's quick picks, the top search result, an
 * episode, a dial tile): what Tap does in song lists (Settings → Taps and swipes). [menu] opens
 * its song menu; without one, Song menu adds it to the queue.
 */
export function tapSong(room: RoomConnection, song: Song, onToast: Toast, menu?: () => void) {
	switch (gestures.list.tap) {
		case 'playNow':
			return playNow(room, song, onToast);
		case 'playNext':
			return playNext(room, [song], quoted(song), onToast);
		case 'radio':
			return startRadio(room, song, onToast);
		case 'none':
			return;
		case 'menu':
			if (menu) return menu();
	}
	return addToQueue(room, [song], quoted(song), onToast);
}

/**
 * Start radio: the queue becomes a radio from [song] (your own account's, when you bring one).
 * [name]: what it's from. The song starts right away; your own radio follows once it's loaded.
 * With the setting on, what played before (above the song) is cleared too.
 */
export async function startRadio(room: RoomConnection, song: Song, onToast: Toast, name = quoted(song), options: RadioOptions = radioOptions()) {
	const own = bringsOwnRadio() && ownRadioFits(options);
	const radio = own ? yourRadio(song.id) : null;
	const started = await runCommand(
		room,
		{ kind: 'StartRadio', song, songs: null, follows: own, clearHistory: settings.radioClearsHistory.value, options },
		onToast,
		`Starting a radio from ${name}`
	);
	if (!started || !radio) return started;
	const songs = await radio;
	// Unless something else was played meanwhile: then that keeps playing.
	if (room.state?.nowPlaying?.item.song.id === song.id) await room.run({ kind: 'StartRadio', song, songs, follows: false, clearHistory: false, options });
	return started;
}

/** Autoplay this: the autoplay queue becomes a radio from [song]. */
export async function autoplayThis(room: RoomConnection, song: Song, onToast: Toast, options: RadioOptions = radioOptions()) {
	const songs = ownRadioFits(options) ? await whileBusy(`Finding songs like ${quoted(song)}…`, yourRadio(song.id)) : null;
	return runCommand(room, { kind: 'AutoplayFromHere', song, songs, options }, onToast, `Autoplay: songs like ${quoted(song)}`);
}

/**
 * The radio you bring from your server (your account's YouTube Music with your listening) is
 * that mix: the room makes the others (only YouTube Music, only the graph, only what's here).
 */
const ownRadioFits = (options: RadioOptions) => options.from === 'mix' && options.withAccount !== false;

/** The song menu's width, to open it under the right end of a row or button. */
const MENU_WIDTH = 256;

/** Where the song menu opens: at the pointer for a right-click ([atPointer]), else under the right end of [element] (the ⋮ button, or the row). */
export function menuAt(element: Element, event?: MouseEvent | null, atPointer = false): { x: number; y: number } {
	if (atPointer && event) return { x: event.clientX, y: event.clientY };
	const rect = element.getBoundingClientRect();
	return { x: rect.right - MENU_WIDTH, y: rect.bottom };
}

/** How far (px) the player must be swiped sideways to skip (Settings → Taps and swipes → Swipe to skip). */
export const SKIP_SWIPE_PX = 60;

/** A sideways swipe on the player: left ([forward]) plays the next song, right goes back, like YTM. */
export function skipBySwipe(room: RoomConnection, forward: boolean): void {
	if (forward) void room.next();
	else if (room.canPrevious) void room.run({ kind: 'Previous' });
}

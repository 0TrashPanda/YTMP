// What a command will most likely do, shown before the host answers
// (docs/implementation/protocol.md#commands-show-right-away). The page applies it to its copy of
// the room as soon as the command is sent; the host's own events take over once it answered.
// Like Room.execute in core/.../Room.kt, for the commands whose outcome the page can tell.
import type { Command, Permission, QueueItem, RoomState, Song } from './protocol.gen';
import { positionNow } from './sync';

/** Who sends the command, and when the host gets it. */
export interface Sender {
	id: string;
	name: string;
	can: (permission: Permission) => boolean;
	/** Host time when the host runs the command. */
	at: number;
	/** Makes the IDs of songs this command adds (the host gives them their own). */
	itemId: (index: number) => string;
}

/** Back to the start instead of the previous song, after this long (PREVIOUS_RESTART_MS). */
const PREVIOUS_RESTART_MS = 3000;

/**
 * Applies what [command] will most likely do to [s] (in place). False when the page can't tell
 * (or the host would refuse it): then [s] is unchanged.
 */
export function predict(s: RoomState, command: Command, by: Sender): boolean {
	const p = s.playback;
	const now = s.nowPlaying;
	// Wants to play: plays, waits for the players, or its stream is being looked up.
	const wanted = p.playing || p.starting || !!p.loading;
	switch (command.kind) {
		case 'Play':
			if (!by.can('play_pause')) return false;
			if (!now) {
				if (!s.queue.length) return false;
				playNext(s, true, by);
			} else if (!wanted) {
				if (now.streamUrl) {
					p.playing = true;
					p.hostTimeMs = by.at;
				} else p.loading = true;
			}
			return true;
		case 'Pause':
			if (!by.can('play_pause')) return false;
			if (wanted) {
				p.positionMs = position(s, by.at);
				p.hostTimeMs = by.at;
				p.playing = p.starting = p.loading = false;
			}
			return true;
		case 'Seek':
			if (!by.can('seek') || !now) return false;
			seek(s, command.positionMs, by.at);
			return true;
		case 'SetSpeed':
			if (!by.can('play_pause')) return false;
			// Only an episode changes speed; music plays at 1.
			if (now?.item.song.podcast) {
				p.positionMs = position(s, by.at);
				p.hostTimeMs = by.at;
				p.speed = command.speed;
			}
			return true;
		case 'Skip': {
			const voteOnly = !by.can('skip');
			if (voteOnly && !by.can('vote_skip')) return false;
			if (!now) return false;
			if (voteOnly && s.settings.voteSkip) return vote(s, true, by);
			// Repeat queue and nothing left: the host queues the songs again.
			if (!s.queue.length && s.repeat === 'queue') return false;
			retire(s);
			playNext(s, wanted || (command.play && by.can('play_pause')), by);
			return true;
		}
		case 'VoteSkip':
			if (!by.can('vote_skip') || !now) return false;
			return vote(s, command.on, by);
		case 'Previous': {
			if (!by.can('skip') && !(by.can('vote_skip') && !s.settings.voteSkip)) return false;
			if (!now) return false;
			const last = s.history.at(-1);
			if (position(s, by.at) > PREVIOUS_RESTART_MS || !last) seek(s, 0, by.at);
			else jumpTo(s, last.itemId, by);
			return true;
		}
		case 'JumpTo':
			return by.can('play_now') && jumpTo(s, command.itemId, by);
		case 'PlayNow':
			if (!by.can('play_now')) return false;
			retire(s);
			setCurrent(s, newItem(command.song, 0, by), true, by.at);
			return true;
		case 'StartRadio': {
			if (!by.can('start_radio') || command.song.podcast) return false;
			// On the song that's playing it keeps playing; only the queue changes.
			const keep = now?.item.song.id === command.song.id;
			// The history too, if they asked and may remove those songs (the host decides the same way).
			const clearHistory = command.clearHistory && by.can([...s.history, ...(now ? [now.item] : [])].every((i) => i.addedBy === by.id) ? 'remove_own' : 'remove_others');
			if (!keep) retire(s);
			if (clearHistory) s.history = [];
			s.queue = [];
			s.autoplay = [];
			s.autoplaySeed = command.song;
			if (!keep) setCurrent(s, newItem(command.song, 0, by), true, by.at);
			return true;
		}
		case 'AddSongs': {
			if (!by.can('add_songs') || !command.songs.length) return false;
			// Fair order: where it goes depends on everyone's songs; the host decides.
			if (s.settings.fairOrder && (command.position === 'end' || !by.can('reorder'))) return false;
			const items = command.songs.map((song, i) => newItem(song, i, by));
			s.queue.splice(command.position === 'next' ? 0 : s.queue.length, 0, ...items);
			if (!now) playNext(s, true, by);
			return true;
		}
		case 'RestoreSongs': {
			if (!by.can('add_songs') || !command.songs.length) return false;
			const items = command.songs.map((song, i) => newItem(song, i, by));
			s.queue.splice(Math.min(Math.max(command.index, 0), s.queue.length), 0, ...items);
			if (!now) playNext(s, true, by);
			return true;
		}
		case 'RemoveQueueItem': {
			const item = now?.item.itemId === command.itemId ? now.item : find(s, command.itemId)?.item;
			if (!item || !by.can(item.addedBy === by.id ? 'remove_own' : 'remove_others')) return false;
			// The current song: it stops (not into the history), and the next one plays.
			if (item.itemId === now?.item.itemId) playNext(s, wanted, by);
			else {
				s.queue = s.queue.filter((i) => i.itemId !== item.itemId);
				s.history = s.history.filter((i) => i.itemId !== item.itemId);
				s.autoplay = s.autoplay.filter((i) => i.itemId !== item.itemId);
			}
			return true;
		}
		case 'ClearQueue':
			if (s.queue.length && !by.can(s.queue.every((i) => i.addedBy === by.id) ? 'remove_own' : 'remove_others')) return false;
			s.queue = [];
			return true;
		case 'ClearAutoplay':
			if (!by.can('autoplay_from_here')) return false;
			s.autoplay = [];
			s.autoplaySeed = null;
			return true;
		case 'ShuffleQueue':
			// Shuffled by the host.
			return false;
		case 'MoveQueueItem': {
			if (!by.can('reorder')) return false;
			const from = s.queue.findIndex((i) => i.itemId === command.itemId);
			if (from < 0) return false;
			const [item] = s.queue.splice(from, 1);
			s.queue.splice(Math.min(Math.max(command.toIndex, 0), s.queue.length), 0, item);
			return true;
		}
		case 'MoveItem':
			return moveItem(s, command.itemId, command.list, command.toIndex, by);
		case 'SetRepeat':
			if (!by.can('repeat')) return false;
			s.repeat = command.mode;
			return true;
		case 'SetListening': {
			const me = s.participants.find((x) => x.id === by.id);
			if (!me || (command.on && !by.can('listen_locally'))) return false;
			me.listening = command.on;
			return true;
		}
		case 'SetOutputVolume': {
			const output = s.outputs.find((o) => o.id === command.outputId && o.active);
			if (!output || !by.can('output_volume')) return false;
			output.volume = Math.min(Math.max(command.volume, 0), 1);
			return true;
		}
		default:
			return false;
	}
}

/** Where the current song is at host time [at], within the song. */
function position(s: RoomState, at: number): number {
	const duration = s.nowPlaying?.item.song.durationMs ?? 0;
	const position = Math.round(positionNow(s.playback, at));
	return duration > 0 ? Math.min(position, duration) : position;
}

function seek(s: RoomState, positionMs: number, at: number): void {
	const p = s.playback;
	p.positionMs = Math.min(Math.max(positionMs, 0), Math.max(s.nowPlaying?.item.song.durationMs ?? 0, 0));
	p.hostTimeMs = at;
	// Seeking while it waits for the players: it plays from there now.
	if (p.starting) {
		p.starting = false;
		p.playing = true;
	}
}

function newItem(song: Song, index: number, by: Sender): QueueItem {
	return { itemId: by.itemId(index), song, addedBy: by.id, addedByName: by.name, addedAt: by.at, result: null, origin: 'manual' };
}

/** The current song goes to the history, as skipped. */
function retire(s: RoomState): void {
	const item = s.nowPlaying?.item;
	if (item) s.history.push({ ...item, result: 'skipped' });
	s.nowPlaying = null;
}

/** The next song from the queue (or else autoplay) becomes the current one. */
function playNext(s: RoomState, wanted: boolean, by: Sender): void {
	const next = s.queue.shift() ?? s.autoplay.shift() ?? null;
	setCurrent(s, next, wanted && !!next, by.at);
}

/** [item] is the current song now; its stream is looked up first (loading, when it should play). */
function setCurrent(s: RoomState, item: QueueItem | null, wanted: boolean, at: number): void {
	s.nowPlaying = item ? { item, streamUrl: null } : null;
	// An episode may go on where it was left, and at the episode speed: the host knows; this is a guess.
	s.playback = { playing: false, starting: false, loading: !!item && wanted, positionMs: 0, hostTimeMs: at, speed: item?.song.podcast ? s.playback.speed : 1 };
	if (s.skipVotes) s.skipVotes = { ...s.skipVotes, voters: [] };
}

function vote(s: RoomState, on: boolean, by: Sender): boolean {
	if (!s.settings.voteSkip || !s.skipVotes) return false;
	const voters = s.skipVotes.voters.filter((id) => id !== by.id);
	s.skipVotes = { ...s.skipVotes, voters: on ? [...voters, by.id] : voters };
	return true;
}

function find(s: RoomState, itemId: string): { list: 'history' | 'queue' | 'autoplay'; item: QueueItem } | null {
	for (const list of ['history', 'queue', 'autoplay'] as const) {
		const item = s[list].find((i) => i.itemId === itemId);
		if (item) return { list, item };
	}
	return null;
}

/** Like jumpTo in Room.kt: plays [itemId] now, from autoplay, the queue or the history. */
function jumpTo(s: RoomState, itemId: string, by: Sender): boolean {
	const autoplayIndex = s.autoplay.findIndex((i) => i.itemId === itemId);
	if (autoplayIndex >= 0) {
		// The autoplay songs before it are dropped (they never played).
		retire(s);
		const [item] = s.autoplay.splice(0, autoplayIndex + 1).slice(-1);
		setCurrent(s, item, true, by.at);
		return true;
	}
	const queueIndex = s.queue.findIndex((i) => i.itemId === itemId);
	if (queueIndex >= 0) {
		// Songs before it are skipped.
		retire(s);
		for (const skipped of s.queue.splice(0, queueIndex)) s.history.push({ ...skipped, result: 'skipped' });
		playNext(s, true, by);
		return true;
	}
	const historyIndex = s.history.findIndex((i) => i.itemId === itemId);
	if (historyIndex < 0) return false;
	// Jumping back: everything after it becomes upcoming again.
	const [target, ...after] = s.history.splice(historyIndex);
	const reopened = [...after, ...(s.nowPlaying ? [s.nowPlaying.item] : [])].map((i) => ({ ...i, result: null }));
	s.queue.unshift(...reopened);
	setCurrent(s, { ...target, result: null }, true, by.at);
	return true;
}

/** Like moveItem in Room.kt; the current song moving is left to the host. */
function moveItem(s: RoomState, itemId: string, list: 'history' | 'queue' | 'autoplay', toIndex: number, by: Sender): boolean {
	if (s.nowPlaying?.item.itemId === itemId) return false;
	const found = find(s, itemId);
	if (!found) return false;
	// Taking an autoplay song into the queue (next or last) is like adding it; anything else is reordering.
	const adding = found.list === 'autoplay' && list === 'queue' && (toIndex === 0 || toIndex >= s.queue.length);
	if (!by.can(adding ? 'add_songs' : 'reorder')) return false;
	if (adding && s.settings.fairOrder && !by.can('reorder')) return false;
	s[found.list] = s[found.list].filter((i) => i.itemId !== itemId);
	const item: QueueItem =
		list === 'history'
			? found.item
			: found.list === 'autoplay' && list === 'queue'
				? { ...found.item, addedBy: by.id, addedByName: by.name, addedAt: by.at, origin: 'manual' }
				: { ...found.item, result: null };
	s[list].splice(Math.min(Math.max(toIndex, 0), s[list].length), 0, item);
	if (!s.nowPlaying && s.queue.length && list === 'queue') playNext(s, true, by);
	return true;
}

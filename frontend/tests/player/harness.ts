// The web player (src/lib/player.svelte.ts) in a real browser, with a fake room: built and run
// by scripts/player-test.mjs, which serves real WAV files of known lengths (song IDs say theirs:
// `ytm:len3-…` is 3 s), this computer's host (`/computer/…`) and an own module (`/module/…`),
// both answering late on purpose.
//
// The fake room keeps its state with the app's own applyEvent (roomEvents.ts) and does what
// the host's room does for one player alone: a new song waits for PlayerReady (taking the
// length it reports, and sending the song again with it), then plays; PlayerEnded moves on.
import { createPlayer, type RoomPlayer } from '../../src/lib/player.svelte';
import { applyEvent } from '../../src/lib/roomEvents';
import { computer } from '../../src/lib/computer.svelte';
import { saved } from '../../src/lib/storage';
import type { RoomConnection } from '../../src/lib/room.svelte';
import type { Command, Event, QueueItem, RoomState, Song } from '../../src/lib/protocol.gen';

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** A command the player sent, and when (ms since the test started). */
interface Sent {
	at: number;
	command: Command;
}

class FakeRoom {
	roomCode = 'TEST';
	participantId = 'p1';
	clockOffset = 0;
	listeningAlone = true;
	me = null;
	player: RoomPlayer | null = null;
	sent: Sent[] = [];
	/** When each item started playing (ms since the test started). */
	startedAt = new Map<string, number>();
	/** PlayerEnded moves on to the next song in the queue. */
	movesOn = true;
	/** Repeat one: PlayerEnded starts the same song over (like the room's replayCurrent). */
	repeatOne = false;
	/** The room's speed (an episode played faster or slower). */
	speed = 1;
	state: RoomState = {
		room: { visibility: 'private' },
		participants: [],
		queue: [],
		history: [],
		nowPlaying: null,
		playback: { playing: false, positionMs: 0, hostTimeMs: 0, starting: false, speed: 1, loading: false },
		outputs: [],
		autoplay: []
	} as unknown as RoomState;

	constructor(private readonly t0: number) {}

	now = () => performance.now() - this.t0;
	hostNow = () => Date.now();
	can = () => true;
	interruptsOthers = () => false;
	next = async () => null;

	async run(command: Command): Promise<null> {
		this.sent.push({ at: this.now(), command });
		// Like the host: an answer comes a moment later, not inside the player's own call.
		setTimeout(() => this.handle(command), 0);
		return null;
	}

	apply(...events: Event[]): void {
		for (const e of events) applyEvent(this.state, e);
		this.player?.sync();
	}

	/** [item] becomes the current song, waiting for the player (`starting`). */
	play(item: QueueItem, streamUrl: string): void {
		this.state.queue = this.state.queue.filter((i) => i.itemId !== item.itemId);
		this.streams.set(item.itemId, streamUrl);
		this.apply(
			{ kind: 'NowPlayingChanged', item },
			{ kind: 'StreamReady', itemId: item.itemId, streamUrl },
			{ kind: 'PlaybackChanged', playback: this.playback(false, 0, true) }
		);
	}

	playback(playing: boolean, positionMs: number, starting = false) {
		return { playing, positionMs, hostTimeMs: Date.now(), starting, speed: this.speed, loading: false };
	}

	/** Where the room is now (ms). */
	position(): number {
		const p = this.state.playback;
		return p.playing ? p.positionMs + (Date.now() - p.hostTimeMs) * (p.speed ?? 1) : p.positionMs;
	}

	streams = new Map<string, string>();

	private handle(command: Command): void {
		const now = this.state.nowPlaying;
		if (command.kind === 'PlayerReady' && now?.item.itemId === command.itemId && this.state.playback.starting) {
			// The room takes the length the player reports, and sends the song again with it.
			if (command.durationMs && command.durationMs !== now.item.song.durationMs) {
				this.apply({ kind: 'NowPlayingChanged', item: { ...now.item, song: { ...now.item.song, durationMs: command.durationMs } } });
			}
			this.startedAt.set(command.itemId, this.now());
			this.apply({ kind: 'PlaybackChanged', playback: this.playback(true, this.state.playback.positionMs) });
		}
		if (command.kind === 'PlayerEnded' && now?.item.itemId === command.itemId && this.repeatOne) {
			this.startedAt.set(command.itemId, this.now());
			this.apply({ kind: 'PlaybackChanged', playback: this.playback(true, 0) });
			return;
		}
		if (command.kind === 'PlayerEnded' && now?.item.itemId === command.itemId && this.movesOn) {
			const next = this.state.queue[0];
			if (next) this.play(next, this.streams.get(next.itemId)!);
		}
	}

	of<K extends Command['kind']>(kind: K): (Sent & { command: Extract<Command, { kind: K }> })[] {
		return this.sent.filter((s) => s.command.kind === kind) as (Sent & { command: Extract<Command, { kind: K }> })[];
	}
}

let ids = 0;
/** A song whose audio file is [seconds] long; the room thinks it's [roomMs] (it doesn't know yet). */
function item(seconds: number, roomMs = 0, tag = ''): QueueItem {
	const n = ++ids;
	const song: Song = {
		id: `ytm:len${seconds}-${n}${tag}-${Math.random().toString(36).slice(2, 8)}`,
		title: `Test ${seconds} s (${n})`,
		artists: [],
		album: null,
		durationMs: roomMs,
		thumbnails: [],
		explicit: false,
		podcast: null
	};
	return { itemId: `item-${n}`, song, addedBy: 'p1', addedByName: 'Tester', addedAt: Date.now(), result: null, origin: 'queue' } as unknown as QueueItem;
}

type Way = 'computer' | 'own' | 'host';

/** Where the player gets songs from: this computer (desktop app), your own module, or the host's `/api/audio/`. */
function setUp(way: Way): void {
	delete window.YtmpDesktop;
	computer.status = null;
	saved.ownModule = null;
	saved.songSources = ['direct', 'own', 'host'];
	if (way === 'computer') {
		window.YtmpDesktop = { version: 'test', platform: 'windows', host: `${location.origin}/computer` };
		computer.status = { libraryId: 'lib', cacheMb: 500, cacheSongs: 0, cacheBytes: 0, music: false, folders: [], musicSongs: 0, scanning: false };
	}
	if (way === 'own') {
		saved.ownModule = { url: `${location.origin}/module/`, key: 'test', on: true };
		saved.songSources = ['own', 'direct', 'host'];
	}
}

/** The room's link for [song]: the host's `/api/audio/` (the own module's link and this computer's come from elsewhere). */
const roomLink = (song: Song) => `/api/audio/${encodeURIComponent(song.id)}`;

/** What happened to the audio element: its events, counted, with when. */
function watchAudio(player: RoomPlayer): { audio: HTMLAudioElement; events: { at: number; type: string }[] } {
	const audio = (player as unknown as { audio: HTMLAudioElement }).audio;
	const events: { at: number; type: string }[] = [];
	for (const type of ['loadstart', 'emptied', 'waiting', 'playing', 'pause', 'seeking', 'ended', 'durationchange', 'error']) {
		audio.addEventListener(type, () => events.push({ at: performance.now(), type }));
	}
	return { audio, events };
}

async function waitFor(ok: () => boolean, ms: number): Promise<boolean> {
	for (const end = performance.now() + ms; performance.now() < end; await sleep(50)) if (ok()) return true;
	return ok();
}

interface Result {
	name: string;
	ok: boolean;
	detail: string;
}

async function withPlayer(way: Way, test: (room: FakeRoom, player: RoomPlayer) => Promise<Result[]>): Promise<Result[]> {
	setUp(way);
	const room = new FakeRoom(performance.now());
	const player = createPlayer(room as unknown as RoomConnection);
	room.player = player;
	await player.enable();
	try {
		return await test(room, player);
	} finally {
		player.destroy();
	}
}

const near = (value: number | null | undefined, want: number, within: number) => value != null && Math.abs(value - want) <= within;

/**
 * Two songs in a row (3 s, then 5 s): each PlayerReady says its own song's length, never the
 * one before; PlayerEnded comes once per song, at its audio's real end.
 */
function twoSongs(way: Way): Promise<Result[]> {
	return withPlayer(way, async (room, player) => {
		const a = item(3);
		const b = item(5);
		const { audio } = watchAudio(player);
		const sources: string[] = [];
		audio.addEventListener('loadstart', () => audio.currentSrc && sources.push(new URL(audio.currentSrc).pathname));
		room.state.queue = [b];
		room.streams.set(b.itemId, roomLink(b.song));
		room.play(a, roomLink(a.song));
		const done = await waitFor(() => room.of('PlayerEnded').some((s) => s.command.itemId === b.itemId), 20_000);
		const results: Result[] = [];
		const check = (name: string, ok: boolean, detail: string) => results.push({ name: `${way}: ${name}`, ok, detail });

		const ready = (it: QueueItem) => room.of('PlayerReady').filter((s) => s.command.itemId === it.itemId);
		const lengths = (it: QueueItem) => ready(it).map((s) => s.command.durationMs);
		check('both songs played to their end', done, `sent: ${room.sent.map((s) => s.command.kind).join(', ')}`);
		const prefix = { computer: '/computer/api/audio/', own: '/module/files/api/audio/', host: '/api/audio/' }[way];
		check(`plays from ${way === 'own' ? 'the own module' : way === 'host' ? "the host's /api/audio/" : 'this computer'}`, sources.length === 2 && sources.every((s) => s.startsWith(prefix)), `loaded ${sources.join(', ')}`);
		check('PlayerReady once per song', ready(a).length === 1 && ready(b).length === 1, `${ready(a).length} for the 3 s song, ${ready(b).length} for the 5 s song`);
		check('PlayerReady says 3 s for the 3 s song', ready(a).length > 0 && ready(a).every((s) => near(s.command.durationMs, 3000, 100)), `said ${lengths(a).join(', ') || 'nothing'}`);
		check('PlayerReady says 5 s for the 5 s song (not the 3 s before it)', ready(b).length > 0 && ready(b).every((s) => near(s.command.durationMs, 5000, 100)), `said ${lengths(b).join(', ') || 'nothing'}`);

		for (const [it, ms] of [[a, 3000], [b, 5000]] as const) {
			const ended = room.of('PlayerEnded').filter((s) => s.command.itemId === it.itemId);
			const started = room.startedAt.get(it.itemId);
			const after = ended.map((s) => (started === undefined ? NaN : Math.round(s.at - started)));
			check(`PlayerEnded once for the ${ms / 1000} s song`, ended.length === 1, `${ended.length} times`);
			check(
				`PlayerEnded for the ${ms / 1000} s song at its end (not while it starts)`,
				ended.length > 0 && after.every((t) => near(t, ms, 500)) && ended.every((s) => near(s.command.positionMs, ms, 400)),
				`${after.map((t) => `${t} ms`).join(', ') || 'never'} after it started, at ${ended.map((s) => s.command.positionMs).join(', ')} ms`
			);
		}
		check('no PlayerStalled', room.of('PlayerStalled').length === 0, `${room.of('PlayerStalled').length} times`);
		return results;
	});
}

/** The room's clock goes on past the audio's end (it doesn't know the length): the player waits at the end. */
function pastTheEnd(): Promise<Result[]> {
	return withPlayer('host', async (room, player) => {
		room.movesOn = false;
		const a = item(3);
		const { audio, events } = watchAudio(player);
		room.play(a, roomLink(a.song));
		await waitFor(() => room.of('PlayerEnded').length > 0, 8000);
		// Further on, and a jump further still.
		await sleep(1000);
		room.apply({ kind: 'PlaybackChanged', playback: { playing: true, positionMs: 8000, hostTimeMs: Date.now(), starting: false, speed: 1, loading: false } });
		const from = performance.now();
		await sleep(2500);
		const later = events.filter((e) => e.at >= from).map((e) => e.type);
		const flips = later.filter((t) => t === 'playing' || t === 'waiting' || t === 'seeking');
		const ended = room.of('PlayerEnded');
		return [
			{ name: 'past the end: PlayerEnded once', ok: ended.length === 1, detail: `${ended.length} times` },
			{ name: 'past the end: waits at the end (paused, not loading)', ok: audio.paused && !player.buffering, detail: `paused ${audio.paused}, loading ${player.buffering}, at ${audio.currentTime.toFixed(2)} s` },
			{ name: 'past the end: no loading/playing flip-flop', ok: flips.length === 0, detail: `audio events in 2.5 s: ${later.join(', ') || 'none'}` }
		];
	});
}

/** The same item comes again with a new length (`NowPlayingChanged`): it plays on, not loaded again. */
function sameItemNewLength(): Promise<Result[]> {
	return withPlayer('host', async (room, player) => {
		room.movesOn = false;
		const a = item(5, 5000);
		const { audio, events } = watchAudio(player);
		room.play(a, roomLink(a.song));
		await waitFor(() => !audio.paused && audio.currentTime > 1, 6000);
		const from = performance.now();
		const before = audio.currentTime;
		const now = room.state.nowPlaying!.item;
		// Only the song again (as from a room before 2a6cdf8 sent the stream again too) …
		room.apply({ kind: 'NowPlayingChanged', item: { ...now, song: { ...now.song, durationMs: 5100 } } });
		await sleep(1200);
		const playedOn = !audio.paused && audio.currentTime > before + 0.8;
		// … then the stream again too, as the room sends now.
		room.apply({ kind: 'StreamReady', itemId: now.itemId, streamUrl: roomLink(now.song) });
		await sleep(800);
		const later = events.filter((e) => e.at >= from).map((e) => e.type);
		const reloads = later.filter((t) => t === 'loadstart' || t === 'emptied' || t === 'pause' || t === 'waiting');
		return [
			{ name: 'new length: plays on', ok: playedOn && !audio.paused && !player.buffering, detail: `from ${before.toFixed(2)} s to ${audio.currentTime.toFixed(2)} s, paused ${audio.paused}, loading ${player.buffering}` },
			{ name: 'new length: not loaded again', ok: reloads.length === 0, detail: `audio events: ${later.join(', ') || 'none'}` },
			{ name: 'new length: no PlayerReady or PlayerStalled for it again', ok: room.of('PlayerReady').length === 1 && room.of('PlayerStalled').length === 0, detail: room.sent.map((s) => s.command.kind).join(', ') }
		];
	});
}

/** How far the audio is from the room (ms; positive: ahead). */
const drift = (room: FakeRoom, audio: HTMLAudioElement) => Math.round(audio.currentTime * 1000 - room.position());

/** Repeat one: the room starts the same song over at its end; the player plays it again, and ends it again. */
function repeatOne(): Promise<Result[]> {
	return withPlayer('host', async (room, player) => {
		room.repeatOne = true;
		const a = item(3);
		const { audio, events } = watchAudio(player);
		room.play(a, roomLink(a.song));
		const twice = await waitFor(() => room.of('PlayerEnded').length >= 2, 12_000);
		await sleep(800);
		const ended = room.of('PlayerEnded');
		const loads = events.filter((e) => e.type === 'loadstart').length;
		return [
			{ name: 'repeat one: ends twice, at the end each time', ok: twice && ended.every((s) => near(s.command.positionMs, 3000, 400)), detail: `PlayerEnded at ${ended.map((s) => s.command.positionMs).join(', ') || 'never'}` },
			{ name: 'repeat one: plays again from the start', ok: !audio.paused && audio.currentTime < 1.5, detail: `paused ${audio.paused} at ${audio.currentTime.toFixed(2)} s` },
			{ name: 'repeat one: not loaded again', ok: loads === 1, detail: `${loads} loads` },
			{ name: 'repeat one: PlayerReady once', ok: room.of('PlayerReady').length === 1, detail: room.sent.map((s) => s.command.kind).join(', ') }
		];
	});
}

/** Pause and play: the audio stops where the room paused, and goes on from there. */
function pauseAndPlay(): Promise<Result[]> {
	return withPlayer('host', async (room, player) => {
		room.movesOn = false;
		const a = item(5, 5000);
		const { audio } = watchAudio(player);
		room.play(a, roomLink(a.song));
		await waitFor(() => !audio.paused && audio.currentTime > 1, 6000);
		const at = Math.round(room.position());
		room.apply({ kind: 'PlaybackChanged', playback: room.playback(false, at) });
		await sleep(800);
		const pausedAt = audio.currentTime;
		const paused = audio.paused && Math.abs(pausedAt * 1000 - at) < 250;
		await sleep(700);
		const stayed = Math.abs(audio.currentTime - pausedAt) < 0.01;
		room.apply({ kind: 'PlaybackChanged', playback: room.playback(true, at) });
		await sleep(1000);
		const d = drift(room, audio);
		return [
			{ name: 'pause: stops where the room paused', ok: paused && stayed, detail: `room ${at} ms, audio ${pausedAt.toFixed(3)} s then ${audio.currentTime.toFixed(3)} s` },
			{ name: 'play: goes on from there, in sync', ok: !audio.paused && Math.abs(d) < 250, detail: `drift ${d} ms` },
			{ name: 'pause and play: nothing sent', ok: room.sent.filter((s) => s.command.kind !== 'SetListening' && s.command.kind !== 'PlayerReady').length === 0, detail: room.sent.map((s) => s.command.kind).join(', ') }
		];
	});
}

/** A jump while the song starts (the room plays from there at once): the audio starts there. */
function jumpWhileStarting(): Promise<Result[]> {
	return withPlayer('host', async (room, player) => {
		room.movesOn = false;
		const a = item(5, 5000);
		const { audio } = watchAudio(player);
		room.play(a, roomLink(a.song));
		room.apply({ kind: 'PlaybackChanged', playback: room.playback(true, 2500) });
		await waitFor(() => !audio.paused && audio.currentTime > 2.5, 5000);
		await sleep(600);
		const d = drift(room, audio);
		return [{ name: 'jump while starting: plays from there, in sync', ok: !audio.paused && audio.currentTime > 2.5 && Math.abs(d) < 250, detail: `at ${audio.currentTime.toFixed(2)} s, drift ${d} ms` }];
	});
}

/** An episode at 1.5×: the audio plays at the room's speed, in sync. */
function speed(): Promise<Result[]> {
	return withPlayer('host', async (room, player) => {
		room.movesOn = false;
		room.speed = 1.5;
		const a = item(5, 5000);
		const { audio } = watchAudio(player);
		room.play(a, roomLink(a.song));
		await waitFor(() => !audio.paused && audio.currentTime > 1, 6000);
		await sleep(800);
		const d = drift(room, audio);
		return [{ name: '1.5×: plays at 1.5, in sync', ok: Math.abs(audio.playbackRate - 1.5) < 0.06 && Math.abs(d) < 250, detail: `rate ${audio.playbackRate}, drift ${d} ms` }];
	});
}

/**
 * The room moves on twice while this computer's host is still answering (late) about the
 * first: the late answer is for a song no longer there, and must not load it.
 */
function skipWhileComputerAnswers(): Promise<Result[]> {
	return withPlayer('computer', async (room, player) => {
		room.movesOn = false;
		const b = item(5);
		const c = item(3);
		const { audio } = watchAudio(player);
		const sources: string[] = [];
		audio.addEventListener('loadstart', () => audio.currentSrc && sources.push(new URL(audio.currentSrc).pathname));
		room.play(b, roomLink(b.song));
		await sleep(300);
		room.play(c, roomLink(c.song));
		await waitFor(() => room.of('PlayerReady').some((s) => s.command.itemId === c.itemId), 8000);
		// B's late answer comes in meanwhile.
		await sleep(1500);
		const ready = room.of('PlayerReady');
		const loadedB = sources.filter((s) => s.includes(encodeURIComponent(b.song.id)));
		return [
			{ name: 'skip while this computer answers: only the song now loads', ok: loadedB.length === 0 && sources.length >= 1, detail: `loaded ${sources.join(', ')}` },
			{ name: 'skip while this computer answers: PlayerReady only for it, with its length', ok: ready.length === 1 && ready[0].command.itemId === c.itemId && near(ready[0].command.durationMs, 3000, 100), detail: JSON.stringify(ready.map((s) => s.command)) },
			{ name: 'skip while this computer answers: it plays', ok: !audio.paused && near(audio.duration * 1000, 3000, 100), detail: `paused ${audio.paused}, ${audio.duration} s` }
		];
	});
}

/** Your own module can't get a song (it answers an error): the player plays it the next way, with its length. */
function ownModuleFails(): Promise<Result[]> {
	return withPlayer('own', async (room, player) => {
		room.movesOn = false;
		const a = item(3, 0, '-fail');
		const { audio } = watchAudio(player);
		room.play(a, roomLink(a.song));
		await waitFor(() => !audio.paused && audio.currentTime > 0.5, 8000);
		const ready = room.of('PlayerReady');
		return [
			{ name: "own module fails: plays the room's way", ok: !audio.paused && new URL(audio.currentSrc).pathname.startsWith('/api/audio/'), detail: `paused ${audio.paused}, ${audio.currentSrc}` },
			{ name: 'own module fails: PlayerReady once, with its length', ok: ready.length === 1 && !ready[0].command.failed && near(ready[0].command.durationMs, 3000, 100), detail: JSON.stringify(ready.map((s) => s.command)) }
		];
	});
}

/** The room's link doesn't play here (404): the player goes through the host, with the right length. */
function linkFails(): Promise<Result[]> {
	return withPlayer('host', async (room, player) => {
		room.movesOn = false;
		const a = item(3);
		const { audio, events } = watchAudio(player);
		room.play(a, `/broken/${encodeURIComponent(a.song.id)}`);
		await waitFor(() => !audio.paused && audio.currentTime > 0.5, 8000);
		const ready = room.of('PlayerReady');
		const errors = events.filter((e) => e.type === 'error').length;
		return [
			{ name: 'link fails: plays through the host', ok: !audio.paused && new URL(audio.currentSrc).pathname.startsWith('/api/audio/'), detail: `paused ${audio.paused}, ${audio.currentSrc}` },
			{ name: 'link fails: PlayerReady once, with its length (not failed)', ok: ready.length === 1 && !ready[0].command.failed && near(ready[0].command.durationMs, 3000, 100), detail: JSON.stringify(ready.map((s) => s.command)) },
			{ name: 'link fails: one failed try', ok: errors <= 1, detail: `${errors} errors` }
		];
	});
}

const TESTS: Record<string, () => Promise<Result[]>> = {
	computer: () => twoSongs('computer'),
	own: () => twoSongs('own'),
	host: () => twoSongs('host'),
	'past-the-end': pastTheEnd,
	'new-length': sameItemNewLength,
	'repeat-one': repeatOne,
	'pause-play': pauseAndPlay,
	'jump-while-starting': jumpWhileStarting,
	speed,
	'skip-while-computer-answers': skipWhileComputerAnswers,
	'own-module-fails': ownModuleFails,
	'link-fails': linkFails
};

declare global {
	interface Window {
		playerTests: { names: string[]; run(name: string): Promise<Result[]> };
	}
}

window.playerTests = {
	names: Object.keys(TESTS),
	run: (name) => TESTS[name]().catch((e: unknown) => [{ name, ok: false, detail: `threw: ${e instanceof Error ? e.stack : String(e)}` }])
};

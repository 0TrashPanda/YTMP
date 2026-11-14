import { proxiedAudioUrl, reportProblem } from './api';
import { appUpdate } from './appUpdate.svelte';
import { autoplayAllowed, mayPlayWithoutTap } from './autoplay';
import { DeviceVolume } from './deviceVolume';
import { artUrl, artistNames } from './format';
import { EQ_BANDS, EQ_Q, eqHeadroom, equalizer, type EqSettings } from './equalizer.svelte';
import { nativeBridge, type NativeBridge } from './native';
import { forgetOwnStream, isYoutubeSong, moduleUsable, ownStreamUrl, prefetchOwnStream } from './ownModule.svelte';
import type { NowPlaying, OwnLookups } from './protocol.gen';
import type { RoomConnection } from './room.svelte';
import { settings } from './settings.svelte';
import { saved, type SongSource } from './storage';
import { pickSource, SOURCE_NAMES } from './songSources';
import { computerAudio, tellComputer } from './computer.svelte';
import { onThisPhone } from './offlinePlaylists.svelte';
import { correction, looseCorrection, LOOSE_SETTLE_MS, ownRate, targetPosition } from './sync';

/**
 * Whether this browser looks YouTube songs up itself (see OwnLookups): with its own module, in
 * the order of Settings → Where songs play from. The room's host then needn't (first), or plays
 * on when its own lookup fails (fallback).
 */
export function ownLookups(): OwnLookups {
	if (!moduleUsable()) return 'none';
	const at = saved.songSources.indexOf('own');
	if (at < 0) return 'none';
	return at === 0 ? 'first' : 'fallback';
}

/**
 * The Android app looks songs up on the phone first (bridge 20, PlaybackService); before, it
 * played the room's link first and looked a song up when that didn't play.
 */
const nativeLookups = (bridge: NativeBridge): OwnLookups => {
	// A room on the phone itself: its host looks songs up with the phone's module already.
	if (onThisPhone()) return 'none';
	return bridge.bridgeVersion() >= 20 ? 'first' : 'fallback';
};

/** Where the sound playing here comes from: YouTube, the host streaming it, or this phone itself. */
/**
 * Where the sound playing here comes from (SourceBadge): `youtube` the room's YouTube link;
 * `module` a link from your own module (this browser's or phone's); `computerModule` the desktop
 * app's module looks it up now (its song cache keeps it as it plays); `host` the host has it saved
 * (song cache, download, music files); `hostYoutube` the host gets it from YouTube now; `device`
 * on this phone or computer.
 */
export type AudioSource = 'youtube' | 'module' | 'host' | 'hostYoutube' | 'device' | 'computerModule';

export interface RoomPlayer {
	readonly enabled: boolean;
	/** Where the song playing here comes from (shown in the player); null when nothing plays here. */
	readonly audioSource: AudioSource | null;
	readonly volume: number;
	readonly buffering: boolean;
	readonly error: string | null;
	/** Sync adjustment for this device in ms; positive plays earlier (for speaker/Bluetooth delay). */
	readonly syncOffsetMs: number;
	/** True when playback can start without a click, also right after loading (the Android app, a TV, a browser that allows autoplay). */
	readonly canStartWithoutGesture: boolean;
	/** Sound can start now without a tap here: for another device of yours switching "Play here" on (Command.SetDeviceListening). */
	canStartSound(): boolean;
	enable(): Promise<void>;
	/** [chosen]: you switched it off (not another app taking the audio), so a solo room remembers it. */
	disable(chosen?: boolean): void;
	/** Back after the connection to the host dropped: if it plays here, the host hears so again. */
	reconnected(): void;
	setVolume(value: number): void;
	/** The sleep timer fades the sound out (0–1, on top of the volume); the app's player does that itself. */
	setFade?(fraction: number): void;
	setSyncOffset(ms: number): void;
	/** Call whenever the room state changes. */
	sync(): void;
	destroy(): void;
	/** Sound comes out now (the browser player; the app's player says null). */
	readonly sounding?: boolean | null;
	/** What the player is doing, for a problem report. */
	describe?(): string;
}

/** Buffering this long (ms) is reported to the host. */
const LONG_BUFFERING_MS = 3000;
/** Without data this long (ms) in the middle of a song, and alone, the room waits for this player. */
const ROOM_WAITS_AFTER_MS = 600;

/** How often a Chromecast sums up its short buffering pauses for the host's log. */
const STALL_REPORT_MS = 30_000;
const MEDIA_ERRORS: Record<number, string> = { 1: 'aborted', 2: 'network error', 3: 'decode error', 4: 'not supported (link refused?)' };

/** The equalizer's sound path in a browser: the audio element through a filter per band, then a gain. */
interface EqGraph {
	context: AudioContext;
	filters: BiquadFilterNode[];
	gain: GainNode;
}

/** Where the audio comes from: the room's link, a link from your own module, or the host streaming it (see songSources.ts). */
type Source = SongSource;

/**
 * Pause, from YTMP's own buttons. With *Pause only stops it here* on, in a party room where
 * others listen along, it stops playing on this device instead; not playing here, it pauses
 * the room (asked first, see [RoomConnection.run]).
 */
export function pause(room: RoomConnection, player: RoomPlayer | null | undefined): void {
	if (stopsHere(room, player)) player!.disable();
	else void room.run({ kind: 'Pause' });
}

/**
 * Play, from YTMP's own buttons. In a solo room where nothing plays the room (no device with
 * *Play here* on, no speaker or TV), it also switches *Play here* on, so the press is heard
 * (*Press play to play here*, on by default). Call it from the click: a browser only starts
 * sound inside one.
 */
export function play(room: RoomConnection, player: RoomPlayer | null | undefined): Promise<unknown> {
	if (player && startsHere(room, player)) void player.enable();
	return room.run({ kind: 'Play' });
}

/** Play also switches *Play here* on (see [play]). */
export function startsHere(room: RoomConnection, player: RoomPlayer): boolean {
	return (
		settings.playStartsHere.value &&
		!player.enabled &&
		room.state?.room.visibility === 'private' &&
		room.listeningAlone &&
		room.can('listen_locally')
	);
}

/** Pause only stops the music on this device: the setting, or you may not pause the party (the default Listener). */
function stopsHere(room: RoomConnection, player: RoomPlayer | null | undefined): boolean {
	if (!player?.enabled || room.state?.room.visibility === 'private' || room.listeningAlone) return false;
	return settings.pauseStopsHere.value || !room.can('play_pause');
}

/** The Pause button works: you may pause the room, or it stops the music on this device ([pause]). */
export function canPause(room: RoomConnection, player: RoomPlayer | null | undefined): boolean {
	return room.can('play_pause') || stopsHere(room, player);
}

const onScreen = () => document.visibilityState === 'visible';

/** A media button pressed longer ago than this (ms) isn't asked about any more: the page was frozen meanwhile. */
const ASK_MAX_AGE_MS = 5000;

/** [loose]: on a Chromecast, which can't be synced closely (see [looseCorrection]). */
export function createPlayer(room: RoomConnection, options: { loose?: boolean } = {}): RoomPlayer {
	return nativeBridge ? new NativePlayer(room, nativeBridge) : new WebPlayer(room, options.loose ?? false);
}

/**
 * Plays the room's audio in the browser, in sync with the host.
 *
 * Uses the direct stream URL first. When that doesn't work here (YouTube's links are tied to
 * the host's internet connection), it uses links from your own module if you have one set up,
 * and otherwise streams through the host, for the rest of the room
 * (see docs/implementation/outputs/playback-sync.md).
 */
class WebPlayer implements RoomPlayer {
	get canStartWithoutGesture(): boolean {
		return this.loose || autoplayAllowed();
	}
	/** Sound has played on this page: its audio may play again without a tap. */
	private unlocked = false;
	enabled = $state(false);
	volume = $state(saved.volume);
	syncOffsetMs = $state(saved.syncOffsetMs);
	buffering = $state(false);
	error = $state<string | null>(null);

	private audio = new Audio();
	/** The sleep timer's fade-out (see [setFade]). */
	private fade = 1;
	/** This device's volume in the room, for your other devices (see [followVolume]). */
	private deviceVolume = new DeviceVolume();
	private volumeReport: ReturnType<typeof setTimeout> | undefined;
	/** The room's YouTube links didn't play on this connection: passed over for the next songs. */
	private directFails = false;
	/** Where the loaded song comes from. */
	private source = $state<Source>('direct');
	/** The song loaded plays from this computer itself (the desktop app: its music, or its song cache). */
	private fromComputer = $state(false);
	/** Playing through the host: false when it gets the song from YouTube now (not saved there); null: not known. */
	private hostSaved = $state<boolean | null>(null);
	/** Playing from this computer: false while its module looks the song up (not in its song cache yet). */
	private computerHas = $state(true);
	/** The song that didn't play from this computer: it plays the usual way now. */
	private computerFailed: string | null = null;
	/** The ways already tried for this item (the next one in the order comes after a failure). */
	private tried: { itemId: string | null; sources: Set<Source> } = { itemId: null, sources: new Set() };
	/** Counts loads, so a lookup that finishes after the song changed is ignored. */
	private loads = 0;
	private loadedItemId: string | null = null;
	private timer: ReturnType<typeof setInterval> | undefined;
	/** When the current wait for data started (performance.now()), to report long buffering. */
	private waitingSince: number | null = null;

	/** Loose sync only: no jump before this (performance.now()). */
	private settledAt = 0;

	/**
	 * The equalizer's sound path, made the first time it's switched on and kept from then on (an
	 * audio element can't leave it again); switched off, it's flat.
	 */
	private eq: EqGraph | null = null;
	private stopEqualizer = () => {};

	constructor(
		private readonly room: RoomConnection,
		private readonly loose = false
	) {
		this.audio.preload = 'auto';
		this.audio.volume = this.volume;
		this.audio.addEventListener('waiting', () => {
			this.buffering = true;
			this.waitingSince ??= performance.now();
			this.watchStall();
		});
		this.audio.addEventListener('playing', () => {
			this.unlocked = true;
			// Playing again (repeat one): it can end again.
			this.endedSent = null;
			this.buffering = false;
			clearTimeout(this.stallTimer);
			// It plays after all (e.g. after a tap): the "tap to allow audio" is done.
			if (this.error?.startsWith('Tap')) this.error = null;
			this.reportBuffering();
		});
		// Paused while waiting: that time isn't buffering.
		this.audio.addEventListener('pause', () => (this.waitingSince = null));
		this.audio.addEventListener('canplay', () => this.sync());
		this.audio.addEventListener('ended', () => this.reportEnded());
		this.audio.addEventListener('error', () => this.onError());
		// Not on a Chromecast (the TV's party screen).
		if (!loose) this.stopEqualizer = equalizer.listen((settings) => this.setEqualizer(settings));
	}

	/** Must be called from a click: browsers only allow audio after a user gesture. */
	canStartSound(): boolean {
		return this.loose || mayPlayWithoutTap(this.unlocked);
	}

	get audioSource(): AudioSource | null {
		const now = this.room.state?.nowPlaying;
		if (!this.enabled || !now?.streamUrl) return null;
		if (this.fromComputer) return this.computerHas ? 'device' : 'computerModule';
		if (this.source === 'own') return 'module';
		// Through the host (its `/api/audio/…`): saved there, or it gets the song from YouTube now.
		if (this.source === 'host' || now.streamUrl.startsWith('/')) return this.hostSaved === false ? 'hostYoutube' : 'host';
		return 'youtube';
	}

	async enable(): Promise<void> {
		this.enabled = true;
		// A new version of the page waits while it plays (see appUpdate).
		appUpdate.playingHere = true;
		saved.listening = true;
		saved.setSoloOff(this.room.roomCode, false);
		this.error = null;
		// Unlock audio right away, inside the gesture.
		this.audio.play().catch(() => {});
		this.eq?.context.resume().catch(() => {});
		this.setMediaKeys(true);
		this.sync();
		clearInterval(this.timer);
		this.timer = setInterval(() => this.sync(), 500);
		// New songs wait for this player to load them (see reportReady).
		this.sentLookups = ownLookups();
		await this.room.run({ kind: 'SetListening', on: true, startsTogether: true, ownLookups: this.sentLookups });
	}

	reconnected(): void {
		this.sentLookups = ownLookups();
		if (this.enabled) this.room.run({ kind: 'SetListening', on: true, startsTogether: true, ownLookups: this.sentLookups });
	}

	/** What the room last heard about this player's own lookups: sent again when it changes (the module starts, the order changes). */
	private sentLookups: OwnLookups = 'none';

	private tellLookups(): void {
		const now = ownLookups();
		if (now === this.sentLookups) return;
		this.sentLookups = now;
		this.room.run({ kind: 'SetListening', on: true, startsTogether: true, ownLookups: now });
	}

	disable(chosen = true): void {
		this.enabled = false;
		appUpdate.playingHere = false;
		saved.listening = false;
		if (chosen && this.room.state?.room.visibility === 'private') saved.setSoloOff(this.room.roomCode, true);
		clearInterval(this.timer);
		this.audio.pause();
		this.setMediaKeys(false);
		this.room.run({ kind: 'SetListening', on: false, startsTogether: false, ownLookups: 'none' });
	}

	setVolume(value: number): void {
		this.turnTo(value);
		this.reportVolume();
	}

	private turnTo(value: number): void {
		this.volume = value;
		this.audio.volume = value * this.fade;
		saved.volume = value;
	}

	/** Tells the room this device's volume, or turns to the one another device of yours set. */
	private followVolume(): void {
		const action = this.deviceVolume.update(this.room.me, this.volume);
		if (action && 'apply' in action) this.turnTo(action.apply);
		else if (action) this.reportVolume();
	}

	/** Once the slider rests a moment: it moves in small steps. */
	private reportVolume(): void {
		clearTimeout(this.volumeReport);
		this.volumeReport = setTimeout(() => {
			if (this.room.me?.accountId) this.room.run({ kind: 'SetDeviceVolume', volume: this.volume, participantId: null });
		}, 300);
	}

	setFade(fraction: number): void {
		if (fraction === this.fade) return;
		this.fade = fraction;
		this.audio.volume = this.volume * fraction;
	}

	setSyncOffset(ms: number): void {
		this.syncOffsetMs = ms;
		saved.syncOffsetMs = ms;
		this.sync();
	}

	destroy(): void {
		appUpdate.playingHere = false;
		clearInterval(this.timer);
		this.stopEqualizer();
		this.eq?.context.close().catch(() => {});
		clearTimeout(this.volumeReport);
		this.setMediaKeys(false);
		this.audio.pause();
		this.audio.removeAttribute('src');
		this.audio.load();
	}

	/** Brings the audio element in line with the room. Called on every state change and every second. */
	sync(): void {
		this.followVolume();
		if (!this.enabled) return;
		this.tellLookups();
		if (this.loose) this.reportStalls();
		const state = this.room.state;
		const now = state?.nowPlaying;
		if (!state || !now || !now.streamUrl) {
			if (!this.audio.paused) this.audio.pause();
			if (!now) this.unload();
			return;
		}

		if (navigator.mediaSession) navigator.mediaSession.playbackState = state.playback.playing ? 'playing' : 'paused';
		// Made without a tap, the equalizer's sound path waits for one (browsers allow sound after a tap).
		if (this.eq?.context.state === 'suspended' && state.playback.playing) this.eq.context.resume().catch(() => {});
		if (this.loadedItemId !== now.item.itemId) {
			// A new version of the page: between two songs is the moment, if the new page can play on by itself.
			if (this.loadedItemId !== null && appUpdate.betweenSongs(this.room.roomCode, this.canStartWithoutGesture)) return;
			this.loadedItemId = now.item.itemId;
			this.error = null;
			this.showMetadata();
			this.load(now, now.streamUrl);
			return; // 'canplay' calls sync again.
		}
		if (this.audio.readyState < HTMLMediaElement.HAVE_METADATA) return;
		this.lookUpNext();

		const playback = state.playback;
		const expectedMs = targetPosition(playback, this.room.hostNow(), this.syncOffsetMs);

		if (!playback.playing) {
			if (!this.audio.paused) this.audio.pause();
			if (Math.abs(this.audio.currentTime * 1000 - expectedMs) > 250) this.audio.currentTime = expectedMs / 1000;
			// A new song: ready to start, at its start, when the room starts it for everyone.
			else if (playback.starting && this.audio.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA) this.reportReady(now.item.itemId);
			return;
		}

		// The room is past the end of the audio (it doesn't know the song's length, so it plays on
		// until someone skips): wait at the end. Seeking there ended the audio, playing started it
		// over, and it flipped between loading and playing many times a second.
		const lengthMs = this.audio.duration * 1000;
		if (Number.isFinite(lengthMs) && expectedMs >= lengthMs - 100) {
			if (!this.audio.paused) this.audio.pause();
			this.buffering = false;
			// Paused a moment before the audio's own end: it ended all the same.
			this.reportEnded();
			return;
		}

		const drift = this.audio.currentTime * 1000 - expectedMs;
		// Corrections are on top of the room's speed (an episode played faster or slower).
		const speed = playback.speed ?? 1;
		const fix = this.loose
			? looseCorrection(drift, performance.now() < this.settledAt)
			: correction(drift, ownRate(this.audio.playbackRate, speed), this.room.listeningAlone);
		if (fix.seek) {
			const wasPlaying = !this.audio.paused;
			this.audio.currentTime = expectedMs / 1000;
			if (this.loose) {
				this.settledAt = performance.now() + LOOSE_SETTLE_MS;
				// A jump on a Chromecast is audible (it buffers): the host's log hears about each one
				// (not the one into a song that starts).
				if (wasPlaying) reportProblem(`browser jumped ${Math.round(drift)} ms to stay in sync in ${this.room.state?.nowPlaying?.item.song.id} (${SOURCE_NAMES[this.source]})`, this.room.roomCode);
			}
		}
		const rate = fix.rate * speed;
		if (Math.abs(this.audio.playbackRate - rate) > 0.0001) this.audio.playbackRate = rate;
		if (this.audio.paused) {
			this.audio.play().catch((e: unknown) => {
				// Only a missing tap blocks it (NotAllowedError); anything else says what it was.
				const name = e instanceof DOMException ? e.name : '';
				if (name === 'NotAllowedError') this.error = 'Tap "Play here" again to allow audio.';
				else if (name !== 'AbortError') this.error = `Couldn't play this song (${name || String(e)}).`;
			});
		}
	}

	private setEqualizer(settings: EqSettings): void {
		if (!settings.on && !this.eq) return;
		const graph = (this.eq ??= this.eqGraph());
		if (!graph) return;
		const at = graph.context.currentTime;
		// Glides there in a few ms, so moving a slider doesn't click.
		graph.filters.forEach((filter, i) => filter.gain.setTargetAtTime(settings.on ? settings.gains[i] : 0, at, 0.02));
		graph.gain.gain.setTargetAtTime(settings.on ? 10 ** (eqHeadroom(settings.gains) / 20) : 1, at, 0.02);
	}

	private eqGraph(): EqGraph | null {
		let context: AudioContext;
		try {
			context = new AudioContext();
		} catch {
			return null;
		}
		// Web Audio hears audio from another site only when that site allows it (CORS), and
		// otherwise plays silence. Asking for that, a link that doesn't allow it fails to load,
		// and the player goes on to your own module or the host (see onError), which do.
		this.audio.crossOrigin = 'anonymous';
		const filters = EQ_BANDS.map((frequency) => {
			const filter = context.createBiquadFilter();
			filter.type = 'peaking';
			filter.frequency.value = frequency;
			filter.Q.value = EQ_Q;
			filter.gain.value = 0;
			return filter;
		});
		const gain = context.createGain();
		let node: AudioNode = context.createMediaElementSource(this.audio);
		for (const filter of filters) node = node.connect(filter);
		node.connect(gain).connect(context.destination);
		// A song loaded without asking would play silently from now on: load it again.
		if (this.loadedItemId !== null) {
			this.loadedItemId = null;
			this.sync();
		}
		return { context, filters, gain };
	}

	private triedFor(itemId: string): Set<Source> {
		if (this.tried.itemId !== itemId) this.tried = { itemId, sources: new Set() };
		return this.tried.sources;
	}

	/** The next way to try for [now] in the chosen order, or null when all were tried. */
	private nextSource(now: NowPlaying): Source | null {
		return pickSource(saved.songSources, {
			tried: this.triedFor(now.item.itemId),
			youtube: isYoutubeSong(now.item.song.id),
			directIsYoutube: !(now.streamUrl ?? '').startsWith('/'),
			moduleUsable: moduleUsable(),
			directFails: this.directFails
		});
	}

	private sourceFor(now: NowPlaying): Source {
		return this.nextSource(now) ?? 'host';
	}

	private load(now: NowPlaying, streamUrl: string): void {
		const load = ++this.loads;
		const songId = now.item.song.id;
		this.audio.playbackRate = 1;
		// The desktop app: from this computer when it has the song (its music, or the song cache,
		// which keeps a YouTube song as it plays). Nothing goes through the server then.
		const here = this.computerFailed === now.item.itemId ? null : computerAudio(now.item.song);
		this.fromComputer = !!here;
		this.computerHas = true;
		if (here) {
			// The cache hears about it first, so asking for its audio fills the cache.
			this.audio.removeAttribute('src');
			void tellComputer(now.item.song, 'started').then((has) => {
				if (load !== this.loads) return;
				this.computerHas = has !== false;
				this.audio.src = here;
				this.lookUpNext();
			});
			return;
		}
		this.source = this.sourceFor(now);
		this.hostSaved = null;
		if (this.source !== 'own') {
			const src = this.source === 'host' ? proxiedAudioUrl(songId) : streamUrl;
			this.audio.src = src;
			// Through the host: whether it has the song saved (an older host doesn't say).
			if (src.startsWith('/api/audio/'))
				void fetch(`${src.split('?')[0]}/saved`)
					.then((r) => (r.ok ? r.json() : null))
					.then((a: { saved?: boolean } | null) => {
						if (load === this.loads && typeof a?.saved === 'boolean') this.hostSaved = a.saved;
					})
					.catch(() => {});
			return;
		}
		// Nothing plays while your module looks the song up, not even the last one.
		this.audio.removeAttribute('src');
		this.audio.load();
		this.buffering = true;
		ownStreamUrl(songId).then(
			(url) => {
				if (load !== this.loads) return;
				this.audio.src = url;
				this.lookUpNext();
			},
			(e: Error) => {
				if (load !== this.loads) return;
				this.triedFor(now.item.itemId).add('own');
				const next = this.sourceFor(now);
				reportProblem(`browser: ${e.message} (${songId}), playing it from ${SOURCE_NAMES[next]}`, this.room.roomCode);
				this.load(now, streamUrl);
			}
		);
	}

	/** The song the player last looked up ahead of time (see [lookUpNext]). */
	private lookedUpNext: string | null = null;

	/**
	 * Playing from your own module: the next song's link is looked up while this one plays, also
	 * when it's added (or autoplay finds it) later, so it starts without waiting. Once per song.
	 */
	private lookUpNext(): void {
		const state = this.room.state;
		const song = (state?.queue[0] ?? state?.autoplay[0])?.song;
		if (!song || song.id === this.lookedUpNext) return;
		// From this computer's song cache: asking for its start has the cache fetch it now.
		const here = computerAudio(song);
		if (here) {
			this.lookedUpNext = song.id;
			if (song.id.startsWith('ytm:')) void tellComputer(song, 'known').then(() => fetch(here, { headers: { Range: 'bytes=0-1' } }).catch(() => {}));
			return;
		}
		if (this.source !== 'own' || this.fromComputer) return;
		this.lookedUpNext = song.id;
		prefetchOwnStream(song.id);
	}

	/**
	 * Media keys (keyboard, headphones, the OS media overlay) would otherwise pause only the
	 * local audio, which the sync loop then undoes. Make them room commands instead.
	 */
	private setMediaKeys(on: boolean): void {
		const session = navigator.mediaSession;
		if (!session) return;
		const handlers: [MediaSessionAction, MediaSessionActionHandler][] = [
			['play', () => this.room.run({ kind: 'Play' }, false)],
			// Others listen along (headphones taken out, say): stop playing here, not for everyone.
			['pause', () => (this.room.listeningAlone ? this.room.run({ kind: 'Pause' }, false) : this.disable())],
			// With the page on screen these ask first like YTMP's own buttons; in the background there's no one to ask.
			['nexttrack', () => this.room.next(onScreen())],
			['previoustrack', () => this.room.run({ kind: 'Previous' }, onScreen())],
			['seekto', (details) => details.seekTime != null && this.room.run({ kind: 'Seek', positionMs: Math.round(details.seekTime * 1000) }, onScreen())]
		];
		for (const [action, handler] of handlers) {
			try {
				session.setActionHandler(action, on ? handler : null);
			} catch {
				// Not every browser supports every action.
			}
		}
		if (!on) {
			session.metadata = null;
			session.playbackState = 'none';
		}
	}

	private showMetadata(): void {
		const song = this.room.state?.nowPlaying?.item.song;
		if (!navigator.mediaSession || !song) return;
		const art = artUrl(song, 544);
		navigator.mediaSession.metadata = new MediaMetadata({
			title: song.title,
			artist: artistNames(song),
			album: song.album?.name ?? '',
			artwork: art ? [{ src: art, sizes: '544x544' }] : []
		});
	}

	/** Buffering long enough to hear goes to the host's log, to find out where streams get slow. */
	/** Loose sync only (a Chromecast): every buffering pause, summed up for the host's log now and then. */
	private stalls = { count: 0, ms: 0, since: performance.now() };

	private reportStalls(): void {
		const s = this.stalls;
		if (s.count === 0 || performance.now() - s.since < STALL_REPORT_MS) return;
		reportProblem(
			`browser buffered ${s.count} times (${(s.ms / 1000).toFixed(1)} s) in the last ${Math.round((performance.now() - s.since) / 1000)} s, from ${SOURCE_NAMES[this.source]}`,
			this.room.roomCode
		);
		this.stalls = { count: 0, ms: 0, since: performance.now() };
	}

	private reportBuffering(): void {
		const since = this.waitingSince;
		this.waitingSince = null;
		if (since === null || !this.enabled) return;
		const ms = performance.now() - since;
		if (this.loose) {
			this.stalls.count++;
			this.stalls.ms += ms;
		}
		const song = this.room.state?.nowPlaying?.item.song;
		if (ms < LONG_BUFFERING_MS || !song) return;
		reportProblem(
			`browser buffered ${(ms / 1000).toFixed(1)} s at ${Math.round(this.audio.currentTime)} s in ${song.id} (${SOURCE_NAMES[this.source]})`,
			this.room.roomCode
		);
	}

	get sounding(): boolean {
		return (
			!this.audio.paused &&
			this.audio.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA &&
			this.audio.volume > 0 &&
			!this.audio.muted &&
			(this.eq?.context.state ?? 'running') === 'running'
		);
	}

	describe(): string {
		const a = this.audio;
		const error = a.error ? `, error ${a.error.code} (${MEDIA_ERRORS[a.error.code] ?? '?'}${a.error.message ? `: ${a.error.message}` : ''})` : '';
		return (
			`${a.paused ? 'paused' : 'playing'} at ${a.currentTime.toFixed(1)} s, ready ${a.readyState}/4, network ${a.networkState}, ` +
			`volume ${a.volume}${a.muted ? ' muted' : ''}, from ${SOURCE_NAMES[this.source]}${error}${this.error ? `, says "${this.error}"` : ''}` +
			(this.eq ? `, equalizer ${equalizer.settings.on ? 'on' : 'off'} (${this.eq.context.state})` : '')
		);
	}

	/** The song this player told the room it has loaded. */
	private readySent: string | null = null;

	private stallTimer: ReturnType<typeof setTimeout> | undefined;

	/** The player ran out of data: if it still has none a moment later, and it plays alone, the room waits for it. */
	private watchStall(): void {
		clearTimeout(this.stallTimer);
		if (!this.loose) this.stallTimer = setTimeout(() => this.reportStall(), ROOM_WAITS_AFTER_MS);
	}

	private reportStall(): void {
		const state = this.room.state;
		const now = state?.nowPlaying;
		if (!this.enabled || !state || !now || this.loadedItemId !== now.item.itemId) return;
		if (!state.playback.playing || state.playback.starting || !this.room.listeningAlone) return;
		if (this.audio.paused || this.audio.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA) return;
		// Ready again (`PlayerReady`) when it has data: the room goes on from here.
		this.readySent = null;
		this.room.run({ kind: 'PlayerStalled', itemId: now.item.itemId, positionMs: Math.max(0, Math.round(this.audio.currentTime * 1000 - this.syncOffsetMs)) });
	}

	/** The song this player told the room it played to the end. */
	private endedSent: string | null = null;

	/**
	 * The song played to its end here: the room moves on now (`PlayerEnded`), rather than at a
	 * length it may not know or have rounded up. The room checks it's where it is too.
	 */
	private reportEnded(): void {
		const state = this.room.state;
		const now = state?.nowPlaying;
		if (!this.enabled || !now || this.loadedItemId !== now.item.itemId || this.endedSent === now.item.itemId) return;
		if (!state?.playback.playing) return;
		this.endedSent = now.item.itemId;
		this.room.run({ kind: 'PlayerEnded', itemId: now.item.itemId, positionMs: Math.max(0, Math.round(this.audio.currentTime * 1000 - this.syncOffsetMs)) });
	}

	/** The room waits for its players to load a new song (`playback.starting`): this one has it (or can't play it). */
	private reportReady(itemId: string, failed = false): void {
		if (!this.room.state?.playback.starting || this.readySent === itemId) return;
		this.readySent = itemId;
		// Its length too: when the room didn't look the song up itself, that's the only exact one.
		const length = this.audio.duration;
		const durationMs = !failed && Number.isFinite(length) && length > 0 ? Math.round(length * 1000) : null;
		this.room.run({ kind: 'PlayerReady', itemId, speakers: false, failed, durationMs });
	}

	private onError(): void {
		const now = this.room.state?.nowPlaying;
		if (!this.enabled || !now) return;
		const error = this.audio.error;
		const songId = now.item.song.id;
		if (this.fromComputer) {
			// Not on this computer after all (a file gone), or the cache can't get it: the usual way.
			reportProblem(`browser can't play ${songId} from this computer: ${MEDIA_ERRORS[error?.code ?? 0] ?? 'unknown error'}`, this.room.roomCode);
			this.computerFailed = now.item.itemId;
			this.fromComputer = false;
			this.loadedItemId = null;
			this.sync();
			return;
		}
		const from = this.source;
		this.triedFor(now.item.itemId).add(from);
		// The room's YouTube link is probably tied to the host's internet connection: the next
		// songs skip it too.
		if (from === 'direct' && isYoutubeSong(songId) && !(now.streamUrl ?? '').startsWith('/')) this.directFails = true;
		if (from === 'own') forgetOwnStream(songId);
		const next = this.nextSource(now);
		reportProblem(
			`browser can't play ${songId} from ${SOURCE_NAMES[from]}: ` +
				`${MEDIA_ERRORS[error?.code ?? 0] ?? 'unknown error'}${error?.message ? ` (${error.message})` : ''} at ${Math.round(this.audio.currentTime)} s` +
				(this.eq ? ' with the equalizer (CORS)' : '') +
				(next ? `, switching to ${SOURCE_NAMES[next]}` : ''),
			this.room.roomCode
		);
		if (next) {
			this.loadedItemId = null;
			this.sync();
		} else {
			this.error = `Can't play "${now.item.song.title}" on this device.`;
			// The room doesn't wait for it (and skips the song when no player can play it).
			this.reportReady(now.item.itemId, true);
			// Nothing worked (the host may not stream to this network): the next song tries the
			// room's link again, which often works, instead of failing the same way.
			this.directFails = false;
		}
	}

	private unload(): void {
		if (this.loadedItemId === null) return;
		this.loadedItemId = null;
		this.loads++;
		this.audio.removeAttribute('src');
		this.audio.load();
	}
}

/**
 * In the Android app, audio is played natively (Media3), which also gives lock screen and
 * Bluetooth controls. This class only tells the app what should be playing; the app does
 * the syncing itself and sends media button presses back as room commands.
 */
class NativePlayer implements RoomPlayer {
	readonly canStartWithoutGesture = true;
	canStartSound(): boolean {
		return true;
	}

	get audioSource(): AudioSource | null {
		// Only for the song it's about: the next one's link can take seconds, and until the app
		// loads it, what it said is about the last song.
		const at = this.source.indexOf(':');
		const itemId = this.room.state?.nowPlaying?.item.itemId;
		if (!this.enabled || at < 0 || this.source.slice(at + 1) !== itemId) return null;
		return this.source.slice(0, at) as AudioSource;
	}
	enabled = $state(false);
	volume = $state(saved.volume);
	syncOffsetMs = $state(saved.syncOffsetMs);
	buffering = $state(false);
	error = $state<string | null>(null);
	/** What the app's player says it loaded ("kind:itemId", bridge version 17). */
	private source = $state('');
	/** The song [error] is about: the next one starts without it. */
	private errorItemId: string | null = null;

	constructor(
		private readonly room: RoomConnection,
		private readonly bridge: NativeBridge
	) {
		// What it plays from already (the page reloaded); changes come as onStatus("source:…").
		this.source = bridge.audioSource?.() ?? '';
		window.__ytmpNative = {
			...window.__ytmpNative,
			onCommand: (command) => void this.room.run(command, false),
			// Only asked while the app is on screen, about a press just now; otherwise it doesn't happen.
			onAskCommand: (command, at) => {
				if (!this.room.interruptsOthers(command)) void this.room.run(command, false);
				else if (document.visibilityState === 'visible' && Date.now() - at < ASK_MAX_AGE_MS) void this.room.run(command);
			},
			onStatus: (status) => {
				if (status.startsWith('source:')) {
					this.source = status.slice('source:'.length);
					return;
				}
				this.buffering = status === 'buffering';
				// Another app took the audio, or headphones were unplugged (see saved.headphonesAction):
				// not a choice to stop listening here.
				if (status === 'stopped') this.disable(false);
				// Earbuds back in after their pause stopped it here: the app plays here again already.
				if (status === 'resumed') void this.enable();
				if (status === 'retrying' || status === 'error') this.errorItemId = this.room.state?.nowPlaying?.item.itemId ?? null;
				if (status === 'retrying') this.error = "YouTube won't send this song right now. Trying again…";
				if (status === 'error') this.error = "Can't play this song on this device: YouTube won't send it right now.";
				if (status === 'playing') this.error = null;
			}
		};
	}

	async enable(): Promise<void> {
		this.enabled = true;
		this.error = null;
		saved.listening = true;
		saved.setSoloOff(this.room.roomCode, false);
		this.sync();
		// The app says when it has loaded a new song (bridge version 9), so new songs wait for it.
		await this.room.run({ kind: 'SetListening', on: true, startsTogether: this.bridge.bridgeVersion() >= 9, ownLookups: nativeLookups(this.bridge) });
	}

	reconnected(): void {
		if (this.enabled) this.room.run({ kind: 'SetListening', on: true, startsTogether: this.bridge.bridgeVersion() >= 9, ownLookups: nativeLookups(this.bridge) });
	}

	disable(chosen = true): void {
		this.enabled = false;
		saved.listening = false;
		if (chosen && this.room.state?.room.visibility === 'private') saved.setSoloOff(this.room.roomCode, true);
		this.sync();
		this.room.run({ kind: 'SetListening', on: false, startsTogether: false, ownLookups: 'none' });
	}

	setVolume(value: number): void {
		this.volume = value;
		saved.volume = value;
		this.sync();
	}

	setSyncOffset(ms: number): void {
		this.syncOffsetMs = ms;
		saved.syncOffsetMs = ms;
		this.sync();
	}

	sync(): void {
		// Asked again: a page Android froze for a while missed what the app said meanwhile.
		if (this.bridge.audioSource) this.source = this.bridge.audioSource();
		const state = this.room.state;
		const now = state?.nowPlaying ?? null;
		const song = now?.item.song;
		if (this.error && now?.item.itemId !== this.errorItemId) this.error = null;
		this.bridge.playback(
			JSON.stringify({
				enabled: this.enabled,
				item:
					now && song
						? {
								itemId: now.item.itemId,
								songId: song.id,
								title: song.title,
								artist: artistNames(song),
								artUrl: artUrl(song, 544),
								durationMs: song.durationMs,
								// For the phone's song cache.
								song
							}
						: null,
				streamUrl: now?.streamUrl ?? null,
				// The notification's and lock screen's Next plays the next song while paused (Settings → Playback).
				nextPlays: settings.nextPlays.value,
				proxyUrl: song ? new URL(proxiedAudioUrl(song.id), location.href).href : null,
				playing: state?.playback.playing ?? false,
				starting: state?.playback.starting ?? false,
				positionMs: state?.playback.positionMs ?? 0,
				hostTimeMs: state?.playback.hostTimeMs ?? 0,
				speed: state?.playback.speed ?? 1,
				clockOffset: this.room.clockOffset,
				syncOffsetMs: this.syncOffsetMs,
				alone: this.room.listeningAlone,
				volume: this.volume,
				headphones: saved.headphonesAction,
				skipSilence: settings.skipSilence.value,
				// So the app can follow the room itself while this page is frozen in the background.
				room: this.followableRoom(),
				// The sleep timer pauses a solo room (in a shared room it only stops playing here).
				solo: state?.room.visibility === 'private',
				// The lock screen's Next, Previous and seeking only for whoever may do them right away.
				canSkip: this.room.can('skip'),
				canSeek: this.room.can('seek'),
				// The notification shows the room playing on speakers or TVs, with their volume.
				elsewhere: state?.outputs.some((o) => o.active) ?? false,
				canSetOutputVolume: this.room.can('output_volume'),
				volumeKeys: settings.volumeKeys.value,
				mixWithOtherApps: settings.mixWithOtherApps.value
			})
		);
	}

	private followableRoom() {
		const guestToken = saved.guestToken(this.room.roomCode);
		return this.room.participantId && guestToken
			? { hostUrl: location.origin, code: this.room.roomCode, guestToken }
			: null;
	}

	destroy(): void {
		this.enabled = false;
		this.sync();
		if (window.__ytmpNative) window.__ytmpNative = { ...window.__ytmpNative, onCommand: undefined, onAskCommand: undefined, onStatus: undefined };
	}
}

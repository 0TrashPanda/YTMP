import { proxiedAudioUrl, reportProblem } from './api';
import { appUpdate } from './appUpdate.svelte';
import { DeviceVolume } from './deviceVolume';
import { artUrl, artistNames } from './format';
import { EQ_BANDS, EQ_Q, eqHeadroom, equalizer, type EqSettings } from './equalizer.svelte';
import { nativeBridge, type NativeBridge } from './native';
import { forgetOwnStream, isYoutubeSong, ownStreamUrl, prefetchOwnStream } from './ownModule';
import type { NowPlaying } from './protocol.gen';
import type { RoomConnection } from './room.svelte';
import { saved } from './storage';
import { correction, looseCorrection, LOOSE_SETTLE_MS, ownRate, targetPosition } from './sync';

/** Plays the room's audio on this device. */
export interface RoomPlayer {
	readonly enabled: boolean;
	readonly volume: number;
	readonly buffering: boolean;
	readonly error: string | null;
	/** Sync adjustment for this device in ms; positive plays earlier (for speaker/Bluetooth delay). */
	readonly syncOffsetMs: number;
	/** True when playback can start without a click (the Android app). */
	readonly canStartWithoutGesture: boolean;
	enable(): Promise<void>;
	/** [chosen]: you switched it off (not another app taking the audio), so a solo room remembers it. */
	disable(chosen?: boolean): void;
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
/** How often a Chromecast sums up its short buffering pauses for the host's log. */
const STALL_REPORT_MS = 30_000;
const MEDIA_ERRORS: Record<number, string> = { 1: 'aborted', 2: 'network error', 3: 'decode error', 4: 'not supported (link refused?)' };

/** The equalizer's sound path in a browser: the audio element through a filter per band, then a gain. */
interface EqGraph {
	context: AudioContext;
	filters: BiquadFilterNode[];
	gain: GainNode;
}

/** Where the audio comes from: the room's link, a link from your own module, or the host streaming it. */
type Source = 'direct' | 'own' | 'host';
const SOURCE_NAMES: Record<Source, string> = { direct: 'the direct link', own: 'your own module', host: 'through the host' };

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
 * (see docs/implementation/playback-sync.md).
 */
class WebPlayer implements RoomPlayer {
	readonly canStartWithoutGesture = false;
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
	/** Where songs in this room come from, after what worked so far. */
	private preferred: Source = 'direct';
	/** Where the loaded song comes from. */
	private source: Source = 'direct';
	/** This item goes through the host: its own link didn't work. */
	private hostItemId: string | null = null;
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
		});
		this.audio.addEventListener('playing', () => {
			this.buffering = false;
			// It plays after all (e.g. after a tap): the "tap to allow audio" is done.
			if (this.error?.startsWith('Tap')) this.error = null;
			this.reportBuffering();
		});
		// Paused while waiting: that time isn't buffering.
		this.audio.addEventListener('pause', () => (this.waitingSince = null));
		// A new version of the page waits while sound plays here.
		this.audio.addEventListener('play', () => (appUpdate.soundHere = true));
		for (const stopped of ['pause', 'ended', 'emptied']) this.audio.addEventListener(stopped, () => (appUpdate.soundHere = false));
		this.audio.addEventListener('canplay', () => this.sync());
		this.audio.addEventListener('error', () => this.onError());
		// Not on a Chromecast (the TV's party screen).
		if (!loose) this.stopEqualizer = equalizer.listen((settings) => this.setEqualizer(settings));
	}

	/** Must be called from a click: browsers only allow audio after a user gesture. */
	async enable(): Promise<void> {
		this.enabled = true;
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
		await this.room.run({ kind: 'SetListening', on: true, startsTogether: true });
	}

	disable(chosen = true): void {
		this.enabled = false;
		saved.listening = false;
		if (chosen && this.room.state?.room.visibility === 'private') saved.setSoloOff(this.room.roomCode, true);
		clearInterval(this.timer);
		this.audio.pause();
		this.setMediaKeys(false);
		this.room.run({ kind: 'SetListening', on: false, startsTogether: false });
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
			this.loadedItemId = now.item.itemId;
			this.error = null;
			this.showMetadata();
			this.load(now, now.streamUrl);
			return; // 'canplay' calls sync again.
		}
		if (this.audio.readyState < HTMLMediaElement.HAVE_METADATA) return;

		const playback = state.playback;
		const expectedMs = targetPosition(playback, this.room.hostNow(), this.syncOffsetMs);

		if (!playback.playing) {
			if (!this.audio.paused) this.audio.pause();
			if (Math.abs(this.audio.currentTime * 1000 - expectedMs) > 250) this.audio.currentTime = expectedMs / 1000;
			// A new song: ready to start, at its start, when the room starts it for everyone.
			else if (playback.starting && this.audio.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA) this.reportReady(now.item.itemId);
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

	private sourceFor(now: NowPlaying): Source {
		if (this.hostItemId === now.item.itemId) return 'host';
		if (this.preferred === 'own') return isYoutubeSong(now.item.song.id) ? 'own' : 'direct';
		return this.preferred;
	}

	private load(now: NowPlaying, streamUrl: string): void {
		const load = ++this.loads;
		const songId = now.item.song.id;
		this.source = this.sourceFor(now);
		this.audio.playbackRate = 1;
		if (this.source !== 'own') {
			this.audio.src = this.source === 'host' ? proxiedAudioUrl(songId) : streamUrl;
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
				const state = this.room.state;
				const next = state?.queue[0] ?? state?.autoplay[0];
				if (next) prefetchOwnStream(next.song.id);
			},
			(e: Error) => {
				if (load !== this.loads) return;
				reportProblem(`browser: ${e.message} (${songId}), playing it through the host`, this.room.roomCode);
				this.hostItemId = now.item.itemId;
				this.load(now, streamUrl);
			}
		);
	}

	/**
	 * Media keys (keyboard, headphones, the OS media overlay) would otherwise pause only the
	 * local audio, which the sync loop then undoes. Make them room commands instead.
	 */
	private setMediaKeys(on: boolean): void {
		const session = navigator.mediaSession;
		if (!session) return;
		const handlers: [MediaSessionAction, MediaSessionActionHandler][] = [
			['play', () => this.room.run({ kind: 'Play' })],
			['pause', () => this.room.run({ kind: 'Pause' })],
			['nexttrack', () => this.room.next()],
			['previoustrack', () => this.room.run({ kind: 'Previous' })],
			['seekto', (details) => details.seekTime != null && this.room.run({ kind: 'Seek', positionMs: Math.round(details.seekTime * 1000) })]
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

	/** The room waits for its players to load a new song (`playback.starting`): this one has it (or can't play it). */
	private reportReady(itemId: string): void {
		if (!this.room.state?.playback.starting || this.readySent === itemId) return;
		this.readySent = itemId;
		this.room.run({ kind: 'PlayerReady', itemId, speakers: false });
	}

	private onError(): void {
		const now = this.room.state?.nowPlaying;
		if (!this.enabled || !now) return;
		const error = this.audio.error;
		const songId = now.item.song.id;
		const from = this.source;
		let next: Source = 'host';
		if (from === 'direct' && isYoutubeSong(songId)) {
			// The direct URL is probably tied to the host's IP: use your own module for the rest of
			// the room, or stream through the host.
			next = saved.ownModule ? 'own' : 'host';
			this.preferred = next;
		} else if (from !== 'host') {
			// Your module's link (or a host's file) didn't play: this song through the host.
			if (from === 'own') forgetOwnStream(songId);
			this.hostItemId = now.item.itemId;
		}
		reportProblem(
			`browser can't play ${songId} from ${SOURCE_NAMES[from]}: ` +
				`${MEDIA_ERRORS[error?.code ?? 0] ?? 'unknown error'}${error?.message ? ` (${error.message})` : ''} at ${Math.round(this.audio.currentTime)} s` +
				(this.eq ? ' with the equalizer (CORS)' : '') +
				(from === 'host' ? '' : `, switching to ${SOURCE_NAMES[next]}`),
			this.room.roomCode
		);
		if (from !== 'host') {
			this.loadedItemId = null;
			this.sync();
		} else {
			this.error = `Can't play "${now.item.song.title}" on this device.`;
			// The room doesn't wait for it.
			this.reportReady(now.item.itemId);
			// The host can't help either (it may not stream to this network): the next song tries
			// its direct link again, which often works, instead of failing the same way.
			if (this.preferred === 'host') this.preferred = 'direct';
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
	enabled = $state(false);
	volume = $state(saved.volume);
	syncOffsetMs = $state(saved.syncOffsetMs);
	buffering = $state(false);
	error = $state<string | null>(null);
	/** The song [error] is about: the next one starts without it. */
	private errorItemId: string | null = null;

	constructor(
		private readonly room: RoomConnection,
		private readonly bridge: NativeBridge
	) {
		window.__ytmpNative = {
			...window.__ytmpNative,
			onCommand: (command) => void this.room.run(command),
			onStatus: (status) => {
				this.buffering = status === 'buffering';
				// Another app took the audio, or headphones were unplugged (see saved.headphonesAction):
				// not a choice to stop listening here.
				if (status === 'stopped') this.disable(false);
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
		await this.room.run({ kind: 'SetListening', on: true, startsTogether: this.bridge.bridgeVersion() >= 9 });
	}

	disable(chosen = true): void {
		this.enabled = false;
		saved.listening = false;
		if (chosen && this.room.state?.room.visibility === 'private') saved.setSoloOff(this.room.roomCode, true);
		this.sync();
		this.room.run({ kind: 'SetListening', on: false, startsTogether: false });
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
				nextPlays: saved.nextPlays,
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
				skipSilence: saved.skipSilence,
				// So the app can follow the room itself while this page is frozen in the background.
				room: this.followableRoom(),
				// The sleep timer pauses a solo room (in a shared room it only stops playing here).
				solo: state?.room.visibility === 'private'
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
		if (window.__ytmpNative) window.__ytmpNative = { ...window.__ytmpNative, onCommand: undefined, onStatus: undefined };
	}
}

import { proxiedAudioUrl, reportProblem } from './api';
import { artUrl, artistNames } from './format';
import { nativeBridge, type NativeBridge } from './native';
import { forgetOwnStream, isYoutubeSong, ownStreamUrl, prefetchOwnStream } from './ownModule';
import type { NowPlaying } from './protocol.gen';
import type { RoomConnection } from './room.svelte';
import { saved } from './storage';
import { correction, targetPosition } from './sync';

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
	setSyncOffset(ms: number): void;
	/** Call whenever the room state changes. */
	sync(): void;
	destroy(): void;
}

/** Buffering this long (ms) is reported to the host. */
const LONG_BUFFERING_MS = 3000;
const MEDIA_ERRORS: Record<number, string> = { 1: 'aborted', 2: 'network error', 3: 'decode error', 4: 'not supported (link refused?)' };

/** Where the audio comes from: the room's link, a link from your own module, or the host streaming it. */
type Source = 'direct' | 'own' | 'host';
const SOURCE_NAMES: Record<Source, string> = { direct: 'the direct link', own: 'your own module', host: 'through the host' };

export function createPlayer(room: RoomConnection): RoomPlayer {
	return nativeBridge ? new NativePlayer(room, nativeBridge) : new WebPlayer(room);
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

	constructor(private readonly room: RoomConnection) {
		this.audio.preload = 'auto';
		this.audio.volume = this.volume;
		this.audio.addEventListener('waiting', () => {
			this.buffering = true;
			this.waitingSince ??= performance.now();
		});
		this.audio.addEventListener('playing', () => {
			this.buffering = false;
			this.reportBuffering();
		});
		// Paused while waiting: that time isn't buffering.
		this.audio.addEventListener('pause', () => (this.waitingSince = null));
		this.audio.addEventListener('canplay', () => this.sync());
		this.audio.addEventListener('error', () => this.onError());
	}

	/** Must be called from a click: browsers only allow audio after a user gesture. */
	async enable(): Promise<void> {
		this.enabled = true;
		saved.listening = true;
		saved.setSoloOff(this.room.roomCode, false);
		this.error = null;
		// Unlock audio right away, inside the gesture.
		this.audio.play().catch(() => {});
		this.setMediaKeys(true);
		this.sync();
		this.timer = setInterval(() => this.sync(), 500);
		await this.room.run({ kind: 'SetListening', on: true });
	}

	disable(chosen = true): void {
		this.enabled = false;
		saved.listening = false;
		if (chosen && this.room.state?.room.visibility === 'private') saved.setSoloOff(this.room.roomCode, true);
		clearInterval(this.timer);
		this.audio.pause();
		this.setMediaKeys(false);
		this.room.run({ kind: 'SetListening', on: false });
	}

	setVolume(value: number): void {
		this.volume = value;
		this.audio.volume = value;
		saved.volume = value;
	}

	setSyncOffset(ms: number): void {
		this.syncOffsetMs = ms;
		saved.syncOffsetMs = ms;
		this.sync();
	}

	destroy(): void {
		clearInterval(this.timer);
		this.setMediaKeys(false);
		this.audio.pause();
		this.audio.removeAttribute('src');
		this.audio.load();
	}

	/** Brings the audio element in line with the room. Called on every state change and every second. */
	sync(): void {
		if (!this.enabled) return;
		const state = this.room.state;
		const now = state?.nowPlaying;
		if (!state || !now || !now.streamUrl) {
			if (!this.audio.paused) this.audio.pause();
			if (!now) this.unload();
			return;
		}

		if (navigator.mediaSession) navigator.mediaSession.playbackState = state.playback.playing ? 'playing' : 'paused';
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
			return;
		}

		const fix = correction(this.audio.currentTime * 1000 - expectedMs, this.audio.playbackRate, this.room.listeningAlone);
		if (fix.seek) this.audio.currentTime = expectedMs / 1000;
		if (this.audio.playbackRate !== fix.rate) this.audio.playbackRate = fix.rate;
		if (this.audio.paused) {
			this.audio.play().catch(() => {
				this.error = 'Tap "Play here" again to allow audio.';
			});
		}
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
			['nexttrack', () => this.room.run({ kind: 'Skip' })],
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
	private reportBuffering(): void {
		const since = this.waitingSince;
		this.waitingSince = null;
		if (since === null || !this.enabled) return;
		const ms = performance.now() - since;
		const song = this.room.state?.nowPlaying?.item.song;
		if (ms < LONG_BUFFERING_MS || !song) return;
		reportProblem(
			`browser buffered ${(ms / 1000).toFixed(1)} s at ${Math.round(this.audio.currentTime)} s in ${song.id} (${SOURCE_NAMES[this.source]})`,
			this.room.roomCode
		);
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
				(from === 'host' ? '' : `, switching to ${SOURCE_NAMES[next]}`),
			this.room.roomCode
		);
		if (from !== 'host') {
			this.loadedItemId = null;
			this.sync();
		} else {
			this.error = `Can't play "${now.item.song.title}" on this device.`;
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
				if (status === 'error') this.error = "Can't play this song on this device.";
			}
		};
	}

	async enable(): Promise<void> {
		this.enabled = true;
		this.error = null;
		saved.listening = true;
		saved.setSoloOff(this.room.roomCode, false);
		this.sync();
		await this.room.run({ kind: 'SetListening', on: true });
	}

	disable(chosen = true): void {
		this.enabled = false;
		saved.listening = false;
		if (chosen && this.room.state?.room.visibility === 'private') saved.setSoloOff(this.room.roomCode, true);
		this.sync();
		this.room.run({ kind: 'SetListening', on: false });
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
								durationMs: song.durationMs
							}
						: null,
				streamUrl: now?.streamUrl ?? null,
				proxyUrl: song ? new URL(proxiedAudioUrl(song.id), location.href).href : null,
				playing: state?.playback.playing ?? false,
				positionMs: state?.playback.positionMs ?? 0,
				hostTimeMs: state?.playback.hostTimeMs ?? 0,
				clockOffset: this.room.clockOffset,
				syncOffsetMs: this.syncOffsetMs,
				alone: this.room.listeningAlone,
				volume: this.volume,
				headphones: saved.headphonesAction,
				// So the app can follow the room itself while this page is frozen in the background.
				room: this.followableRoom()
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

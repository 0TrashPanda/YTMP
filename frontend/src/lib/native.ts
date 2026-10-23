import type { Command } from './protocol.gen';

/** The Android app's JS bridge (android/.../MainActivity.kt). Null in a normal browser. */
export interface NativeBridge {
	bridgeVersion(): number;
	serverUrl(): string;
	changeServer(): void;
	/** Opens the configured server (or asks for one). */
	openServer(): void;
	/** Back to the rooms on this phone. */
	openHome(): void;
	/** JSON list of NearbyRoom. */
	nearbyRooms(): string;
	/** JSON of PlaybackTarget (android/.../PlaybackHub.kt). */
	playback(targetJson: string): void;
	/** Opens YouTube Music's sign-in (bridge version 2); the answer comes as onYoutubeSignIn. */
	youtubeSignIn?(): void;
	/**
	 * For a server's page (bridge version 3): asks you, then opens YouTube Music's sign-in and
	 * hands the page the cookies as onYoutubeCookie, to keep in your account there.
	 */
	youtubeCookie?(): void;
	/**
	 * Log in once (bridge version 4): your server's pages hand the app a login for the phone's
	 * own pages (a host token JSON); the app only takes it from the server it's linked to.
	 */
	shareIdentity?(identityJson: string): void;
	/** On the phone's own pages: the login handed over by [shareIdentity], once; null if none. */
	takeIdentity?(): string | null;
	/** On your server's pages (bridge version 4): the YouTube Music account the phone is signed in to (JSON YoutubeAccount), or null. */
	phoneYoutubeAccount?(): string | null;
	/** On your account server's pages (bridge version 4): asks you, then signs the phone in to YouTube Music with these cookies. */
	useYoutubeCookie?(cookie: string): void;
	/** This app's version, e.g. "0.5.2" (bridge version 5). */
	appVersion?(): string;
	/** Looks for a new version of the app; the app shows what it found (bridge version 5). */
	checkForUpdates?(): void;
	/** Opens the share sheet with the app's log files, to report a problem (bridge version 5). */
	shareLogs?(): void;
	/**
	 * Clones a room onto this phone (bridge version 6): a CreateRoomRequest as JSON. Answers a
	 * CreateRoomResponse as JSON (`url`: its link on the local network), or `{ error }`.
	 */
	cloneRoom?(requestJson: string): string;
	/** The Chromecasts on the phone's network and the party screens it shows there (bridge version 7), JSON CastScreenState. */
	castScreens?(): string;
	/**
	 * Shows a room's party screen on a TV on the phone's network (bridge version 7): `url` is
	 * this site's `/tv#cast=CODE.TOKEN`. The answer comes as onCastScreenShown.
	 */
	showCastScreen?(deviceId: string, url: string): void;
	/** Stops it again; the app tells the room (bridge version 7). */
	stopCastScreen?(deviceId: string): void;
	/** The speakers and TVs on the phone's network, what they show and play (bridge version 8), JSON NearbyState. */
	nearby?(): string;
	/** Plays this page's room on a speaker on the phone's network (bridge version 8): `roomJson` is { hostUrl, code, guestToken }. */
	playNearby?(deviceId: string, roomJson: string): void;
	stopNearby?(deviceId: string): void;
	/** 0–1. */
	setNearbyVolume?(deviceId: string, volume: number): void;
	/** The music on this phone shows in the Local tab (bridge version 10). */
	phoneMusic?(): boolean;
	/** Switches that on (Android asks once) or off; the answer comes as onPhoneMusic (bridge version 10). */
	setPhoneMusic?(on: boolean): void;
	/**
	 * Saves a song (JSON Song) with its tags in the phone's Music/YTMP folder (bridge version 11);
	 * the answer comes as onDownloaded.
	 */
	downloadSong?(songJson: string): void;
	/** The folders picked as music, JSON [{ id, name }] (bridge version 13). */
	musicFolders?(): string;
	/** Opens Android's folder picker; the new list comes as onMusicFolders (bridge version 13). */
	addMusicFolder?(): void;
	removeMusicFolder?(id: string): void;
	/** Android's own music scan is used (bridge version 13); setMediaScan answers with onMusicFolders. */
	mediaScan?(): boolean;
	setMediaScan?(on: boolean): void;
	/** The sleep timer, which the app's player runs (bridge version 12): JSON SleepSetting, or null when it's off. */
	sleepTimer?(): string | null;
	/** Sets it (JSON SleepSetting) or turns it off ("null"); the app tells every page as onSleepTimer. */
	setSleepTimer?(json: string): void;
}

/** Callbacks the app calls on the page. */
export interface NativeCallbacks {
	/** A media button (lock screen, notification, headphones) was pressed. */
	onCommand?(command: Command): void;
	/** "buffering" | "ready" | "stopped" | "error" */
	onStatus?(status: string): void;
	/** The sleep timer changed (set, turned off, or it went off): JSON SleepSetting, or null. */
	onSleepTimer?(json: string | null): void;
	/** Back from YouTube Music's sign-in: null when signed in, else why not. */
	onYoutubeSignIn?(error: string | null): void;
	/** Back from youtubeCookie: the cookies, or null (with why, or null when you cancelled). */
	onYoutubeCookie?(cookie: string | null, error: string | null): void;
	/**
	 * The phone's Back button: "handled" when the page went back itself, "exit" to put the
	 * app in the background, anything else for the WebView's own history.
	 */
	onBack?(): 'handled' | 'exit' | 'default';
	/** The list of rooms found on the local network changed. */
	onNearbyRooms?(rooms: NearbyRoom[]): void;
	/** The TVs the phone found, or what it shows on them, changed. */
	onCastScreens?(state: CastScreenState): void;
	/** Back from showCastScreen: null when the TV shows it, else why not. */
	onCastScreenShown?(deviceId: string, error: string | null): void;
	/** The speakers on the phone's network, or what they do, changed. */
	onNearby?(state: NearbyState): void;
	/** A speaker near the phone had a problem (couldn't play a song, another app took it). */
	onNearbyNotice?(text: string): void;
	/** The picked music folders changed. */
	onMusicFolders?(folders: { id: string; name: string }[]): void;
	/** Back from setPhoneMusic: whether it's on now, and why not when Android said no. */
	onPhoneMusic?(on: boolean, error: string | null): void;
	/** Back from downloadSong: null when the song is in the Music folder, else why not. */
	onDownloaded?(songId: string, error: string | null): void;
}

/** Speakers and TVs on the phone's own network (android/.../MainActivity.kt), for a room on another host. */
export interface NearbyState {
	devices: { id: string; name: string; kind: 'chromecast' | 'sonos' | 'mpd' | 'zone' }[];
	shown: { deviceId: string; name: string; url: string }[];
	/** The room playing on speakers here, with them; null when none plays. */
	playing: { roomCode: string; speakers: { id: string; name: string; volume: number | null }[] } | null;
}

/** Chromecasts on the phone's own network, and the party screens it shows there (android/.../MainActivity.kt). */
export interface CastScreenState {
	devices: { id: string; name: string }[];
	shown: { deviceId: string; name: string; url: string }[];
}

/** A room another phone on the same network is hosting (android/.../Nearby.kt). */
export interface NearbyRoom {
	name: string;
	code: string;
	url: string;
}

declare global {
	interface Window {
		YtmpNative?: NativeBridge;
		__ytmpNative?: NativeCallbacks;
	}
}

export const nativeBridge: NativeBridge | null = typeof window === 'undefined' ? null : (window.YtmpNative ?? null);

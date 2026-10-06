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
}

/** Callbacks the app calls on the page. */
export interface NativeCallbacks {
	/** A media button (lock screen, notification, headphones) was pressed. */
	onCommand?(command: Command): void;
	/** "buffering" | "ready" | "stopped" | "error" */
	onStatus?(status: string): void;
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

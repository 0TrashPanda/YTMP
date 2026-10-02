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
}

/** Callbacks the app calls on the page. */
export interface NativeCallbacks {
	/** A media button (lock screen, notification, headphones) was pressed. */
	onCommand(command: Command): void;
	/** "buffering" | "ready" | "stopped" | "error" */
	onStatus(status: string): void;
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

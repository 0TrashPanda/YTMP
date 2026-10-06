// Thumbs up in YouTube Music, shared by the full player, the player bar and the song menu so
// they always agree. Needs your YouTube Music sign-in.
import { SvelteMap } from 'svelte/reactivity';
import { getLiked, setLiked } from './api';
import { youtube } from './youtube.svelte';

class Likes {
	#known = new SvelteMap<string, boolean>();
	#asked = new Set<string>();
	#version = 0;

	/** Whether you like [songId]; null while not known (see [load]) or signed out. */
	get(songId: string | undefined): boolean | null {
		if (!songId || !youtube.account) return null;
		return this.#known.get(songId) ?? null;
	}

	/** Looks [songId] up, once per sign-in. Call it from an effect. */
	load(songId: string): void {
		if (this.#version !== youtube.version) {
			this.#version = youtube.version;
			this.#known.clear();
			this.#asked.clear();
		}
		if (!youtube.account || this.#asked.has(songId)) return;
		this.#asked.add(songId);
		getLiked(songId)
			.then((liked) => this.#known.set(songId, liked))
			.catch(() => this.#asked.delete(songId));
	}

	/** Likes or unlikes [songId] right away (back if it didn't work), and says so with [onToast]. */
	async set(songId: string, liked: boolean, onToast: (text: string) => void): Promise<void> {
		const before = this.#known.get(songId);
		this.#known.set(songId, liked);
		try {
			await setLiked(songId, liked);
			onToast(liked ? 'Added to Liked music' : 'Removed from Liked music');
		} catch (e) {
			if (before === undefined) this.#known.delete(songId);
			else this.#known.set(songId, before);
			onToast(e instanceof Error ? e.message : "Couldn't change the like");
		}
	}
}

export const likes = new Likes();

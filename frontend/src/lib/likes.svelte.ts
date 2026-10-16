// Your thumbs in YouTube Music (up: Liked music; down: fewer songs like it), shared by the full
// player, the player bar and the song menu so they always agree. Needs your YouTube Music sign-in.
import { SvelteMap } from 'svelte/reactivity';
import { getRating, setRating } from './api';
import { isYoutubeSong } from './ownModule';
import type { LikeStatus } from './protocol.gen';
import { youtube } from './youtube.svelte';

export type Rating = 'like' | 'dislike' | 'none';

const toRating = (s: LikeStatus): Rating => (s.liked ? 'like' : s.disliked ? 'dislike' : 'none');

class Likes {
	#known = new SvelteMap<string, Rating>();
	#asked = new Set<string>();
	#version = 0;

	/** Your thumbs on [songId]; null while not known (see [load]) or signed out. */
	rating(songId: string | undefined): Rating | null {
		if (!songId || !youtube.account) return null;
		return this.#known.get(songId) ?? null;
	}

	/** Whether you like [songId]; null while not known. */
	get(songId: string | undefined): boolean | null {
		const rating = this.rating(songId);
		return rating === null ? null : rating === 'like';
	}

	/** Looks [songId] up, once per sign-in. Call it from an effect. */
	load(songId: string): void {
		if (this.#version !== youtube.version) {
			this.#version = youtube.version;
			this.#known.clear();
			this.#asked.clear();
		}
		// Only YouTube Music songs have a like there (not local music).
		if (!youtube.account || !isYoutubeSong(songId) || this.#asked.has(songId)) return;
		this.#asked.add(songId);
		getRating(songId)
			.then((s) => this.#known.set(songId, toRating(s)))
			.catch(() => this.#asked.delete(songId));
	}

	/** Thumbs up, down or neither on [songId], right away (back if it didn't work), and says so with [onToast]. */
	async rate(songId: string, rating: Rating, onToast: (text: string) => void): Promise<boolean> {
		const before = this.#known.get(songId);
		this.#known.set(songId, rating);
		try {
			await setRating(songId, { liked: rating === 'like', disliked: rating === 'dislike' });
			onToast(
				rating === 'like'
					? 'Added to Liked music'
					: rating === 'dislike'
						? "Disliked: YouTube Music suggests fewer songs like it"
						: before === 'like'
							? 'Removed from Liked music'
							: 'Dislike removed'
			);
			return true;
		} catch (e) {
			if (before === undefined) this.#known.delete(songId);
			else this.#known.set(songId, before);
			onToast(e instanceof Error ? e.message : "Couldn't change it");
			return false;
		}
	}

	/** Likes or unlikes [songId]. */
	set(songId: string, liked: boolean, onToast: (text: string) => void): Promise<boolean> {
		return this.rate(songId, liked ? 'like' : 'none', onToast);
	}
}

export const likes = new Likes();

// Which size of each picture (album art, artists, playlists) to load, and the look settings
// (settings.svelte.ts): "Image quality" (High looks sharp on big screens, Low saves data and
// battery), "Video pictures" (wide pictures cropped to the square, or whole; see Art.svelte),
// the dislike button in the player bar and the full player's tabs.
import type { Thumbnail } from './protocol.gen';
import { settings } from './settings.svelte';

export const images = {
	get quality() {
		return settings.imageQuality.value;
	},
	get videoArt() {
		return settings.videoArt.value;
	},
	/** The dislike button in the player bar: as bright as the others, dimmed, or not there. */
	get dislikeButton() {
		return settings.dislikeButton.value;
	},
	/** Swiping up on the full player's tabs: the tab under the finger, or always Up next. */
	get swipeOpens() {
		return settings.swipeOpens.value;
	},
	/** Up next first (like YTM), or Related first. */
	get tabOrder() {
		return settings.tabOrder.value;
	}
};

/** Low quality: no picture is loaded bigger than this (px). */
const LOW_MAX = 300;

/** Google's pictures come in any size: the size is in the URL ("…=w544-h544-l90-rj"). */
const GOOGLE = /\.(googleusercontent|ggpht)\.com\//;
const GOOGLE_SIZE = /=w(\d+)-h(\d+)/;

/**
 * The picture for showing it [size] px wide: the smallest that is at least that big, or the
 * largest. On High, Google's pictures are asked for at [size] when even the largest is smaller,
 * like YTM does; on Low, nothing is bigger than [LOW_MAX].
 */
export function thumbUrl(thumbnails: Thumbnail[], size: number): string | undefined {
	const low = images.quality === 'low';
	const want = low ? Math.min(size, LOW_MAX) : size;
	const sorted = [...thumbnails].sort((a, b) => a.width - b.width);
	const best = sorted.find((t) => t.width >= want) ?? sorted.at(-1);
	if (!best) return undefined;
	const resize = (low && best.width > want) || (!low && best.width < want);
	if (resize && GOOGLE.test(best.url) && GOOGLE_SIZE.test(best.url)) {
		// Same shape (artist banners aren't square).
		const height = best.width ? Math.round((want * best.height) / best.width) : want;
		return best.url.replace(GOOGLE_SIZE, `=w${want}-h${height}`);
	}
	return best.url;
}

/**
 * Small first: big pictures show a small one (~7 KB instead of ~50 KB for a card) while the sharp
 * one loads (Picture.svelte). Measured on a home page: 24 cards are 170 KB at 120 px and 1.1 MB at
 * 400 px. Lists and the player bar load the same small one, so it's often there already.
 */
export const SMALL = 96;

/** The small picture to show first for one shown [size] px wide; none for small pictures and on Low quality. */
export function smallUrl(thumbnails: Thumbnail[], size: number): string | undefined {
	if (images.quality === 'low' || size <= 160) return undefined;
	const small = thumbUrl(thumbnails, SMALL);
	return small === thumbUrl(thumbnails, size) ? undefined : small;
}

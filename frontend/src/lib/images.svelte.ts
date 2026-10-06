// Which size of each picture (album art, artists, playlists) to load, and this device's
// "Image quality" setting: High looks sharp on big screens, Low saves data (and battery).
import type { Thumbnail } from './protocol.gen';
import { saved, type ImageQuality } from './storage';

export const images = $state({ quality: saved.imageQuality });

export function setImageQuality(quality: ImageQuality): void {
	images.quality = quality;
	saved.imageQuality = quality;
}

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

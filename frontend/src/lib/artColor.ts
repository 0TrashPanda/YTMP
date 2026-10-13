// A colour from a song's album art, for the party screen's background (like YTM's player).
// Google's pictures allow being read by other sites, so a small copy is drawn and looked at.

const cache = new Map<string, Promise<[number, number, number] | null>>();

/** The art's most colourful common colour (not near black or white), or null if it can't be read. */
export function artColor(url: string): Promise<[number, number, number] | null> {
	let colour = cache.get(url);
	if (!colour) {
		colour = pick(url);
		cache.set(url, colour);
	}
	return colour;
}

function pick(url: string): Promise<[number, number, number] | null> {
	return new Promise((resolve) => {
		const image = new Image();
		image.crossOrigin = 'anonymous';
		image.referrerPolicy = 'no-referrer';
		image.onerror = () => resolve(null);
		image.onload = () => {
			try {
				const size = 24;
				const canvas = document.createElement('canvas');
				canvas.width = canvas.height = size;
				const context = canvas.getContext('2d', { willReadFrequently: true })!;
				context.drawImage(image, 0, 0, size, size);
				const pixels = context.getImageData(0, 0, size, size).data;
				// Each pixel counts by how colourful it is; near black, white and grey count little.
				let r = 0;
				let g = 0;
				let b = 0;
				let total = 0;
				for (let i = 0; i < pixels.length; i += 4) {
					const [pr, pg, pb] = [pixels[i], pixels[i + 1], pixels[i + 2]];
					const max = Math.max(pr, pg, pb);
					const min = Math.min(pr, pg, pb);
					const weight = 0.05 + ((max - min) / 255) ** 2 * (max > 30 && min < 235 ? 1 : 0.1);
					r += pr * weight;
					g += pg * weight;
					b += pb * weight;
					total += weight;
				}
				resolve([Math.round(r / total), Math.round(g / total), Math.round(b / total)]);
			} catch {
				resolve(null); // The picture can't be read here.
			}
		};
		image.src = url;
	});
}

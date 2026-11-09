import { describe, expect, it, vi } from 'vitest';

let quality = 'high';
vi.mock('./settings.svelte', () => ({
	settings: {
		get imageQuality() {
			return { value: quality };
		}
	}
}));

const { smallUrl, thumbUrl } = await import('./images.svelte');

const base = 'https://yt3.googleusercontent.com/abc';
// What YouTube Music gives an album: a small and a big one.
const album = [
	{ url: `${base}=w120-h120-l90-rj`, width: 120, height: 120 },
	{ url: `${base}=w544-h544-l90-rj`, width: 544, height: 544 }
];

describe('small first', () => {
	it('a card shows the small picture the lists load, then the sharp one', () => {
		quality = 'high';
		expect(thumbUrl(album, 400)).toBe(`${base}=w544-h544-l90-rj`);
		expect(smallUrl(album, 400)).toBe(`${base}=w120-h120-l90-rj`);
		expect(thumbUrl(album, 96)).toBe(smallUrl(album, 400));
	});

	it('small pictures load as they are', () => {
		quality = 'high';
		expect(smallUrl(album, 160)).toBeUndefined();
		expect(smallUrl(album, 48)).toBeUndefined();
	});

	it('not when the only picture is the small one', () => {
		quality = 'high';
		const one = [{ url: 'https://i.ytimg.com/vi/x/hqdefault.jpg', width: 480, height: 360 }];
		expect(smallUrl(one, 544)).toBeUndefined();
	});

	it('not on Low quality: that saves data, and its pictures are small already', () => {
		quality = 'low';
		expect(smallUrl(album, 544)).toBeUndefined();
	});
});

import { describe, expect, it } from 'vitest';
import { parseLink } from './links';

describe('pasted links', () => {
	it('YouTube Music and YouTube songs', () => {
		const song = { kind: 'song', id: 'ytm:dQw4w9WgXcQ', playlist: null };
		for (const link of [
			'https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=abc',
			'https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s',
			'https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ',
			'https://youtu.be/dQw4w9WgXcQ?si=xyz',
			'youtu.be/dQw4w9WgXcQ',
			'https://youtube.com/shorts/dQw4w9WgXcQ',
			'  https://music.youtube.com/watch?v=dQw4w9WgXcQ  '
		])
			expect(parseLink(link), link).toEqual(song);
	});

	it('a song in a playlist, not in a mix', () => {
		expect(parseLink('https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabc')).toEqual({ kind: 'song', id: 'ytm:dQw4w9WgXcQ', playlist: 'PLabc' });
		expect(parseLink('https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVMdQw4w9WgXcQ')).toEqual({ kind: 'song', id: 'ytm:dQw4w9WgXcQ', playlist: null });
	});

	it('albums, playlists, artists and podcasts', () => {
		expect(parseLink('https://music.youtube.com/playlist?list=OLAK5uy_abc')).toEqual({ kind: 'album', id: 'OLAK5uy_abc' });
		expect(parseLink('https://music.youtube.com/browse/MPREb_abc')).toEqual({ kind: 'album', id: 'MPREb_abc' });
		expect(parseLink('https://music.youtube.com/playlist?list=PLabc&si=x')).toEqual({ kind: 'playlist', id: 'PLabc' });
		expect(parseLink('https://www.youtube.com/playlist?list=PLabc')).toEqual({ kind: 'playlist', id: 'PLabc' });
		expect(parseLink('https://music.youtube.com/browse/VLPLabc')).toEqual({ kind: 'playlist', id: 'PLabc' });
		expect(parseLink('https://music.youtube.com/channel/UCabc')).toEqual({ kind: 'artist', id: 'UCabc' });
		expect(parseLink('https://music.youtube.com/browse/MPSPPLabc')).toEqual({ kind: 'podcast', id: 'MPSPPLabc' });
	});

	it('YTMP rooms, on any host', () => {
		expect(parseLink('http://192.168.0.104:8765/room/mqvu')).toEqual({ kind: 'room', url: 'http://192.168.0.104:8765/room/mqvu', code: 'MQVU' });
		expect(parseLink('192.168.0.104:8765/room/ABCD')).toMatchObject({ url: 'http://192.168.0.104:8765/room/ABCD' });
		expect(parseLink('ytmp.example.com/room/PARTY')).toMatchObject({ url: 'https://ytmp.example.com/room/PARTY', code: 'PARTY' });
	});

	it('anything else is a search', () => {
		for (const text of ['daft punk', 'one/two', 'https://example.com/watch?v=dQw4w9WgXcQ', 'https://www.youtube.com/watch?v=short', 'https://music.youtube.com/', 'ftp://youtube.com/watch?v=dQw4w9WgXcQ', 'AC/DC'])
			expect(parseLink(text), text).toBeNull();
	});
});

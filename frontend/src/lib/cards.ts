// Albums, artists, podcasts and playlists (search results, the home page) as cards that open their page.
import { prefetch } from './api';
import { thumbUrl } from './images.svelte';
import type { Card } from './components/CardGrid.svelte';
import type { AlbumRef, ArtistRef, PodcastRef, SearchItem } from './protocol.gen';

export interface PageHandlers {
	onArtist: (artist: ArtistRef) => void;
	onAlbum: (album: AlbumRef) => void;
	onPlaylist: (playlist: { id: string; title: string }) => void;
	onPodcast: (podcast: PodcastRef) => void;
}

/** Cards are up to ~226 px wide; this is sharp on high-density screens. */
const CARD = 400;

/** A card for anything but a song (songs are rows); null for a song. */
export function itemCard(item: SearchItem, open: PageHandlers): Card | null {
	switch (item.kind) {
		case 'album':
			return {
				key: `album:${item.album.id}`,
				title: item.album.title,
				subtitle: [item.album.kind, item.artists.map((a) => a.name).join(', '), item.album.year].filter(Boolean).join(' • '),
				art: thumbUrl(item.album.thumbnails, CARD),
				open: () => open.onAlbum({ id: item.album.id, name: item.album.title }),
				prefetch: () => prefetch('album', item.album.id)
			};
		case 'artist':
			return {
				key: `artist:${item.id}`,
				title: item.name,
				subtitle: 'Artist',
				art: thumbUrl(item.thumbnails, CARD),
				round: true,
				open: () => open.onArtist({ id: item.id, name: item.name }),
				prefetch: () => prefetch('artist', item.id)
			};
		case 'podcast':
			return {
				key: `podcast:${item.id}`,
				title: item.title,
				subtitle: item.author ?? 'Podcast',
				art: thumbUrl(item.thumbnails, CARD),
				open: () => open.onPodcast({ id: item.id, name: item.title }),
				prefetch: () => prefetch('podcast', item.id)
			};
		case 'playlist':
			return {
				key: `playlist:${item.id}`,
				title: item.title,
				subtitle: [item.author, item.itemCount != null ? `${item.itemCount} songs` : null].filter(Boolean).join(' • '),
				art: thumbUrl(item.thumbnails, CARD),
				open: () => open.onPlaylist({ id: item.id, title: item.title }),
				prefetch: () => prefetch('playlist', item.id)
			};
		case 'song':
			return null;
	}
}

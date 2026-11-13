// Tiles for a speed dial (SpeedDial.svelte): songs (tap = what Tap does in song lists, right-click = song menu),
// and albums, playlists, artists and podcasts (cards) that open their page.
import type { Card } from './components/CardGrid.svelte';
import type { DialTile } from './components/SpeedDial.svelte';
import type { MenuTarget } from './components/SongMenu.svelte';
import { smallUrl, thumbUrl } from './images.svelte';
import type { Song } from './protocol.gen';
import type { RoomConnection } from './room.svelte';
import { menuAt, tapSong } from './songActions';
import { openCardMenu } from './cardMenu.svelte';

/** A third of the widest page (30rem), sharp on high-density screens. */
const TILE = 320;

export function songTiles(songs: Song[], room: RoomConnection, onToast: (text: string) => void, onMenu: (target: MenuTarget) => void): DialTile[] {
	return songs.map((song, i) => ({
		key: `${song.id}:${i}`,
		title: song.title,
		art: thumbUrl(song.thumbnails, TILE),
		small: smallUrl(song.thumbnails, TILE),
		icon: 'note',
		open: () => tapSong(room, song, onToast),
		menu: (event: MouseEvent) => {
			event.preventDefault();
			onMenu({ song, place: 'search', ...menuAt(event.currentTarget as HTMLElement, event, true) });
		}
	}));
}

export const cardTiles = (cards: Card[]): DialTile[] =>
	cards.map((card) => ({
		key: card.key,
		title: card.title,
		art: card.art,
		small: card.small,
		icon: card.round ? 'person' : 'note',
		open: card.open,
		prefetch: card.prefetch,
		// Right-click: the album's, playlist's or artist's own menu, as on a card (not the browser's image menu).
		menu: card.menu
			? (event: MouseEvent) => {
					event.preventDefault();
					openCardMenu({ ...card.menu!, subtitle: card.menu!.subtitle || card.subtitle }, menuAt(event.currentTarget as HTMLElement, event, true));
				}
			: undefined
	}));

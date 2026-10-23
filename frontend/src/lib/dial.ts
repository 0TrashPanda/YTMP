// Tiles for a speed dial (SpeedDial.svelte): songs (tap = play next, right-click = song menu),
// and albums, playlists, artists and podcasts (cards) that open their page.
import type { Card } from './components/CardGrid.svelte';
import type { DialTile } from './components/SpeedDial.svelte';
import type { MenuTarget } from './components/SongMenu.svelte';
import { thumbUrl } from './images.svelte';
import type { Song } from './protocol.gen';
import type { RoomConnection } from './room.svelte';
import { menuAt, playNext, quoted } from './songActions';

/** A third of the widest page (30rem), sharp on high-density screens. */
const TILE = 320;

export function songTiles(songs: Song[], room: RoomConnection, onToast: (text: string) => void, onMenu: (target: MenuTarget) => void): DialTile[] {
	return songs.map((song, i) => ({
		key: `${song.id}:${i}`,
		title: song.title,
		art: thumbUrl(song.thumbnails, TILE),
		icon: 'note',
		open: () => playNext(room, [song], quoted(song), onToast),
		menu: (event: MouseEvent) => {
			event.preventDefault();
			onMenu({ song, place: 'search', ...menuAt(event.currentTarget as HTMLElement, event, true) });
		}
	}));
}

export const cardTiles = (cards: Card[]): DialTile[] =>
	cards.map((card) => ({ key: card.key, title: card.title, art: card.art, icon: card.round ? 'person' : 'note', open: card.open, prefetch: card.prefetch }));

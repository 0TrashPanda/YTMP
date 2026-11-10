// The menu of an album, playlist, artist or podcast card (hold it, right-click it, or its ⋮ with a
// mouse), like YouTube Music's: the room page shows it (CardMenu.svelte) wherever the card is.

export interface CardTarget {
	kind: 'album' | 'playlist' | 'artist' | 'podcast';
	id: string;
	title: string;
	subtitle: string;
	art?: string;
}

/** The open menu: what it's for, and where (a mouse: at the pointer or under the ⋮). */
export const cardMenu = $state<{ open: { target: CardTarget; x: number; y: number } | null }>({ open: null });

export function openCardMenu(target: CardTarget, at: { x: number; y: number }): void {
	cardMenu.open = { target, ...at };
}

/** A link to it on YouTube Music, to share. */
export function cardLink(target: CardTarget): string {
	const id = encodeURIComponent(target.id);
	switch (target.kind) {
		case 'album':
			return `https://music.youtube.com/browse/${id}`;
		case 'artist':
			return `https://music.youtube.com/channel/${id}`;
		case 'podcast':
			return target.id.startsWith('MPSP') ? `https://music.youtube.com/browse/${id}` : `https://music.youtube.com/playlist?list=${id}`;
		case 'playlist':
			return `https://music.youtube.com/playlist?list=${encodeURIComponent(target.id.replace(/^VL/, ''))}`;
	}
}

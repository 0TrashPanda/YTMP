// Where a browser plays a song from, in the order chosen in the settings (saved.songSources):
// the room's link (usually YouTube's, looked up by the host), your own module, or through the
// host. Each is tried once per song; one that can't be used for it is passed over.

import type { SongSource } from './storage';

/** For the problem reports. */
export const SOURCE_NAMES: Record<SongSource, string> = { direct: 'the direct link', own: 'your own module', host: 'through the host' };

/** What the settings call them. */
export const SOURCE_LABELS: Record<SongSource, { name: string; about: string }> = {
	direct: { name: 'The room’s link', about: 'Straight from YouTube, with the link the room’s host got' },
	own: { name: 'Your own module', about: 'Straight from YouTube, with a link from the module on this computer' },
	host: { name: 'Through the server', about: 'The room’s host sends the song itself' }
};

export interface SourceChoice {
	/** Already tried for this song. */
	tried: ReadonlySet<SongSource>;
	/** A YouTube song (the module only knows those). */
	youtube: boolean;
	/** The room's link is YouTube's (not a file on the host). */
	directIsYoutube: boolean;
	/** Your module is on and not known to be down. */
	moduleUsable: boolean;
	/** The room's YouTube links didn't play on this connection (they're tied to the host's). */
	directFails: boolean;
}

/** The first way in [order] worth trying for this song, or null when none is left. */
export function pickSource(order: readonly SongSource[], c: SourceChoice): SongSource | null {
	for (const source of order) {
		if (c.tried.has(source)) continue;
		if (source === 'own' && !(c.youtube && c.moduleUsable)) continue;
		if (source === 'direct' && c.directFails && c.directIsYoutube) continue;
		return source;
	}
	// Through the host works for every song: the last try, also when it's not in the order's reach.
	return c.tried.has('host') ? null : 'host';
}

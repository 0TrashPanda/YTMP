<script lang="ts" module>
	import type { AlbumRef, ArtistRef, PodcastRef, QueueItem, Song } from '../protocol.gen';

	/** Where a song in a menu comes from. */
	export type SongPlace = 'search' | 'history' | 'current' | 'queue' | 'autoplay';

	export interface MenuTarget {
		song: Song;
		item?: QueueItem;
		place: SongPlace;
		/** The song is in one of your own playlists (or Liked music), shown there: it can be removed. */
		playlist?: { id: string; itemId: string | null; likedMusic: boolean; onRemoved: () => void };
		/** A song in your listening history ([id]: its entry there), shown there: it can be removed. */
		listened?: { id: string; onRemoved: () => void };
		/** A podcast episode with a played mark (your account): it can be marked played or unplayed. */
		episode?: { played: boolean; onMarked: (played: boolean) => void };
		/** Where to show the menu (the click or the ⋮ button). */
		x: number;
		y: number;
	}
</script>

<script lang="ts">
	// The song menu (docs/features/ui.md#song-menu): right-click a song, or its ⋮ button.
	import { onMount, tick } from 'svelte';
	import type { Command } from '../protocol.gen';
	import { removeFromPlaylist } from '../api';
	import { autoplayThis, runCommand, startRadio } from '../songActions';
	import { listening } from '../listening.svelte';
	import { likes } from '../likes.svelte';
	import type { RoomConnection } from '../room.svelte';
	import { isYoutubeSong } from '../ownModule';
	import { youtube } from '../youtube.svelte';
	import Icon, { type IconName } from './Icon.svelte';

	interface Props {
		room: RoomConnection;
		target: MenuTarget;
		onClose: () => void;
		onToast: (text: string) => void;
		onFindSimilar: (song: Song) => void;
		onArtist: (artist: ArtistRef) => void;
		onAlbum: (album: AlbumRef, artist: string) => void;
		onPodcast: (podcast: PodcastRef) => void;
		/** Save to one of your YouTube Music playlists; only when signed in. */
		onSave?: (song: Song) => void;
	}
	let { room, target, onClose, onToast, onFindSimilar, onArtist, onAlbum, onPodcast, onSave }: Props = $props();

	const song = $derived(target.song);
	const item = $derived(target.item);
	const place = $derived(target.place);
	const queueLength = $derived(room.state?.queue.length ?? 0);
	const liked = $derived(likes.get(song.id));
	const rating = $derived(likes.rating(song.id));
	$effect(() => likes.load(song.id));
	const ytmId = $derived(song.id.startsWith('ytm:') || song.id.startsWith('yt:') ? song.id.slice(song.id.indexOf(':') + 1) : null);

	interface Entry {
		icon: IconName;
		label: string;
		run: () => void | Promise<void>;
		danger?: boolean;
		href?: string;
	}

	async function command(c: Command, done?: string) {
		onClose();
		await runCommand(room, c, onToast, done);
	}

	const entries = $derived.by(() => {
		const list: (Entry | null)[] = [];
		const fromAutoplay = place === 'autoplay' && item;
		if (room.can('add_songs')) {
			list.push({
				icon: 'playNext',
				label: 'Play next',
				run: () =>
					fromAutoplay
						? command({ kind: 'MoveItem', itemId: item!.itemId, list: 'queue', toIndex: 0 }, room.playNextText(`"${song.title}"`))
						: command({ kind: 'AddSongs', songs: [song], position: 'next' }, room.playNextText(`"${song.title}"`))
			});
			list.push({
				icon: 'playlistAdd',
				label: 'Add to queue',
				run: () =>
					fromAutoplay
						? command({ kind: 'MoveItem', itemId: item!.itemId, list: 'queue', toIndex: queueLength }, `Added "${song.title}" to the queue`)
						: command({ kind: 'AddSongs', songs: [song], position: 'end' }, `Added "${song.title}" to the queue`)
			});
		}
		if (place !== 'current' && room.can('play_now')) {
			list.push({
				icon: 'play',
				label: 'Play now',
				run: () => (item ? command({ kind: 'JumpTo', itemId: item.itemId }) : command({ kind: 'PlayNow', song }))
			});
		}
		if (place === 'current' && room.can('seek')) {
			list.push({ icon: 'previous', label: 'Play from the start', run: () => command({ kind: 'Seek', positionMs: 0 }) });
		}
		list.push(null);
		// Radio and "similar" are about music, not podcast episodes.
		if (!song.podcast) {
			const seed = song;
			if (room.can('start_radio')) {
				list.push({
					icon: 'radio',
					label: 'Start radio',
					run: () => {
						onClose();
						void startRadio(room, seed, onToast);
					}
				});
			}
			if (room.can('autoplay_from_here')) {
				list.push({
					icon: 'autoplay',
					label: 'Autoplay this',
					run: () => {
						onClose();
						void autoplayThis(room, seed, onToast);
					}
				});
			}
			// Read everything from the target before closing: closing clears it.
			const current = song;
			list.push({ icon: 'similar', label: 'Find similar', run: () => (onFindSimilar(current), onClose()) });
			list.push(null);
		}
		// Likes and playlists are YouTube Music's: not for local music.
		const fromYoutube = isYoutubeSong(song.id);
		if (youtube.account && fromYoutube) {
			const current = song;
			const wasCurrent = place === 'current';
			list.push({ icon: liked ? 'liked' : 'like', label: liked ? 'Remove like' : 'Like', run: () => (likes.set(current.id, !liked, onToast), onClose()) });
			list.push({
				icon: rating === 'dislike' ? 'disliked' : 'dislike',
				label: rating === 'dislike' ? 'Remove dislike' : 'Dislike',
				run: async () => {
					onClose();
					const disliking = rating !== 'dislike';
					// Like YouTube Music: a disliked song that's playing is skipped, in a solo room (a party's music is everyone's).
					const solo = room.state?.room.visibility === 'private';
					if ((await likes.rate(current.id, disliking ? 'dislike' : 'none', onToast)) && disliking && wasCurrent && solo && room.can('skip')) {
						await room.run({ kind: 'Skip', play: false });
					}
				}
			});
		}
		if (target.playlist) {
			const { id, itemId, likedMusic, onRemoved } = target.playlist;
			const current = song;
			if (likedMusic) {
				list.push({
					icon: 'remove',
					label: 'Remove from Liked music',
					danger: true,
					run: async () => {
						onClose();
						if (await likes.rate(current.id, 'none', onToast)) onRemoved();
					}
				});
			} else if (itemId) {
				list.push({
					icon: 'remove',
					label: 'Remove from this playlist',
					danger: true,
					run: async () => {
						onClose();
						try {
							await removeFromPlaylist(id, [{ songId: current.id, itemId }]);
							onToast(`Removed "${current.title}" from the playlist`);
							onRemoved();
						} catch (e) {
							onToast(e instanceof Error ? e.message : "Couldn't remove it");
						}
					}
				});
			}
		}
		if (onSave && fromYoutube) {
			const current = song;
			list.push({ icon: 'save', label: 'Save to playlist', run: () => (onSave(current), onClose()) });
		}
		list.push(null);
		if (song.podcast?.id) {
			const podcast = song.podcast;
			list.push({ icon: 'podcast', label: `Go to ${podcast.name}`, run: () => (onPodcast(podcast), onClose()) });
		}
		for (const artist of song.artists.slice(0, 3)) {
			list.push({ icon: 'person', label: `Go to ${artist.name}`, run: () => (onArtist(artist), onClose()) });
		}
		if (song.album) {
			const album = song.album;
			const artist = song.artists[0]?.name ?? '';
			list.push({ icon: 'album', label: `Go to album ${album.name}`, run: () => (onAlbum(album, artist), onClose()) });
		}
		if (ytmId) {
			list.push({ icon: 'open', label: 'Open in YouTube Music', href: `https://music.youtube.com/watch?v=${encodeURIComponent(ytmId)}`, run: onClose });
		}
		if (target.episode) {
			const { played, onMarked } = target.episode;
			const current = song;
			list.push({
				icon: played ? 'replay' : 'check',
				label: played ? 'Mark as unplayed' : 'Mark as played',
				run: async () => {
					onClose();
					try {
						await listening.markEpisode(current.id, !played);
						onMarked(!played);
					} catch (e) {
						onToast(e instanceof Error ? e.message : "Couldn't mark it");
					}
				}
			});
		}
		if (target.listened) {
			const { id, onRemoved } = target.listened;
			const current = song;
			list.push(null);
			list.push({
				icon: 'remove',
				label: 'Remove from your history',
				danger: true,
				run: async () => {
					onClose();
					try {
						await listening.remove(id);
						onToast(`Removed "${current.title}" from your history`);
						onRemoved();
					} catch (e) {
						onToast(e instanceof Error ? e.message : "Couldn't remove it");
					}
				}
			});
		}
		if (item && room.can(item.addedBy === room.participantId ? 'remove_own' : 'remove_others')) {
			list.push(null);
			list.push({
				icon: 'remove',
				// The current song too: the next one plays.
				label: place === 'history' ? 'Remove from history' : place === 'autoplay' ? 'Remove from autoplay' : 'Remove from queue',
				danger: true,
				run: () => command({ kind: 'RemoveQueueItem', itemId: item.itemId })
			});
		}
		// No separators at the start, the end, or twice in a row.
		return list.filter((e, i, all) => e !== null || (i > 0 && i < all.length - 1 && all[i - 1] !== null));
	});

	// Keep the menu on screen.
	let menu = $state<HTMLElement>();
	let left = $state(0);
	let top = $state(0);
	onMount(async () => {
		left = target.x;
		top = target.y;
		await tick();
		if (!menu) return;
		const rect = menu.getBoundingClientRect();
		left = Math.max(8, Math.min(target.x, window.innerWidth - rect.width - 8));
		top = target.y + rect.height > window.innerHeight - 8 ? Math.max(8, target.y - rect.height) : target.y;
	});
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && onClose()} onresize={onClose} />

<!-- Click anywhere else (or right-click) to close. -->
<div class="fixed inset-0 z-[55]" role="presentation" onclick={onClose} oncontextmenu={(e) => (e.preventDefault(), onClose())}></div>
<div
	bind:this={menu}
	class="fixed z-[56] w-64 overflow-hidden rounded-lg bg-raised py-1 shadow-2xl ring-1 ring-line"
	style:left="{left}px"
	style:top="{top}px"
	role="menu"
>
	<div class="truncate border-b border-line px-4 pt-1 pb-2 text-sm text-muted">{song.title}</div>
	{#each entries as entry, i (i)}
		{#if entry === null}
			<div class="my-1 h-px bg-line"></div>
		{:else if entry.href}
			<a role="menuitem" class="flex w-full items-center gap-3 px-4 py-2 hover:bg-line" href={entry.href} target="_blank" rel="noopener" onclick={entry.run}>
				<Icon name={entry.icon} size={20} class="shrink-0" />
				<span class="truncate">{entry.label}</span>
			</a>
		{:else}
			<button role="menuitem" class="flex w-full items-center gap-3 px-4 py-2 text-left hover:bg-line {entry.danger ? 'text-accent' : ''}" onclick={entry.run}>
				<Icon name={entry.icon} size={20} class="shrink-0" />
				<span class="truncate">{entry.label}</span>
			</button>
		{/if}
	{/each}
</div>

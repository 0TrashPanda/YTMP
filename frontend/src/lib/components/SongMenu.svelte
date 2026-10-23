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
	// The song menu (docs/features/ui.md#song-menu): right-click a song, or its ⋮ button. Laid out
	// as set in Settings → Song menu (songMenu.svelte.ts). Phones get a sheet from the bottom, like
	// YouTube Music's, with big buttons at the top; wider screens a menu where it was opened.
	import { onMount, tick } from 'svelte';
	import { MediaQuery } from 'svelte/reactivity';
	import { fly, fade } from 'svelte/transition';
	import type { Command } from '../protocol.gen';
	import { removeFromPlaylist } from '../api';
	import { autoplayThis, runCommand, startRadio } from '../songActions';
	import { listening } from '../listening.svelte';
	import { likes } from '../likes.svelte';
	import type { RoomConnection } from '../room.svelte';
	import { isYoutubeSong } from '../ownModule';
	import { canDownload, downloadSong } from '../downloads';
	import { youtube } from '../youtube.svelte';
	import { sleepTimer } from '../sleepTimer.svelte';
	import { arrange, songMenuLayout, type MenuAction } from '../songMenu.svelte';
	import { artistNames } from '../format';
	import Art from './Art.svelte';
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
		/** The sleep timer (in the menu of the song that's playing). */
		onSleepTimer?: () => void;
	}
	let { room, target, onClose, onToast, onFindSimilar, onArtist, onAlbum, onPodcast, onSave, onSleepTimer }: Props = $props();

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
		const list: Partial<Record<MenuAction, Entry[]>> = {};
		const add = (action: MenuAction, entry: Entry) => (list[action] ??= []).push(entry);
		const fromAutoplay = place === 'autoplay' && item;
		if (room.can('add_songs')) {
			add('playNext', {
				icon: 'playNext',
				label: 'Play next',
				run: () =>
					fromAutoplay
						? command({ kind: 'MoveItem', itemId: item!.itemId, list: 'queue', toIndex: 0 }, room.playNextText(`"${song.title}"`))
						: command({ kind: 'AddSongs', songs: [song], position: 'next' }, room.playNextText(`"${song.title}"`))
			});
			add('addToQueue', {
				icon: 'playlistAdd',
				label: 'Add to queue',
				run: () =>
					fromAutoplay
						? command({ kind: 'MoveItem', itemId: item!.itemId, list: 'queue', toIndex: queueLength }, `Added "${song.title}" to the queue`)
						: command({ kind: 'AddSongs', songs: [song], position: 'end' }, `Added "${song.title}" to the queue`)
			});
		}
		if (place !== 'current' && room.can('play_now')) {
			add('playNow', {
				icon: 'play',
				label: 'Play now',
				run: () => (item ? command({ kind: 'JumpTo', itemId: item.itemId }) : command({ kind: 'PlayNow', song }))
			});
		}
		if (place === 'current' && room.can('seek')) {
			add('playNow', { icon: 'previous', label: 'Play from the start', run: () => command({ kind: 'Seek', positionMs: 0 }) });
		}
		// Radio and "similar" are about music, not podcast episodes.
		if (!song.podcast) {
			const seed = song;
			if (room.can('start_radio')) {
				add('radio', {
					icon: 'radio',
					label: 'Start radio',
					run: () => {
						onClose();
						void startRadio(room, seed, onToast);
					}
				});
			}
			if (room.can('autoplay_from_here')) {
				add('autoplay', {
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
			add('similar', { icon: 'similar', label: 'Find similar', run: () => (onFindSimilar(current), onClose()) });
			}
		// Likes and playlists are YouTube Music's: not for local music.
		const fromYoutube = isYoutubeSong(song.id);
		if (youtube.account && fromYoutube) {
			const current = song;
			const wasCurrent = place === 'current';
			add('like', { icon: liked ? 'liked' : 'like', label: liked ? 'Remove like' : 'Like', run: () => (likes.set(current.id, !liked, onToast), onClose()) });
			add('dislike', {
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
				add('removeFromPlaylist', {
					icon: 'remove',
					label: 'Remove from Liked music',
					danger: true,
					run: async () => {
						onClose();
						if (await likes.rate(current.id, 'none', onToast)) onRemoved();
					}
				});
			} else if (itemId) {
				add('removeFromPlaylist', {
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
			add('save', { icon: 'save', label: 'Save to playlist', run: () => (onSave(current), onClose()) });
		}
		if (canDownload(song)) {
			const current = song;
			add('download', { icon: 'download', label: 'Download', run: () => (onClose(), void downloadSong(current, onToast)) });
		}
		if (place === 'current' && onSleepTimer) {
			add('sleepTimer', { icon: 'sleep', label: sleepTimer.active ? 'Sleep timer (on)' : 'Sleep timer', run: () => (onSleepTimer(), onClose()) });
		}
		if (song.podcast?.id) {
			const podcast = song.podcast;
			add('goPodcast', { icon: 'podcast', label: `Go to ${podcast.name}`, run: () => (onPodcast(podcast), onClose()) });
		}
		for (const artist of song.artists.slice(0, 3)) {
			add('goArtist', { icon: 'person', label: `Go to ${artist.name}`, run: () => (onArtist(artist), onClose()) });
		}
		if (song.album) {
			const album = song.album;
			const artist = song.artists[0]?.name ?? '';
			add('goAlbum', { icon: 'album', label: `Go to album ${album.name}`, run: () => (onAlbum(album, artist), onClose()) });
		}
		if (ytmId) {
			add('openYtm', { icon: 'open', label: 'Open in YouTube Music', href: `https://music.youtube.com/watch?v=${encodeURIComponent(ytmId)}`, run: onClose });
		}
		if (target.episode) {
			const { played, onMarked } = target.episode;
			const current = song;
			add('markPlayed', {
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
				add('removeListened', {
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
				add('remove', {
				icon: 'remove',
				// The current song too: the next one plays.
				label: place === 'history' ? 'Remove from history' : place === 'autoplay' ? 'Remove from autoplay' : 'Remove from queue',
				danger: true,
				run: () => command({ kind: 'RemoveQueueItem', itemId: item.itemId })
			});
		}
		return list;
	});

	/** Phones: a sheet from the bottom, with the big buttons. */
	const phone = new MediaQuery('max-width: 639px');
	const layout = $derived(arrange(songMenuLayout(), entries, phone.current));

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

	// The sheet follows the finger down, and closes when let go far enough (from the top of its list).
	let list = $state<HTMLElement>();
	let swipe: { y: number; ok: boolean } | null = null;
	let dragY = $state(0);
	function touchStart(e: TouchEvent) {
		swipe = { y: e.touches[0].clientY, ok: !list || list.scrollTop <= 0 || !list.contains(e.target as Node) };
	}
	function touchMove(e: TouchEvent) {
		if (!swipe?.ok) return;
		const dy = e.touches[0].clientY - swipe.y;
		if (dragY === 0 && dy < 0) swipe.ok = false;
		else dragY = Math.max(0, dy);
	}
	function touchEnd() {
		if (dragY > 80) onClose();
		else dragY = 0;
		swipe = null;
	}
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && onClose()} onresize={() => !phone.current && onClose()} />

{#snippet row(entry: Entry, big: boolean)}
	{@const style = `flex w-full items-center ${big ? 'gap-5 px-5 py-3.5' : 'gap-3 px-4 py-2'} text-left hover:bg-line active:bg-line ${entry.danger ? 'text-accent' : ''}`}
	{#if entry.href}
		<a role="menuitem" class={style} href={entry.href} target="_blank" rel="noopener" onclick={entry.run}>
			<Icon name={entry.icon} size={big ? 24 : 20} class="shrink-0" />
			<span class="truncate">{entry.label}</span>
		</a>
	{:else}
		<button role="menuitem" class={style} onclick={entry.run}>
			<Icon name={entry.icon} size={big ? 24 : 20} class="shrink-0" />
			<span class="truncate">{entry.label}</span>
		</button>
	{/if}
{/snippet}

{#if phone.current}
	<div class="fixed inset-0 z-[55] bg-black/60" role="presentation" transition:fade|global={{ duration: 150 }} onclick={onClose}></div>
	<div
		class="fixed inset-x-0 bottom-0 z-[56] flex max-h-[85vh] flex-col rounded-t-2xl bg-raised pb-[env(safe-area-inset-bottom)] shadow-2xl {dragY ? '' : 'transition-transform'}"
		style:transform="translateY({dragY}px)"
		role="menu"
		tabindex="-1"
		transition:fly|global={{ y: 500, duration: 200, opacity: 1 }}
		ontouchstart={touchStart}
		ontouchmove={touchMove}
		ontouchend={touchEnd}
		ontouchcancel={touchEnd}
	>
		<div class="mx-auto mt-2 h-1 w-9 shrink-0 rounded-full bg-white/30"></div>
		<div class="flex shrink-0 items-center gap-3 px-4 pt-3 pb-4">
			<Art {song} size={48} class="h-12 w-12" />
			<div class="min-w-0 flex-1">
				<p class="truncate font-medium">{song.title}</p>
				<p class="truncate text-sm text-muted">{song.podcast?.name ?? artistNames(song)}</p>
			</div>
		</div>
		{#if layout.buttons.length}
			<div class="grid shrink-0 gap-2 px-4 pb-3" style:grid-template-columns="repeat({layout.buttons.length}, minmax(0, 1fr))">
				{#each layout.buttons as entry, i (i)}
					{#if entry.href}
						<a role="menuitem" class="flex flex-col items-center gap-1.5 rounded-xl bg-white/10 px-1 py-3 text-center text-xs active:bg-white/20" href={entry.href} target="_blank" rel="noopener" onclick={entry.run}>
							<Icon name={entry.icon} size={24} />
							<span class="line-clamp-2">{entry.label}</span>
						</a>
					{:else}
						<button role="menuitem" class="flex flex-col items-center gap-1.5 rounded-xl bg-white/10 px-1 py-3 text-center text-xs active:bg-white/20 {entry.danger ? 'text-accent' : ''}" onclick={entry.run}>
							<Icon name={entry.icon} size={24} />
							<span class="line-clamp-2">{entry.label}</span>
						</button>
					{/if}
				{/each}
			</div>
		{/if}
		<div bind:this={list} class="min-h-0 flex-1 overflow-y-auto overscroll-contain border-t border-line py-2">
			{#each layout.list as entry, i (i)}
				{#if entry === null}
					<div class="mx-5 my-1 h-px bg-line"></div>
				{:else}
					{@render row(entry, true)}
				{/if}
			{/each}
		</div>
	</div>
{:else}
	<!-- Click anywhere else (or right-click) to close. -->
	<div class="fixed inset-0 z-[55]" role="presentation" onclick={onClose} oncontextmenu={(e) => (e.preventDefault(), onClose())}></div>
	<div
		bind:this={menu}
		class="fixed z-[56] max-h-[calc(100vh-16px)] w-64 overflow-y-auto rounded-lg bg-raised py-1 shadow-2xl ring-1 ring-line"
		style:left="{left}px"
		style:top="{top}px"
		role="menu"
	>
		<div class="truncate border-b border-line px-4 pt-1 pb-2 text-sm text-muted">{song.title}</div>
		{#each layout.list as entry, i (i)}
			{#if entry === null}
				<div class="my-1 h-px bg-line"></div>
			{:else}
				{@render row(entry, false)}
			{/if}
		{/each}
	</div>
{/if}

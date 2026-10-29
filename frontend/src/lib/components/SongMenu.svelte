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
	import type { Command, RadioFrom, RadioOptions } from '../protocol.gen';
	import { keepOffline, removeFromPlaylist, saveToPlaylist } from '../api';
	import { autoplayThis, quoted, runCommand, startRadio, type Toast } from '../songActions';
	import { hold } from '../hold';
	import { blockArtist, blockSong, isBlockedArtist, isBlockedSong, radioOptions } from '../radio.svelte';
	import { settings } from '../settings.svelte';
	import { nativeBridge } from '../native';
	import RadioChoices from './RadioChoices.svelte';
	import { listening } from '../listening.svelte';
	import { likes } from '../likes.svelte';
	import type { RoomConnection } from '../room.svelte';
	import { isYoutubeSong } from '../ownModule.svelte';
	import { canDownload, downloadSong } from '../downloads';
	import { youtube } from '../youtube.svelte';
	import { sleepTimer } from '../sleepTimer.svelte';
	import { arrange, holds, shownChoices, songMenuLayout, type MenuAction } from '../songMenu.svelte';
	import { artistNames } from '../format';
	import Art from './Art.svelte';
	import Icon, { type IconName } from './Icon.svelte';

	interface Props {
		room: RoomConnection;
		target: MenuTarget;
		onClose: () => void;
		onToast: Toast;
		/** Find similar, from both or only one of YouTube Music and the song graph ([from]). */
		onFindSimilar: (song: Song, from?: RadioFrom) => void;
		onArtist: (artist: ArtistRef) => void;
		onAlbum: (album: AlbumRef, artist: string) => void;
		onPodcast: (podcast: PodcastRef) => void;
		/** Save to one of your YouTube Music playlists; only when signed in. */
		onSave?: (song: Song) => void;
		/** The sleep timer (in the menu of the song that's playing). */
		onSleepTimer?: () => void;
		/** The song graph around a song (with your YTMP account). */
		onGraph?: (song: Song) => void;
	}
	let { room, target, onClose, onToast, onFindSimilar, onArtist, onAlbum, onPodcast, onSave, onSleepTimer, onGraph }: Props = $props();

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
		/** Holding it (or right-click, or its ›): its choices, or something straight away. */
		held?: () => void;
	}

	/** A choice in the sheet of a held action. */
	interface Choice {
		icon: IconName;
		label: string;
		run: () => void | Promise<void>;
		danger?: boolean;
	}

	/** What a held action shows: choices, or the choices for a radio. */
	type Sub = { title: string; choices: Choice[] } | { title: string; radio: 'radio' | 'autoplay' | 'similar'; seed: Song; show: string[] };
	let sub = $state<Sub | null>(null);
	let radioPick = $state<RadioOptions>(radioOptions());

	/** Holding [action] opens [make]'s choices (only those that show), or nothing when there are none. */
	function choicesFor(action: MenuAction, title: string, make: () => (Choice & { id: string })[]): (() => void) | undefined {
		if (!holds(action)) return undefined;
		return () => {
			const shown = shownChoices(action);
			const choices = make().filter((c) => shown.includes(c.id));
			if (choices.length) sub = { title, choices };
		};
	}

	function radioFor(action: 'radio' | 'autoplay' | 'similar', seed: Song, title: string): (() => void) | undefined {
		if (!holds(action)) return undefined;
		return () => {
			radioPick = radioOptions();
			sub = { title, radio: action, seed, show: shownChoices(action) };
		};
	}

	function startPicked() {
		if (!sub || !('radio' in sub)) return;
		const { radio, seed } = sub;
		const options = radioPick;
		onClose();
		if (radio === 'radio') void startRadio(room, seed, onToast, quoted(seed), options);
		else if (radio === 'autoplay') void autoplayThis(room, seed, onToast, options);
		else onFindSimilar(seed, options.from);
	}

	/** On the phone's own pages, which keep its song cache (Download's "keep it offline"). */
	const onThisPhone = !!nativeBridge && ['127.0.0.1', 'localhost'].includes(location.hostname);
	const party = $derived((room.state?.participants.filter((p) => p.online && !p.screen).length ?? 0) > 1);

	/** Removes [items] one by one (the ones you may remove). */
	async function removeAll(items: QueueItem[], what: string) {
		onClose();
		const mine = items.filter((i) => room.can(i.addedBy === room.participantId ? 'remove_own' : 'remove_others'));
		for (const i of mine) await room.run({ kind: 'RemoveQueueItem', itemId: i.itemId });
		onToast(mine.length ? `Removed ${mine.length} ${mine.length === 1 ? 'song' : 'songs'} ${what}` : 'You may not remove those songs');
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
			const current = song;
			const where = (action: MenuAction) =>
				choicesFor(action, current.title, () => [
					{ id: 'next', icon: 'playNext', label: 'Play next', run: () => command({ kind: 'AddSongs', songs: [current], position: 'next' }, room.playNextText(quoted(current))) },
					{ id: 'end', icon: 'playlistAdd', label: 'Add to the end of the queue', run: () => command({ kind: 'AddSongs', songs: [current], position: 'end' }, `Added ${quoted(current)} to the queue`) },
					...(fromAutoplay
						? []
						: [{ id: 'autoplay', icon: 'autoplay' as IconName, label: 'Add to autoplay', run: () => command({ kind: 'AddSongs', songs: [current], position: 'autoplay' }, `${quoted(current)} plays when the queue runs out`) }])
				]);
			add('playNext', {
				icon: 'playNext',
				label: 'Play next',
				run: () =>
					fromAutoplay
						? command({ kind: 'MoveItem', itemId: item!.itemId, list: 'queue', toIndex: 0 }, room.playNextText(`"${song.title}"`))
						: command({ kind: 'AddSongs', songs: [song], position: 'next' }, room.playNextText(`"${song.title}"`)),
				held: where('playNext')
			});
			add('addToQueue', {
				icon: 'playlistAdd',
				label: 'Add to queue',
				run: () =>
					fromAutoplay
						? command({ kind: 'MoveItem', itemId: item!.itemId, list: 'queue', toIndex: queueLength }, `Added "${song.title}" to the queue`)
						: command({ kind: 'AddSongs', songs: [song], position: 'end' }, `Added "${song.title}" to the queue`),
				held: where('addToQueue')
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
					},
					held: radioFor('radio', seed, `Radio from ${quoted(seed)}`)
				});
			}
			if (room.can('autoplay_from_here')) {
				add('autoplay', {
					icon: 'autoplay',
					label: 'Autoplay this',
					run: () => {
						onClose();
						void autoplayThis(room, seed, onToast);
					},
					held: radioFor('autoplay', seed, `Autoplay: songs like ${quoted(seed)}`)
				});
			}
			// Read everything from the target before closing: closing clears it.
			const current = song;
			add('similar', { icon: 'similar', label: 'Find similar', run: () => (onFindSimilar(current), onClose()), held: radioFor('similar', current, `Songs like ${quoted(current)}`) });
			if (onGraph && listening.available) add('graph', { icon: 'graph', label: 'Song graph', run: () => (onClose(), onGraph(current)) });
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
				},
				held: choicesFor('dislike', current.title, () => {
					const artist = current.artists[0]?.name;
					const dislike = async () => {
						if (rating !== 'dislike') await likes.rate(current.id, 'dislike', onToast);
					};
					return [
						...(artist
							? [
									isBlockedArtist(artist)
										? { id: 'artist', icon: 'radio' as IconName, label: `Play ${artist} in radios again`, run: () => (onClose(), blockArtist(artist, false), onToast(`${artist} can be in radios again`)) }
										: {
												id: 'artist',
												icon: 'dislike' as IconName,
												label: `Dislike, and don't play ${artist} in radios`,
												run: async () => (onClose(), blockArtist(artist), onToast(`Radios leave out ${artist}`), await dislike())
											}
								]
							: []),
						isBlockedSong(current.id)
							? { id: 'never', icon: 'radio' as IconName, label: 'Allow this song in radios again', run: () => (onClose(), blockSong(current.id, false), onToast(`${quoted(current)} can be in radios again`)) }
							: {
									id: 'never',
									icon: 'dislike' as IconName,
									label: 'Dislike, and never put it in radios',
									run: async () => (onClose(), blockSong(current.id), onToast(`Radios leave out ${quoted(current)}`), await dislike())
								}
					];
				})
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
			const last = settings.lastPlaylist.value;
			add('save', {
				icon: 'save',
				label: 'Save to playlist',
				run: () => (onSave(current), onClose()),
				// Like YouTube Music: straight into the playlist you saved to last, with a way to pick another.
				held:
					holds('save') && last
						? async () => {
								onClose();
								try {
									await saveToPlaylist(last.id, [current.id]);
									onToast(`Saved to ${last.title}`, { label: 'Change', run: () => onSave(current) });
								} catch (e) {
									onToast(e instanceof Error ? e.message : "Couldn't save it");
								}
							}
						: undefined
			});
		}
		if (canDownload(song)) {
			const current = song;
			add('download', {
				icon: 'download',
				label: 'Download',
				run: () => (onClose(), void downloadSong(current, onToast)),
				held: onThisPhone
					? choicesFor('download', current.title, () => [
							{ id: 'file', icon: 'download', label: 'Save as a music file', run: () => (onClose(), void downloadSong(current, onToast)) },
							{
								id: 'offline',
								icon: 'phone',
								label: 'Only keep it offline in the app',
								run: async () => {
									onClose();
									try {
										await keepOffline(current);
										onToast(`${quoted(current)} plays offline now`);
									} catch (e) {
										onToast(e instanceof Error ? e.message : "Couldn't keep it offline");
									}
								}
							}
						])
					: undefined
			});
		}
		if (place === 'current' && onSleepTimer) {
			const last = sleepTimer.last;
			add('sleepTimer', {
				icon: 'sleep',
				label: sleepTimer.active ? 'Sleep timer (on)' : 'Sleep timer',
				run: () => (onSleepTimer(), onClose()),
				held:
					holds('sleepTimer') && last !== null
						? () => {
								onClose();
								sleepTimer.set(last);
								onToast(last === 'song' ? 'Stops at the end of this song' : `Stops in ${last} min`, { label: 'Change', run: () => onSleepTimer() });
							}
						: undefined
			});
		}
		if (song.podcast?.id) {
			const podcast = song.podcast;
			add('goPodcast', { icon: 'podcast', label: `Go to ${podcast.name}`, run: () => (onPodcast(podcast), onClose()) });
		}
		const pickArtist =
			song.artists.length > 1
				? choicesFor('goArtist', 'Go to artist', () => song.artists.map((a) => ({ id: 'pick', icon: 'person' as IconName, label: a.name, run: () => (onArtist(a), onClose()) })))
				: undefined;
		// One entry for the first artist, the others by holding it; with holding off, one each (up to 3).
		if (pickArtist) {
			const first = song.artists[0];
			add('goArtist', { icon: 'person', label: `Go to ${first.name}`, run: () => (onArtist(first), onClose()), held: pickArtist });
		} else {
			for (const artist of song.artists.slice(0, 3)) add('goArtist', { icon: 'person', label: `Go to ${artist.name}`, run: () => (onArtist(artist), onClose()) });
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
				run: () => command({ kind: 'RemoveQueueItem', itemId: item.itemId }),
				held: choicesFor('remove', song.title, () => {
					const list = place === 'history' ? (room.state?.history ?? []) : place === 'autoplay' ? (room.state?.autoplay ?? []) : (room.state?.queue ?? []);
					const artist = item.song.artists[0]?.name;
					return [
						...(artist
							? [{ id: 'artist', icon: 'remove' as IconName, label: `Remove the songs by ${artist}`, danger: true, run: () => removeAll(list.filter((i) => i.song.artists[0]?.name === artist), `by ${artist}`) }]
							: []),
						{
							id: 'person',
							icon: 'remove' as IconName,
							label: `Remove the songs ${item.addedBy === room.participantId ? 'you' : item.addedByName} added`,
							danger: true,
							run: () => removeAll(list.filter((i) => i.addedBy === item.addedBy), `added by ${item.addedBy === room.participantId ? 'you' : item.addedByName}`)
						}
					];
				})
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
	{@const style = `flex min-w-0 flex-1 items-center ${big ? 'gap-5 px-5 py-3.5' : 'gap-3 px-4 py-2'} text-left hover:bg-line active:bg-line ${entry.danger ? 'text-accent' : ''}`}
	<div class="flex w-full items-stretch">
		{#if entry.href}
			<a role="menuitem" class={style} href={entry.href} target="_blank" rel="noopener" onclick={entry.run}>
				<Icon name={entry.icon} size={big ? 24 : 20} class="shrink-0" />
				<span class="truncate">{entry.label}</span>
			</a>
		{:else}
			<button role="menuitem" class={style} onclick={entry.run} use:hold={entry.held}>
				<Icon name={entry.icon} size={big ? 24 : 20} class="shrink-0" />
				<span class="truncate">{entry.label}</span>
			</button>
		{/if}
		<!-- With a mouse there's no holding: the › opens the choices. -->
		{#if entry.held && !big}
			<button class="shrink-0 px-2 text-muted hover:bg-line hover:text-white" aria-label="More choices for {entry.label}" title="More choices" onclick={entry.held}>
				<Icon name="chevron" size={18} />
			</button>
		{/if}
	</div>
{/snippet}

{#snippet held(s: Sub, big: boolean)}
	<div class="flex shrink-0 items-center gap-2 {big ? 'px-3 pt-1 pb-3' : 'border-b border-line px-2 pb-1'}">
		<button class="rounded-full p-2 hover:bg-line" aria-label="Back" onclick={() => (sub = null)}><Icon name="back" size={20} /></button>
		<p class="min-w-0 flex-1 truncate {big ? 'font-medium' : 'text-sm text-muted'}">{s.title}</p>
	</div>
	{#if 'radio' in s}
		<div class="flex flex-col gap-4 overflow-y-auto {big ? 'px-5 pb-4' : 'px-4 py-3'}">
			<RadioChoices bind:value={radioPick} show={s.show} hasAccount={!!youtube.account} {party} />
			<button class="self-start rounded-full bg-white px-5 py-2 text-sm font-medium text-black" onclick={startPicked}>
				{s.radio === 'similar' ? 'Find similar' : s.radio === 'autoplay' ? 'Autoplay this' : 'Start radio'}
			</button>
		</div>
	{:else}
		<div class="min-h-0 flex-1 overflow-y-auto {big ? 'py-2' : 'py-1'}">
			{#each s.choices as c, i (i)}
				<button role="menuitem" class="flex w-full items-center {big ? 'gap-5 px-5 py-3.5' : 'gap-3 px-4 py-2'} text-left hover:bg-line active:bg-line {c.danger ? 'text-accent' : ''}" onclick={c.run}>
					<Icon name={c.icon} size={big ? 24 : 20} class="shrink-0" />
					<span class="truncate">{c.label}</span>
				</button>
			{/each}
		</div>
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
		{#if sub}
			<div class="flex min-h-0 flex-1 flex-col pt-2">{@render held(sub, true)}</div>
		{:else}
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
						<button role="menuitem" class="flex flex-col items-center gap-1.5 rounded-xl bg-white/10 px-1 py-3 text-center text-xs active:bg-white/20 {entry.danger ? 'text-accent' : ''}" onclick={entry.run} use:hold={entry.held}>
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
		{/if}
	</div>
{:else}
	<!-- Click anywhere else (or right-click) to close. -->
	<div class="fixed inset-0 z-[55]" role="presentation" onclick={onClose} oncontextmenu={(e) => (e.preventDefault(), onClose())}></div>
	<div
		bind:this={menu}
		class="fixed z-[56] max-h-[calc(100vh-16px)] {sub && 'radio' in sub ? 'w-80' : 'w-64'} overflow-y-auto rounded-lg bg-raised py-1 shadow-2xl ring-1 ring-line"
		style:left="{left}px"
		style:top="{top}px"
		role="menu"
	>
		{#if sub}
			<div class="flex flex-col">{@render held(sub, false)}</div>
		{:else}
			<div class="truncate border-b border-line px-4 pt-1 pb-2 text-sm text-muted">{song.title}</div>
			{#each layout.list as entry, i (i)}
				{#if entry === null}
					<div class="my-1 h-px bg-line"></div>
				{:else}
					{@render row(entry, false)}
				{/if}
			{/each}
		{/if}
	</div>
{/if}

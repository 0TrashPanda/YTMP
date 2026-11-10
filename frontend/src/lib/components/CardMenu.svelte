<script lang="ts">
	// The menu of an album, playlist, artist or podcast card, like YouTube Music's: hold the card,
	// right-click it, or its ⋮ with a mouse (cardMenu.svelte.ts). A sheet from the bottom on a
	// phone, a menu at the card on a computer. Play, Shuffle play, Play next and Add do what the
	// page's buttons do, with the same limits (Settings → Adding songs).
	import { tick } from 'svelte';
	import { MediaQuery } from 'svelte/reactivity';
	import { getAlbum, getArtist, getPlaylist, getPlaylistPart, getPodcast, topSongs } from '../api';
	import { whileBusy } from '../busy.svelte';
	import { cardLink, cardMenu, type CardTarget } from '../cardMenu.svelte';
	import { isPinned, togglePin } from '../home.svelte';
	import { keepInView } from '../keepInView';
	import { modal } from '../modal';
	import { motionMs } from '../motion.svelte';
	import type { Song } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import { addToQueue, playNow, startRadio } from '../songActions';
	import { youtube } from '../youtube.svelte';
	import { fade, fly } from 'svelte/transition';
	import type { IconName } from './Icon.svelte';
	import Icon from './Icon.svelte';
	import PlayAllButtons from './PlayAllButtons.svelte';

	let { room, onToast }: { room: RoomConnection; onToast: (text: string) => void } = $props();

	const phone = new MediaQuery('max-width: 639px');
	const open = $derived(cardMenu.open);
	const close = () => (cardMenu.open = null);

	type Entry = { icon: IconName; label: string; run: () => void };

	/**
	 * The album's or playlist's songs for Play, Shuffle play, Play next and Add, handed to a
	 * PlayAllButtons without buttons (it asks how many for a long one, and says when it loads).
	 */
	let runner = $state<{ songs: Song[]; total: number | null; whole?: (count: number) => Promise<Song[]>; name: string; toAutoplay: boolean } | null>(null);
	let buttons = $state<PlayAllButtons>();

	/** A playlist: yours (private, through your sign-in) when it doesn't open otherwise, like its page. */
	async function playlistPage(id: string) {
		try {
			return { page: await getPlaylist(id), personal: false };
		} catch (e) {
			if (!youtube.account) throw e;
			return { page: await getPlaylist(id, true), personal: true };
		}
	}

	/** Loads the songs of [target] (an album or playlist) and does [action] with them. */
	async function withList(target: CardTarget, action: (b: PlayAllButtons) => Promise<void>) {
		close();
		try {
			const songs = await whileBusy(`Loading ${target.title}…`, async () => {
				if (target.kind === 'album') {
					const album = await getAlbum(target.id);
					return { songs: album.songs, total: album.songs.length, name: album.title, toAutoplay: false };
				}
				const { page, personal } = await playlistPage(target.id);
				let list = page;
				// A long playlist: more parts as needed (the ones a long Play needs).
				const whole = page.more
					? async (count: number) => {
							while (list.more && list.songs.length < count) {
								const next = await getPlaylistPart(list.id, list.more, personal);
								list = { ...list, songs: [...list.songs, ...next.songs], more: next.more };
							}
							return list.songs;
						}
					: undefined;
				return { songs: page.songs, total: page.songCount ?? null, whole, name: page.title, toAutoplay: true };
			});
			runner = songs;
			await tick();
			if (buttons) await action(buttons);
		} catch (e) {
			onToast(e instanceof Error ? e.message : `Couldn't load ${target.title}`);
		}
	}

	/** The first song of an album or playlist, or an artist's top song: where a radio starts. */
	async function radioFrom(target: CardTarget) {
		close();
		try {
			const first = await whileBusy(`Loading ${target.title}…`, async () => {
				if (target.kind === 'artist') return (await getArtist(target.id)).songs[0];
				if (target.kind === 'album') return (await getAlbum(target.id)).songs[0];
				return (await playlistPage(target.id)).page.songs[0];
			});
			if (first) await startRadio(room, first, onToast, target.title);
			else onToast(`${target.title} has no songs`);
		} catch (e) {
			onToast(e instanceof Error ? e.message : `Couldn't load ${target.title}`);
		}
	}

	/** An artist's top songs: shuffled and played, or added to the queue. */
	async function artistSongs(target: CardTarget, shuffle: boolean) {
		close();
		try {
			const songs = await whileBusy(`Loading songs by ${target.title}…`, async () => topSongs(await getArtist(target.id)));
			if (!songs.length) return onToast(`No songs by ${target.title}`);
			const name = `${songs.length} songs by ${target.title}`;
			if (!shuffle) return void (await addToQueue(room, songs, name, onToast));
			const list = [...songs];
			for (let i = list.length - 1; i > 0; i--) {
				const j = Math.floor(Math.random() * (i + 1));
				[list[i], list[j]] = [list[j], list[i]];
			}
			const [first, ...rest] = list;
			let e = await room.run({ kind: 'PlayNow', song: first });
			if (!e && rest.length) e = await room.run({ kind: 'AddSongs', songs: rest, position: 'next' });
			if (e && !room.wasNoticed(e)) onToast(e.message);
		} catch (e) {
			onToast(e instanceof Error ? e.message : `Couldn't load ${target.title}`);
		}
	}

	async function latestEpisode(target: CardTarget) {
		close();
		try {
			const episode = await whileBusy(`Loading ${target.title}…`, async () => (await getPodcast(target.id)).episodes[0]);
			if (episode) await playNow(room, episode.song, onToast);
			else onToast(`${target.title} has no episodes`);
		} catch (e) {
			onToast(e instanceof Error ? e.message : `Couldn't load ${target.title}`);
		}
	}

	async function share(target: CardTarget) {
		close();
		const url = cardLink(target);
		try {
			if (navigator.share) return void (await navigator.share({ title: target.title, url }));
		} catch (e) {
			if (e instanceof DOMException && e.name === 'AbortError') return;
		}
		try {
			await navigator.clipboard.writeText(url);
			onToast('Link copied');
		} catch {
			onToast(url);
		}
	}

	function pin(target: CardTarget) {
		close();
		const pinned = isPinned(target.kind, target.id);
		togglePin({ kind: target.kind, id: target.id, title: target.title, art: target.art });
		onToast(pinned ? 'Unpinned from Home' : 'Pinned to Home');
	}

	const entries = $derived.by((): Entry[] => {
		const t = open?.target;
		if (!t) return [];
		const list: Entry[] = [];
		const pinned = isPinned(t.kind, t.id);
		if (t.kind === 'album' || t.kind === 'playlist') {
			const toAutoplay = t.kind === 'playlist' && room.can('autoplay_from_here');
			if (room.can('play_now')) {
				list.push({ icon: 'play', label: 'Play', run: () => withList(t, (b) => b.playNow()) });
				list.push({ icon: 'shuffle', label: 'Shuffle play', run: () => withList(t, (b) => b.shufflePlay()) });
			}
			if (room.can('add_songs')) {
				list.push({ icon: 'playNext', label: 'Play next', run: () => withList(t, (b) => b.addAll('next')) });
				if (toAutoplay) list.push({ icon: 'autoplay', label: 'Add to autoplay', run: () => withList(t, (b) => b.addToAutoplay()) });
				else list.push({ icon: 'playlistAdd', label: 'Add to queue', run: () => withList(t, (b) => b.addAll('end')) });
			}
			if (room.can('start_radio')) list.push({ icon: 'radio', label: 'Start radio', run: () => radioFrom(t) });
		} else if (t.kind === 'artist') {
			if (room.can('start_radio')) list.push({ icon: 'radio', label: 'Start radio', run: () => radioFrom(t) });
			if (room.can('play_now')) list.push({ icon: 'shuffle', label: 'Shuffle songs', run: () => artistSongs(t, true) });
			if (room.can('add_songs')) list.push({ icon: 'playlistAdd', label: 'Add top songs to the queue', run: () => artistSongs(t, false) });
		} else if (room.can('play_now')) {
			list.push({ icon: 'play', label: 'Play the latest episode', run: () => latestEpisode(t) });
		}
		list.push({ icon: 'pin', label: pinned ? 'Unpin from Home' : 'Pin to Home', run: () => pin(t) });
		list.push({ icon: 'share', label: 'Share', run: () => share(t) });
		return list;
	});

	const kindName = { album: 'Album', playlist: 'Playlist', artist: 'Artist', podcast: 'Podcast' };
</script>

{#if runner}
	<PlayAllButtons bind:this={buttons} {room} songs={runner.songs} total={runner.total} whole={runner.whole} name={runner.name} toAutoplay={runner.toAutoplay} {onToast} row={false} />
{/if}

{#snippet items(big: boolean)}
	{#each entries as entry (entry.label)}
		<button role="menuitem" class="flex w-full items-center {big ? 'gap-5 px-5 py-3.5' : 'gap-3 px-4 py-2'} text-left hover:bg-line active:bg-line" onclick={entry.run}>
			<Icon name={entry.icon} size={big ? 24 : 20} class="shrink-0" />
			<span class="min-w-0 break-words">{entry.label}</span>
		</button>
	{/each}
{/snippet}

{#if open}
	{@const t = open.target}
	{#if phone.current}
		<div class="fixed inset-0 z-[55] bg-black/60" role="presentation" transition:fade|global={{ duration: motionMs(150) }} onclick={close}></div>
		<div
			class="fixed inset-x-0 bottom-0 z-[56] flex max-h-screen-85 flex-col rounded-t-2xl bg-raised pb-[env(safe-area-inset-bottom)] shadow-2xl"
			role="menu"
			tabindex="-1"
			aria-label="{kindName[t.kind]} {t.title}"
			use:modal={close}
			transition:fly|global={{ y: 500, duration: motionMs(200), opacity: 1 }}
		>
			<span class="mx-auto mt-2 mb-1 h-1 w-10 shrink-0 rounded-full bg-white/30"></span>
			<div class="flex shrink-0 items-center gap-3 border-b border-line px-5 pt-1 pb-3">
				<div class="h-12 w-12 shrink-0 overflow-hidden bg-line {t.kind === 'artist' ? 'rounded-full' : 'rounded'}">
					{#if t.art}<img src={t.art} alt="" referrerpolicy="no-referrer" class="h-full w-full object-cover" />{/if}
				</div>
				<div class="min-w-0">
					<p class="truncate font-medium">{t.title}</p>
					<p class="truncate text-sm text-muted">{t.subtitle || kindName[t.kind]}</p>
				</div>
			</div>
			<div class="min-h-0 flex-1 overflow-y-auto py-2">{@render items(true)}</div>
		</div>
	{:else}
		<!-- Click anywhere else (or right-click) to close. -->
		<div class="fixed inset-0 z-[55]" role="presentation" onclick={close} oncontextmenu={(e) => (e.preventDefault(), close())}></div>
		<div
			use:keepInView={{ top: open.y, bottom: open.y, left: open.x, right: open.x }}
			class="fixed z-[56] w-64 rounded-lg bg-raised py-1 shadow-2xl ring-1 ring-line"
			style:left="{open.x}px"
			style:top="{open.y}px"
			role="menu"
			aria-label="{kindName[t.kind]} {t.title}"
			use:modal={close}
		>
			{@render items(false)}
		</div>
	{/if}
{/if}

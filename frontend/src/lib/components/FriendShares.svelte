<script lang="ts">
	// Home's "From your friends" (docs/features/friends.md): the songs friends shared with you,
	// newest first, each with who shared it (and in which group) and their note. Tap and ⋮ like
	// Home's other songs; × takes it off your Home.
	import { ACTION_NAMES, gestures } from '../gestures.svelte';
	import { friends, sharedBy } from '../friends.svelte';
	import { artistNames } from '../format';
	import { menuAt, tapSong } from '../songActions';
	import type { ReceivedShare } from '../protocol.gen';
	import type { RoomConnection } from '../room.svelte';
	import Art from './Art.svelte';
	import Icon from './Icon.svelte';
	import PageDots from './PageDots.svelte';
	import type { MenuTarget } from './SongMenu.svelte';

	let { room, shares, list, onToast, onMenu }: { room: RoomConnection; shares: ReceivedShare[]; list: boolean; onToast: (text: string) => void; onMenu: (target: MenuTarget) => void } = $props();

	let scroller = $state<HTMLElement>();

	function menu(event: MouseEvent, share: ReceivedShare, atPointer: boolean) {
		event.preventDefault();
		event.stopPropagation();
		onMenu({ song: share.song, place: 'search', ...menuAt(event.currentTarget as HTMLElement, event, atPointer) });
	}

	async function putAway(share: ReceivedShare) {
		try {
			await friends.dismiss(share.id);
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't remove it");
		}
	}

	function ago(at: number): string {
		const minutes = Math.max(0, Math.round((Date.now() - at) / 60_000));
		if (minutes < 60) return minutes <= 1 ? 'just now' : `${minutes} min ago`;
		const hours = Math.round(minutes / 60);
		if (hours < 24) return `${hours} h ago`;
		const days = Math.round(hours / 24);
		return days === 1 ? 'yesterday' : `${days} days ago`;
	}
</script>

<div
	bind:this={scroller}
	class={list
		? 'flex flex-col'
		: `grid snap-x grid-flow-col gap-x-2 overflow-x-auto [scrollbar-width:none] ${shares.length > 4 ? 'auto-cols-[88%] sm:auto-cols-[24rem]' : 'auto-cols-[100%] sm:auto-cols-[32rem]'}`}
	style:grid-template-rows={list ? undefined : `repeat(${Math.min(4, shares.length)}, auto)`}
>
	{#each shares as share (share.id)}
		{@const song = share.song}
		<div class="flex min-w-0 snap-start items-center gap-1 rounded-md hover:bg-raised" role="listitem" oncontextmenu={(e) => menu(e, share, true)}>
			<button class="flex min-w-0 flex-1 items-center gap-3 px-2 py-1.5 text-left" title={ACTION_NAMES[gestures.list.tap]} onclick={(e) => tapSong(room, song, onToast, () => menu(e, share, false))}>
				<Art {song} size={96} class="h-12 w-12" />
				<div class="min-w-0 flex-1">
					<div class="truncate font-medium">{song.title}</div>
					<div class="truncate text-sm text-muted">{artistNames(song)}</div>
					<div class="truncate text-xs text-muted">
						<span class="font-medium text-white/80">{sharedBy(share)}</span> · {ago(share.at)}{#if share.note}: “{share.note}”{/if}
					</div>
				</div>
			</button>
			<button class="shrink-0 rounded-full p-2 text-muted hover:bg-line hover:text-white" aria-label="More for {song.title}" onclick={(e) => menu(e, share, false)}>
				<Icon name="more" size={20} />
			</button>
			<button class="shrink-0 rounded-full p-2 text-muted hover:bg-line hover:text-white" aria-label="Remove {song.title} from Home" title="Remove from Home" onclick={() => putAway(share)}>
				<Icon name="close" size={18} />
			</button>
		</div>
	{/each}
</div>
{#if !list}<PageDots target={scroller} />{/if}

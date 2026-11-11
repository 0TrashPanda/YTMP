<script lang="ts" module>
	export interface RoomChoice {
		key: string;
		name: string;
		/** Where it is: "On this phone", "Nearby", … */
		where: string;
		open: () => void;
	}
</script>

<script lang="ts">
	// "Play it in": a song shared with you while no room is open (SharedSong.svelte) plays in a
	// new solo room, or in one of your rooms or a room nearby.
	import { modal } from '../modal';
	import Icon from './Icon.svelte';

	let { title, rooms, onNew, onClose }: { title: string; rooms: RoomChoice[]; onNew: () => void; onClose: () => void } = $props();
</script>

<div class="fixed inset-0 z-[60] flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={onClose}>
	<!-- Keys: use:modal (Escape, Tab). The click only keeps it from reaching the backdrop. -->
	<!-- svelte-ignore a11y_click_events_have_key_events -->
	<div
		class="flex max-h-screen-80 w-full max-w-sm flex-col rounded-t-2xl bg-surface pb-[env(safe-area-inset-bottom)] ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label={title}
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		use:modal={() => onClose()}
	>
		<div class="flex items-center justify-between border-b border-line p-4">
			<h2 class="font-bold">{title}</h2>
			<button class="rounded-full p-1 text-muted hover:bg-raised" aria-label="Close" onclick={onClose}><Icon name="close" /></button>
		</div>
		<div class="min-h-0 flex-1 overflow-y-auto py-2">
			<button class="flex w-full items-center gap-3 px-4 py-2.5 text-left hover:bg-raised" onclick={onNew}>
				<span class="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-raised"><Icon name="add" size={22} /></span>
				<span class="font-medium">New solo room</span>
			</button>
			{#each rooms as room (room.key)}
				<button class="flex w-full items-center gap-3 px-4 py-2.5 text-left hover:bg-raised" onclick={room.open}>
					<span class="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-raised"><Icon name="queue" size={22} /></span>
					<span class="min-w-0 flex-1">
						<span class="block truncate">{room.name}</span>
						<span class="block truncate text-xs text-muted">{room.where}</span>
					</span>
				</button>
			{/each}
		</div>
	</div>
</div>

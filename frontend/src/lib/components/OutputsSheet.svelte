<script lang="ts">
	import type { RoomConnection } from '../room.svelte';
	import Icon from './Icon.svelte';

	let { room, onClose, onToast }: { room: RoomConnection; onClose: () => void; onToast: (text: string) => void } = $props();

	const outputs = $derived(room.state?.outputs ?? []);

	async function toggle(outputId: string, active: boolean) {
		const error = await room.run({ kind: 'SetOutput', outputId, active });
		if (error) onToast(error.message);
	}

	function setVolume(outputId: string, volume: number) {
		room.run({ kind: 'SetOutputVolume', outputId, volume });
	}
</script>

<div class="fixed inset-0 z-40 flex items-end justify-center bg-black/60 sm:items-center" role="presentation" onclick={onClose}>
	<div
		class="flex w-full max-w-sm flex-col gap-3 rounded-t-2xl bg-surface p-6 ring-1 ring-line sm:rounded-2xl"
		role="dialog"
		aria-label="Speakers and TVs"
		tabindex="-1"
		onclick={(e) => e.stopPropagation()}
		onkeydown={(e) => e.key === 'Escape' && onClose()}
	>
		<div class="flex items-center justify-between">
			<h2 class="text-lg font-bold">Play on</h2>
			<button class="rounded-full p-1 text-muted hover:bg-raised" aria-label="Close" onclick={onClose}><Icon name="close" /></button>
		</div>

		{#each outputs as output (output.id)}
			<div class="flex flex-col gap-2 rounded-xl bg-raised p-3">
				<label class="flex items-center gap-3">
					<Icon name="cast" size={20} class={output.active ? 'text-accent' : 'text-muted'} />
					<span class="min-w-0 flex-1 truncate">{output.name}</span>
					<input
						type="checkbox"
						class="h-5 w-5 accent-accent"
						checked={output.active}
						disabled={!room.can('change_outputs')}
						onchange={(e) => toggle(output.id, e.currentTarget.checked)}
					/>
				</label>
				{#if output.active && output.volume !== null}
					<input
						type="range"
						class="w-full accent-white"
						min="0"
						max="1"
						step="0.02"
						value={output.volume}
						disabled={!room.can('output_volume')}
						aria-label="Volume of {output.name}"
						onchange={(e) => setVolume(output.id, Number(e.currentTarget.value))}
					/>
				{/if}
			</div>
		{:else}
			<p class="text-sm text-muted">
				No speakers or TVs found. Chromecasts (and speakers with Chromecast built in) on the host's network show up here.
			</p>
		{/each}
		<p class="text-xs text-muted">The room plays on these for everyone, in addition to whoever has "Play here" on.</p>
	</div>
</div>

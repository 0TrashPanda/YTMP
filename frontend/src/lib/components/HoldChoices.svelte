<script lang="ts">
	// The choices of a held player button (Shuffle, Next): a small sheet from the bottom on a
	// phone, a box in the middle on wider screens. Picking one closes it.
	import { fade, fly } from 'svelte/transition';
	import Icon, { type IconName } from './Icon.svelte';

	let { title, choices, onClose }: { title: string; choices: { icon: IconName; label: string; run: () => void }[]; onClose: () => void } = $props();
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && onClose()} />

<div class="fixed inset-0 z-[70] flex items-end justify-center bg-black/60 sm:items-center" role="presentation" transition:fade|global={{ duration: 150 }} onclick={onClose}>
	<div
		class="w-full max-w-sm rounded-t-2xl bg-raised pt-3 pb-[max(env(safe-area-inset-bottom),0.5rem)] shadow-2xl sm:rounded-2xl"
		role="menu"
		tabindex="-1"
		aria-label={title}
		transition:fly|global={{ y: 300, duration: 200, opacity: 1 }}
		onclick={(e) => e.stopPropagation()}
		onkeydown={() => {}}
	>
		<p class="px-5 pb-2 text-sm text-muted">{title}</p>
		{#each choices as c, i (i)}
			<button role="menuitem" class="flex w-full items-center gap-5 px-5 py-3.5 text-left hover:bg-line active:bg-line" onclick={() => (onClose(), c.run())}>
				<Icon name={c.icon} size={24} class="shrink-0" />
				<span class="truncate">{c.label}</span>
			</button>
		{/each}
	</div>
</div>

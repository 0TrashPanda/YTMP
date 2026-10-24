<script lang="ts" module>
	import { getHost } from '../api';
	import type { HostKind } from '../protocol.gen';

	/** The host's kind, asked once: its stream is "Server" or "Host phone". */
	let hostKind = $state<HostKind | null>(null);
	let asked = false;
</script>

<script lang="ts">
	// Where the sound playing on this device comes from, small next to the song: YouTube itself,
	// the host streaming it (its saved songs and music files, or YouTube's link through it), or
	// this phone (saved on it, or its own music when it hosts). See docs/features/ui.md.
	import type { AudioSource } from '../player.svelte';
	import Icon from './Icon.svelte';

	let { source, class: className = '' }: { source: AudioSource | null; class?: string } = $props();

	if (!asked) {
		asked = true;
		getHost()
			.then((h) => (hostKind = h.kind))
			.catch(() => {});
	}

	const shown = $derived.by(() => {
		if (source === 'youtube') return { icon: 'play', label: 'YouTube', title: 'Streams from YouTube' } as const;
		if (source === 'device') return { icon: 'phone', label: 'This phone', title: 'Plays from this phone: saved on it, or its own music' } as const;
		if (source === 'host') {
			const label = hostKind === 'phone' ? 'Host phone' : 'Server';
			return { icon: 'server', label, title: `Streams from the ${hostKind === 'phone' ? "host's phone" : 'server'}: saved there, or YouTube through it` } as const;
		}
		return null;
	});
</script>

{#if shown}
	<span class="inline-flex shrink-0 items-center gap-1 rounded-full bg-white/10 px-1.5 py-0.5 text-[10px] leading-none text-muted {className}" title={shown.title}>
		<Icon name={shown.icon} size={11} />
		{shown.label}
	</span>
{/if}

<script lang="ts">
	// Keep offline on a playlist's page (the phone's own pages): its songs come into the phone's
	// song cache, stay there and keep up with the playlist, like YouTube Music's download button.
	import { findOffline, keepLabel, setKept, watchOffline } from '../offlinePlaylists.svelte';
	import type { Thumbnail } from '../protocol.gen';
	import Icon from './Icon.svelte';

	let { id, title, thumbnails, onToast }: { id: string; title: string | null; thumbnails: Thumbnail[]; onToast: (text: string) => void } = $props();

	$effect(() => watchOffline());

	const info = $derived(findOffline(id));
	const kept = $derived(!!info?.kept);
	const done = $derived(!!info && info.read && !info.tooLong && info.here >= info.songs);
	let busy = $state(false);

	async function toggle() {
		if (!title) return;
		if (kept && !confirm(`Stop keeping "${title}" offline? Its songs stay in the cache until room is needed.`)) return;
		busy = true;
		try {
			onToast(await setKept(id, !kept, title, thumbnails));
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't change it");
		} finally {
			busy = false;
		}
	}
</script>

<button
	class="flex items-center gap-2 rounded-full px-4 py-2 text-sm hover:bg-line disabled:opacity-40 {kept ? 'bg-white font-medium text-black hover:bg-white/90' : 'bg-raised'}"
	disabled={!title || busy}
	aria-pressed={kept}
	title={kept ? 'Kept offline on this phone: tap to stop' : 'Keep this playlist on this phone, to play without internet'}
	onclick={toggle}
>
	<Icon name={kept && done ? 'check' : 'download'} size={18} />
	{keepLabel(info)}
</button>

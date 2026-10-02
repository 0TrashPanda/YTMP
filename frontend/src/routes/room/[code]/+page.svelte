<script lang="ts">
	import { page } from '$app/state';
	import { onDestroy, untrack } from 'svelte';
	import Art from '../../../lib/components/Art.svelte';
	import Icon from '../../../lib/components/Icon.svelte';
	import PlayerBar from '../../../lib/components/PlayerBar.svelte';
	import QueuePanel from '../../../lib/components/QueuePanel.svelte';
	import SearchResults from '../../../lib/components/SearchResults.svelte';
	import { artistNames } from '../../../lib/format';
	import { createPlayer, type RoomPlayer } from '../../../lib/player.svelte';
	import { RoomConnection } from '../../../lib/room.svelte';
	import { saved } from '../../../lib/storage';
	import type { Participant } from '../../../lib/protocol.gen';

	const code = page.params.code!.toUpperCase();

	let name = $state(saved.displayName);
	let room = $state<RoomConnection | null>(null);
	let player = $state<RoomPlayer | null>(null);
	let query = $state('');
	let toasts = $state<{ id: number; text: string }[]>([]);
	let now = $state(Date.now());
	let toastId = 0;

	function start() {
		saved.displayName = name.trim();
		room = new RoomConnection(code, name.trim());
		player = createPlayer(room);
		room.connect();
	}
	if (saved.displayName.trim()) start();

	// Tick for the progress bar.
	const clock = setInterval(() => (now = Date.now()), 250);
	onDestroy(() => {
		clearInterval(clock);
		player?.destroy();
		room?.close();
	});

	// Follow the room: whenever the song or playback changes, re-sync the audio.
	$effect(() => {
		const s = room?.state;
		void [s?.nowPlaying?.item.itemId, s?.nowPlaying?.streamUrl, s?.playback.playing, s?.playback.positionMs, s?.playback.hostTimeMs];
		void room?.clockOffset;
		untrack(() => player?.sync());
	});

	// In the app, "Play here" stays on between visits (a browser needs a click first).
	$effect(() => {
		if (room?.status === 'connected' && player?.canStartWithoutGesture && saved.listening) {
			untrack(() => !player!.enabled && player!.enable());
		}
	});

	const positionMs = $derived.by(() => {
		const playback = room?.state?.playback;
		if (!room || !playback) return 0;
		void now;
		return playback.playing ? playback.positionMs + (room.hostNow() - playback.hostTimeMs) : playback.positionMs;
	});

	function toast(text: string) {
		// Negative, so they never clash with the host's notice IDs.
		const id = -++toastId;
		toasts.push({ id, text });
		setTimeout(() => (toasts = toasts.filter((t) => t.id !== id)), 3000);
	}

	async function copyCode() {
		try {
			await navigator.clipboard.writeText(code);
			toast('Room code copied');
		} catch {
			toast(`Room code: ${code}`);
		}
	}
</script>

<svelte:head>
	<title>{room?.state ? `${room.state.room.name} · YTMP` : 'YTMP'}</title>
</svelte:head>

{#if !room}
	<main class="mx-auto flex min-h-full max-w-sm flex-col justify-center gap-4 px-4">
		<h1 class="text-2xl font-bold">Join room <span class="font-mono">{code}</span></h1>
		<form
			class="flex flex-col gap-3"
			onsubmit={(e) => {
				e.preventDefault();
				if (name.trim()) start();
			}}
		>
			<input
				class="rounded-lg bg-raised px-4 py-3 outline-none ring-white/40 focus:ring-2"
				bind:value={name}
				maxlength="32"
				placeholder="Your name"
				autocomplete="nickname"
			/>
			<button class="rounded-full bg-white py-3 font-medium text-black disabled:opacity-40" disabled={!name.trim()}>
				Join
			</button>
		</form>
	</main>
{:else if room.status === 'rejected'}
	<main class="mx-auto flex min-h-full max-w-sm flex-col items-center justify-center gap-4 px-4 text-center">
		<p class="text-lg">{room.rejectMessage}</p>
		<a href="/" class="rounded-full bg-white px-6 py-3 font-medium text-black">Back</a>
	</main>
{:else}
	<div class="flex h-full flex-col">
		<header class="flex items-center gap-3 border-b border-line px-3 py-2 sm:px-4">
			<a href="/" class="text-xl font-black tracking-tight">YT<span class="text-accent">MP</span></a>
			<label class="flex min-w-0 flex-1 items-center gap-2 rounded-lg bg-raised px-3 py-2 sm:max-w-xl">
				<Icon name="search" size={20} class="shrink-0 text-muted" />
				<input class="min-w-0 flex-1 bg-transparent outline-none" placeholder="Search songs" bind:value={query} />
				{#if query}
					<button aria-label="Clear search" onclick={() => (query = '')}><Icon name="close" size={20} /></button>
				{/if}
			</label>
			<div class="ml-auto flex items-center gap-2">
				<span class="hidden truncate text-sm font-medium md:inline">{room.state?.room.name}</span>
				<button
					class="flex items-center gap-2 rounded-full bg-raised px-3 py-1.5 font-mono tracking-widest hover:bg-line"
					onclick={copyCode}
					title="Copy room code"
				>
					{code}
					<Icon name="copy" size={16} class="text-muted" />
				</button>
				<span
					class="hidden items-center gap-1 text-sm text-muted sm:flex"
					title={room.state?.participants.filter((p: Participant) => p.online).map((p: Participant) => p.name + (p.listening ? ' 🎧' : '')).join(', ')}
				>
					<Icon name="people" size={20} />
					{room.state?.participants.filter((p: Participant) => p.online).length ?? 0}
				</span>
			</div>
		</header>

		{#if room.status !== 'connected'}
			<div class="bg-raised px-4 py-1 text-center text-sm text-muted">
				{room.status === 'connecting' ? 'Connecting…' : 'Connection lost, reconnecting…'}
			</div>
		{/if}

		<div class="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)] grid-rows-[minmax(0,1fr)_minmax(0,40%)] lg:grid-cols-[minmax(0,1fr)_420px] lg:grid-rows-[minmax(0,1fr)]">
			<main class="min-h-0 overflow-y-auto p-3 sm:p-6">
				{#if query.trim()}
					<SearchResults {room} {query} onToast={toast} />
				{:else if room.state?.nowPlaying}
					{@const song = room.state.nowPlaying.item.song}
					<div class="flex h-full min-w-0 flex-col items-center justify-center gap-4 text-center sm:gap-6">
						<Art {song} size={544} class="aspect-square w-[min(100%,28rem,38vh)] shadow-2xl" />
						<div class="w-full min-w-0">
							<h1 class="text-xl font-bold break-words sm:text-2xl">{song.title}</h1>
							<p class="text-muted">{artistNames(song)}</p>
							<p class="mt-1 text-sm text-muted">Added by {room.state.nowPlaying.item.addedByName}</p>
						</div>
					</div>
				{:else}
					<div class="flex h-full flex-col items-center justify-center gap-2 text-center text-muted">
						<Icon name="search" size={48} />
						<p>Search for a song to start the music.</p>
					</div>
				{/if}
			</main>
			<div class="min-h-0 border-t border-line lg:border-t-0 lg:border-l">
				<QueuePanel {room} />
			</div>
		</div>

		{#if player}
			<PlayerBar {room} {player} {positionMs} />
		{/if}
	</div>

	<div class="pointer-events-none fixed inset-x-0 bottom-28 z-30 flex flex-col items-center gap-2 px-4">
		{#each [...room.notices, ...toasts] as message (message.id + message.text)}
			<button
				class="pointer-events-auto rounded-lg bg-white px-4 py-2 text-sm text-black shadow-lg"
				onclick={() => room?.dismissNotice(message.id)}
			>
				{message.text}
			</button>
		{/each}
	</div>
{/if}

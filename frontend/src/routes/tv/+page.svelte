<script lang="ts">
	// A TV (or any screen) as a party screen, like YouTube's "Link with TV code": it shows a
	// code, someone in a room enters it on their phone, and the room shows here. It remembers
	// the room, so reloading (or turning the TV on again) goes straight back to it.
	import { onDestroy } from 'svelte';
	import { ApiRequestError, getHost, reportProblem, startScreenPairing, waitForScreenLink } from '../../lib/api';
	import { createPlayer, type RoomPlayer } from '../../lib/player.svelte';
	import Icon from '../../lib/components/Icon.svelte';
	import PartyScreen from '../../lib/components/PartyScreen.svelte';
	import type { HostInfo, ScreenPairing } from '../../lib/protocol.gen';
	import { RoomConnection } from '../../lib/room.svelte';
	import { saved } from '../../lib/storage';

	const KEY = 'ytmp.screenRoom';
	let host = $state<HostInfo | null>(null);
	let pairing = $state<ScreenPairing | null>(null);
	let problem = $state<string | null>(null);
	let room = $state<RoomConnection | null>(null);
	let stopped = false;

	/**
	 * On a Chromecast (opened by the host with DashCast, `#cast=CODE.TOKEN`): already linked,
	 * and it plays the room's music too, since DashCast takes the place of the TV's music player.
	 */
	const cast = /^#cast=([^.]+)\.(.+)$/.exec(location.hash);
	let player = $state<RoomPlayer | null>(null);

	getHost()
		.then((h) => (host = h))
		.catch(() => {});

	function remembered(): string | null {
		try {
			return localStorage.getItem(KEY);
		} catch {
			return null;
		}
	}
	function remember(code: string | null) {
		try {
			if (code) localStorage.setItem(KEY, code);
			else localStorage.removeItem(KEY);
		} catch {
			// Linked again next time.
		}
	}

	function show(code: string) {
		room?.close();
		player?.destroy();
		room = new RoomConnection(code, 'Party screen');
		if (cast) {
			room.guestToken = decodeURIComponent(cast[2]);
			player = createPlayer(room);
		}
		room.connect();
	}

	// On a Chromecast: play as soon as we're in, at full volume (the TV's own volume is the room's slider).
	let started = false;
	$effect(() => {
		if (!player || started || room?.status !== 'connected' || !room.state || !room.can('listen_locally')) return;
		started = true;
		player.setVolume(1);
		player.enable().catch(() => {});
	});
	// Follow the room, like the room page does.
	$effect(() => {
		const s = room?.state;
		void [s?.nowPlaying?.item.itemId, s?.nowPlaying?.streamUrl, s?.playback.playing, s?.playback.positionMs, s?.playback.hostTimeMs, room?.clockOffset];
		player?.sync();
	});
	$effect(() => {
		if (player?.error && room) reportProblem(`party screen on a Chromecast: ${player.error}`, room.roomCode);
	});

	// Turned away (removed from the room, the room is gone): a new code. (On a Chromecast the
	// host closes the page itself.)
	$effect(() => {
		if (room?.status !== 'rejected' || cast) return;
		room.close();
		room = null;
		remember(null);
		pair();
	});

	const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

	async function pair() {
		while (!stopped) {
			try {
				pairing ??= await startScreenPairing();
				problem = null;
				const linked = await waitForScreenLink(pairing.secret);
				if (!linked) continue; // Nobody yet: wait again.
				saved.setGuestToken(linked.roomCode, linked.guestToken);
				remember(linked.roomCode);
				pairing = null;
				show(linked.roomCode);
				return;
			} catch (e) {
				if (e instanceof ApiRequestError && pairing) {
					pairing = null; // Expired: a new code.
				} else {
					problem = e instanceof Error ? e.message : "Can't reach the server";
					await sleep(3000);
				}
			}
		}
	}

	if (cast) {
		const [, castCode, token] = cast;
		// What this TV's browser is and allows, in the host's log: tells why sound or the page
		// doesn't work there. Whether it may start sound by itself: try a moment of silence.
		const silence = new Audio('data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAIA+AAACABAAZGF0YQAAAAA=');
		silence.play().then(
			() => 'allowed',
			(e: unknown) => (e instanceof DOMException ? e.name : String(e))
		).then((autoplay) =>
			reportProblem(
				`party screen on a Chromecast opened: sound by itself ${autoplay}, ${top === self ? 'whole page' : 'in a frame'}, ${navigator.userAgent}`,
				decodeURIComponent(castCode)
			)
		);
		saved.setGuestToken(decodeURIComponent(castCode), decodeURIComponent(token));
		show(decodeURIComponent(castCode));
	} else {
		const code = remembered();
		if (code && saved.guestToken(code)) show(code);
		else pair();
	}

	onDestroy(() => {
		stopped = true;
		player?.destroy();
		room?.close();
	});
</script>

<svelte:head><title>YTMP party screen</title></svelte:head>

{#if room}
	<PartyScreen {room} {host} {cast} />
{:else}
	<main class="flex min-h-screen flex-col items-center justify-center gap-[4vh] bg-bg px-6 text-center">
		<div class="flex items-center gap-3 text-[max(18px,3vh)] text-muted">
			<Icon name="tv" size={32} />
			<span>YT<span class="text-accent">MP</span> party screen</span>
		</div>
		{#if pairing}
			<p class="text-[max(16px,2.6vh)] text-muted">In the room on your phone: tap your picture, then <b class="text-white">Show on a TV</b>, and enter</p>
			<p class="font-mono text-[min(16vh,14vw)] leading-none font-bold tracking-[0.12em]">{pairing.code.slice(0, 3)} {pairing.code.slice(3)}</p>
			<p class="text-[max(14px,2vh)] text-muted">This screen then shows what plays, what's next, and how to join.</p>
		{:else if problem}
			<p class="text-[max(16px,2.6vh)] text-muted">{problem}. Trying again…</p>
		{:else}
			<p class="text-[max(16px,2.6vh)] text-muted">Getting a code…</p>
		{/if}
	</main>
{/if}

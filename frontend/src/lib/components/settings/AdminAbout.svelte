<script lang="ts">
	// Server admin → About: versions (this server, the newest release, yt-dlp), and how many
	// accounts and rooms there are.
	import { authServer } from '../../account';
	import type { ServerAbout } from '../../protocol.gen';

	let { onToast }: { onToast: (text: string) => void } = $props();

	let about = $state<ServerAbout | null>(null);
	authServer
		.about()
		.then((a) => (about = a))
		.catch((e) => onToast(e instanceof Error ? e.message : "Couldn't load the versions"));

	/** "1.10.0" is newer than "1.9.2". */
	function newer(latest: string, current: string): boolean {
		const a = latest.split('.').map((n) => parseInt(n) || 0);
		const b = current.split('.').map((n) => parseInt(n) || 0);
		for (let i = 0; i < Math.max(a.length, b.length); i++) if ((a[i] ?? 0) !== (b[i] ?? 0)) return (a[i] ?? 0) > (b[i] ?? 0);
		return false;
	}

	const card = 'flex flex-col gap-3 rounded-xl bg-raised/60 p-4';
</script>

{#if about}
	<section class={card}>
		<div>
			<p class="text-sm font-medium">YTMP</p>
			<p class="text-xs text-muted">
				{#if about.version}
					Version {about.version}
					{#if about.latestVersion && newer(about.latestVersion, about.version)}
						· <span class="text-white">{about.latestVersion} is out</span>. The server updates itself at night, or right away with
						<span class="font-mono">docker compose pull && docker compose up -d</span>.
					{:else if about.latestVersion}
						· up to date
					{/if}
				{:else}
					A build of its own (not a release){about.latestVersion ? ` · newest release: ${about.latestVersion}` : ''}
				{/if}
			</p>
		</div>
		<div>
			<p class="text-sm font-medium">YouTube Music module</p>
			<p class="text-xs text-muted">
				{#if about.moduleOnline}
					yt-dlp {about.ytDlp ?? '?'} · ytmusicapi {about.ytmusicapi ?? '?'}. They're updated by themselves when a new one is out.
				{:else}
					Can't be reached: search and playback from YouTube Music don't work. Check that it runs (<span class="font-mono">docker compose logs ytm</span>).
				{/if}
			</p>
		</div>
		<div>
			<p class="text-sm font-medium">In use</p>
			<p class="text-xs text-muted">{about.accounts} {about.accounts === 1 ? 'account' : 'accounts'} · {about.rooms} {about.rooms === 1 ? 'room' : 'rooms'}</p>
		</div>
	</section>
{/if}

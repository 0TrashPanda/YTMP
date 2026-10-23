<script lang="ts">
	// Server admin → About: versions (this server, the newest release, yt-dlp), and how many
	// accounts and rooms there are.
	import { authServer } from '../../account';
	import type { BackupInfo, ServerAbout } from '../../protocol.gen';

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

	let busy = $state(false);
	let uploaded = $state<BackupInfo | null>(null);

	async function download() {
		busy = true;
		try {
			await authServer.downloadBackup();
			onToast('Backup downloaded. It holds passwords (hashed) and listening history: keep it safe.');
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't make the backup");
		} finally {
			busy = false;
		}
	}

	async function restore(event: Event) {
		const input = event.currentTarget as HTMLInputElement;
		const file = input.files?.[0];
		input.value = '';
		if (!file) return;
		if (!confirm(`Replace everything on this server (accounts, rooms, history, settings) with the backup "${file.name}"? It takes effect when the server restarts; the current database is kept next to it.`)) return;
		busy = true;
		try {
			uploaded = await authServer.uploadBackup(file);
			about = (await authServer.about().catch(() => null)) ?? about;
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't use that backup");
		} finally {
			busy = false;
		}
	}

	const card = 'flex flex-col gap-3 rounded-xl bg-raised/60 p-4';
	const small = 'rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-40';
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
	<section class={card}>
		<div>
			<p class="text-sm font-medium">Backup</p>
			<p class="text-xs text-muted">
				{#if about.backupSupported}
					A copy of the whole database: accounts (passwords hashed), rooms, listening history and settings. Not the song cache or the logs. To move to a new server or go back, restore it here; it replaces everything when the server restarts.
				{:else}
					This server uses PostgreSQL: back it up with <span class="font-mono">pg_dump</span>.
				{/if}
			</p>
		</div>
		{#if about.backupSupported}
			<div class="flex flex-wrap gap-2">
				<button class={small} disabled={busy} onclick={download}>Download a backup</button>
				<label class="{small} cursor-pointer {busy ? 'pointer-events-none opacity-40' : ''}">
					Restore from a backup…
					<input class="hidden" type="file" accept=".db,application/octet-stream" onchange={restore} />
				</label>
			</div>
			{#if uploaded || about.restorePending}
				<p class="text-xs">
					{uploaded ? `The backup (${uploaded.accounts} accounts, ${uploaded.rooms} rooms) is ready.` : 'A backup is ready.'}
					Restart the server to use it, for example <span class="font-mono">docker compose restart ytmp</span>. The current database is kept next to it as <span class="font-mono">ytmp.db.before-restore</span>.
				</p>
			{/if}
		{/if}
	</section>
{/if}

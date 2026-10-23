<script lang="ts">
	// Server admin → Log: what the server's admins did lately (newest first).
	import { authServer } from '../../account';
	import type { AuditEntry } from '../../protocol.gen';

	let { onToast }: { onToast: (text: string) => void } = $props();

	let entries = $state<AuditEntry[] | null>(null);
	authServer
		.auditLog()
		.then((list) => (entries = list))
		.catch((e) => onToast(e instanceof Error ? e.message : "Couldn't load the log"));

	const when = (ms: number) => new Date(ms).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
</script>

<section class="flex flex-col gap-2">
	<p class="text-xs text-muted">What server admins did: accounts, invite links, settings, rooms and backups. The last 1000 are kept.</p>
	{#if entries == null}
		<p class="text-sm text-muted">Loading…</p>
	{:else if entries.length === 0}
		<p class="text-sm text-muted">Nothing yet.</p>
	{:else}
		<ul class="flex flex-col gap-1">
			{#each entries as entry (entry.at + entry.action + entry.target)}
				<li class="rounded-lg bg-raised/60 px-3 py-2 text-sm">
					<span class="font-medium">{entry.actor}</span>
					{entry.action.charAt(0).toLowerCase() + entry.action.slice(1)}
					{#if entry.target}<span class="font-medium">{entry.target}</span>{/if}
					{#if entry.detail}<span class="text-muted"> · {entry.detail}</span>{/if}
					<span class="block text-xs text-muted">{when(entry.at)}</span>
				</li>
			{/each}
		</ul>
	{/if}
</section>

<script lang="ts">
	// Per-person overrides, like Discord's channel permission overrides: ✗ deny, / follow the role, ✓ allow.
	import { PERMISSION_GROUPS, type Override } from '../../permissions';
	import type { Participant, Permission, Role } from '../../protocol.gen';
	import SaveBar from './SaveBar.svelte';
	import TriState from './TriState.svelte';

	interface Props {
		person: Participant;
		role: Role | undefined;
		canGrant: (permission: Permission) => boolean;
		onSave: (allow: Permission[], deny: Permission[]) => Promise<boolean>;
		onClose: () => void;
	}
	let { person, role, canGrant, onSave, onClose }: Props = $props();

	// Edited copies; the saved values stay in `person`.
	// svelte-ignore state_referenced_locally
	let allow = $state<Permission[]>([...person.allow]);
	// svelte-ignore state_referenced_locally
	let deny = $state<Permission[]>([...person.deny]);
	let busy = $state(false);
	let shake = $state(false);

	const dirty = $derived([...allow].sort().join() !== [...person.allow].sort().join() || [...deny].sort().join() !== [...person.deny].sort().join());

	function value(p: Permission): Override {
		return allow.includes(p) ? 'allow' : deny.includes(p) ? 'deny' : 'inherit';
	}

	function set(p: Permission, v: Override) {
		allow = allow.filter((x) => x !== p);
		deny = deny.filter((x) => x !== p);
		if (v === 'allow') allow = [...allow, p];
		if (v === 'deny') deny = [...deny, p];
	}

	async function save() {
		busy = true;
		const ok = await onSave(allow, deny);
		busy = false;
		if (ok) onClose();
		else {
			shake = true;
			setTimeout(() => (shake = false), 600);
		}
	}

	function close() {
		if (!dirty) return onClose();
		shake = true;
		setTimeout(() => (shake = false), 600);
	}
</script>

<div class="fixed inset-0 z-[70] flex items-center justify-center bg-black/70 p-4" role="presentation" onclick={(e) => e.target === e.currentTarget && close()}>
	<div class="flex max-h-full w-full max-w-xl flex-col rounded-lg bg-d-bg text-d-text shadow-2xl" role="dialog" aria-modal="true" aria-label="Permissions for {person.name}">
		<div class="flex items-start gap-3 p-4 pb-2">
			<div class="min-w-0 flex-1">
				<h2 class="text-xl font-semibold text-d-head">Permissions for {person.name}</h2>
				<p class="mt-1 text-sm text-d-muted">
					On top of their role
					<span class="font-medium" style:color={role?.color}>{role?.name ?? '—'}</span>.
					✓ allows, ✗ denies, / follows the role.
				</p>
			</div>
			<button class="rounded p-1 text-d-muted hover:text-d-head" aria-label="Close" onclick={close}>
				<svg viewBox="0 0 24 24" class="h-6 w-6"><path fill="currentColor" d="M18.4 4 12 10.4 5.6 4 4 5.6 10.4 12 4 18.4 5.6 20 12 13.6 18.4 20 20 18.4 13.6 12 20 5.6z" /></svg>
			</button>
		</div>
		<div class="min-h-0 flex-1 overflow-y-auto px-4">
			{#each PERMISSION_GROUPS as group (group.name)}
				<h4 class="mt-5 mb-1 text-xs font-bold tracking-wide text-d-muted uppercase">{group.name} Permissions</h4>
				{#each group.permissions as permission (permission.id)}
					{@const fromRole = role?.permissions.includes(permission.id)}
					<div class="flex items-center gap-4 border-b border-d-line py-3">
						<div class="min-w-0 flex-1">
							<p class="font-medium text-d-head">{permission.name}</p>
							<p class="text-xs text-d-faint">Role: {fromRole ? 'allowed' : 'not allowed'}</p>
						</div>
						<TriState
							value={value(permission.id)}
							canAllow={canGrant(permission.id)}
							onchange={(v) => set(permission.id, v)}
						/>
					</div>
				{/each}
			{/each}
		</div>
		<div class="p-4">
			{#if dirty}
				<SaveBar {shake} {busy} onReset={() => ((allow = [...person.allow]), (deny = [...person.deny]))} onSave={save} />
			{:else}
				<div class="flex justify-end"><button class="rounded px-4 py-2 text-sm font-medium text-white hover:underline" onclick={onClose}>Done</button></div>
			{/if}
		</div>
	</div>
</div>

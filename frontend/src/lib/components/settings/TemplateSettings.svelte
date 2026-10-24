<script lang="ts">
	// Your role template: the roles every room you create starts with. Same editor as a
	// room's roles, saved to your account.
	import { onDestroy, onMount } from 'svelte';
	import { authServer } from '../../account';
	import { DEFAULT_ROLE_COLOR } from '../../permissions';
	import type { Role, RoleTemplate } from '../../protocol.gen';
	import RoleEditor from './RoleEditor.svelte';
	import Switch from './Switch.svelte';

	let { onClose }: { onClose: () => void } = $props();

	let template = $state<RoleTemplate | null>(null);
	let error = $state<string | null>(null);
	let section = $state<'roles' | 'defaults'>('roles');
	let showingSection = $state(true);

	onMount(async () => {
		try {
			template = await authServer.roleTemplate();
		} catch (e) {
			error = e instanceof Error ? e.message : "Couldn't load your roles";
		}
	});

	async function save(next: RoleTemplate): Promise<boolean> {
		try {
			template = await authServer.saveRoleTemplate(next);
			error = null;
			return true;
		} catch (e) {
			error = e instanceof Error ? e.message : "Couldn't save";
			return false;
		}
	}

	function newId() {
		return Math.random().toString(36).slice(2, 12);
	}

	function onKey(event: KeyboardEvent) {
		if (event.key === 'Escape') onClose();
	}
	onMount(() => window.addEventListener('keydown', onKey));
	onDestroy(() => window.removeEventListener('keydown', onKey));

	const label = 'mb-2 block text-sm font-medium';
	const input = 'w-full rounded bg-raised px-3 py-2.5 text-white outline-none';
</script>

<div class="fixed inset-0 z-[60] flex bg-bg text-white" role="dialog" aria-modal="true" aria-label="Your roles">
	<nav class="{showingSection ? 'hidden md:flex' : 'flex'} w-full shrink-0 justify-end bg-surface md:w-[min(40%,280px)] lg:w-[calc(50%-370px)] lg:min-w-[218px]">
		<div class="flex w-full flex-col gap-0.5 px-2 py-6 md:w-[218px] md:pt-[60px] md:pr-1.5 md:pl-5">
			<h2 class="px-2.5 pb-1.5 text-sm font-medium">Your room template</h2>
			{#each [['roles', 'Roles'], ['defaults', 'Roles when joining']] as [id, title] (id)}
				<button
					class="rounded px-2.5 py-1.5 text-left text-base font-medium {section === id ? 'bg-line text-white' : 'text-muted hover:bg-raised hover:text-white'}"
					onclick={() => ((section = id as typeof section), (showingSection = true))}
				>
					{title}
				</button>
			{/each}
			<div class="mx-2.5 my-2 h-px bg-line"></div>
			<button class="rounded px-2.5 py-1.5 text-left text-base font-medium text-muted hover:bg-raised hover:text-white" onclick={onClose}>Close</button>
		</div>
	</nav>

	<div class="{showingSection ? 'flex' : 'hidden md:flex'} min-w-0 flex-1 overflow-hidden">
		<div class="flex h-full w-full max-w-[740px] min-w-0 flex-col px-4 pt-4 md:px-10 md:pt-[60px]">
			<div class="mb-2 flex items-center gap-2">
				<button class="rounded p-1 text-muted hover:text-white md:hidden" aria-label="Back" onclick={() => (showingSection = false)}>
					<svg viewBox="0 0 24 24" class="h-5 w-5"><path fill="currentColor" d="M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20z" /></svg>
				</button>
				<h1 class="text-xl font-medium text-white">{section === 'roles' ? 'Roles' : 'Roles when joining'}</h1>
			</div>
			<p class="mb-5 text-sm text-muted">Every room you create starts with these. Changing them doesn't change rooms that already exist.</p>
			{#if error}
				<p class="mb-4 rounded bg-accent/20 px-3 py-2 text-sm text-accent">{error}</p>
			{/if}

			{#if template}
				{@const t = template}
				{#if section === 'roles'}
					<div class="min-h-0 flex-1">
						<RoleEditor
							roles={t.roles}
							defaultRoleIds={[t.settings.defaultGuestRole, t.settings.defaultAccountRole]}
							canManage={() => true}
							canGrant={() => true}
							onSave={(role: Role) => save({ ...t, roles: t.roles.map((r) => (r.id === role.id ? role : r)) })}
							onCreate={() => save({ ...t, roles: [...t.roles, { id: newId(), name: 'new role', color: DEFAULT_ROLE_COLOR, permissions: [] }] })}
							onDelete={(id) => save({ ...t, roles: t.roles.filter((r) => r.id !== id) })}
							onMove={(id, toIndex) => {
								const roles = t.roles.filter((r) => r.id !== id);
								roles.splice(toIndex, 0, t.roles.find((r) => r.id === id)!);
								save({ ...t, roles });
							}}
						/>
					</div>
				{:else}
					<p class="mb-4 text-sm text-muted">The role people get when they join one of your rooms.</p>
					<label class="mb-5 block">
						<span class={label}>Guests</span>
						<select class={input} value={t.settings.defaultGuestRole} onchange={(e) => save({ ...t, settings: { ...t.settings, defaultGuestRole: e.currentTarget.value } })}>
							{#each t.roles as role (role.id)}<option value={role.id}>{role.name}</option>{/each}
						</select>
					</label>
					<label class="mb-5 block">
						<span class={label}>People with an account</span>
						<select class={input} value={t.settings.defaultAccountRole} onchange={(e) => save({ ...t, settings: { ...t.settings, defaultAccountRole: e.currentTarget.value } })}>
							{#each t.roles as role (role.id)}<option value={role.id}>{role.name}</option>{/each}
						</select>
					</label>
					<div class="mt-6 flex items-center gap-4 border-t border-line pt-6">
						<div class="min-w-0 flex-1">
							<h3 class="text-base font-medium text-white">Autoplay</h3>
							<p class="text-sm text-muted">When the queue runs out, keep playing songs like the last one.</p>
						</div>
						<Switch checked={t.settings.autoplay} label="Autoplay" onchange={(on) => save({ ...t, settings: { ...t.settings, autoplay: on } })} />
					</div>
					<div class="mt-6 flex items-center gap-4 border-t border-line pt-6">
						<div class="min-w-0 flex-1">
							<h3 class="text-base font-medium text-white">Fair order</h3>
							<p class="text-sm text-muted">The queue goes round by round, one song per person per round.</p>
						</div>
						<Switch checked={t.settings.fairOrder} label="Fair order" onchange={(on) => save({ ...t, settings: { ...t.settings, fairOrder: on } })} />
					</div>
					<div class="mt-6 flex items-center gap-4 border-t border-line pt-6">
						<div class="min-w-0 flex-1">
							<h3 class="text-base font-medium text-white">Vote to skip</h3>
							<p class="text-sm text-muted">People with <em>Vote to Skip</em> vote instead of skipping right away ({t.settings.voteSkipPercent}% needed).</p>
						</div>
						<Switch checked={t.settings.voteSkip} label="Vote to skip" onchange={(on) => save({ ...t, settings: { ...t.settings, voteSkip: on } })} />
					</div>
					<div class="mt-6 flex items-center gap-4 border-t border-line pt-6">
						<div class="min-w-0 flex-1">
							<h3 class="text-base font-medium text-white">No double songs</h3>
							<p class="text-sm text-muted">A song that is in the queue already can't be added again.</p>
						</div>
						<Switch checked={t.settings.noDuplicates} label="No double songs" onchange={(on) => save({ ...t, settings: { ...t.settings, noDuplicates: on } })} />
					</div>
				{/if}
			{:else if !error}
				<p class="text-muted">Loading…</p>
			{/if}
		</div>
		<div class="hidden shrink-0 pt-[60px] pr-5 md:block">
			<button class="flex flex-col items-center gap-1.5 text-muted hover:text-white" onclick={onClose} aria-label="Close">
				<span class="flex h-9 w-9 items-center justify-center rounded-full border-2 border-current">
					<svg viewBox="0 0 24 24" class="h-[18px] w-[18px]"><path fill="currentColor" d="M18.4 4 12 10.4 5.6 4 4 5.6 10.4 12 4 18.4 5.6 20 12 13.6 18.4 20 20 18.4 13.6 12 20 5.6z" /></svg>
				</span>
				<span class="text-[13px] font-medium">ESC</span>
			</button>
		</div>
	</div>
</div>

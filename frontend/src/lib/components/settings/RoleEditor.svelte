<script lang="ts">
	// Discord's role editor: the ranked role list on the left (drag to reorder), the selected
	// role on the right with Display / Permissions / Manage Members tabs, and the unsaved
	// changes bar. Used for a room's roles and for the role template on /account.
	import { untrack } from 'svelte';
	import { DEFAULT_ROLE_COLOR, PERMISSION_GROUPS, ROLE_COLORS } from '../../permissions';
	import type { Participant, Permission, Role } from '../../protocol.gen';
	import Icon from '../Icon.svelte';
	import SaveBar from './SaveBar.svelte';
	import Switch from './Switch.svelte';

	interface Props {
		roles: Role[];
		/** Roles new people get; they can't be deleted. */
		defaultRoleIds: string[];
		/** Whether the role at this rank can be edited, moved or deleted by me. */
		canManage: (index: number) => boolean;
		/** Whether I may turn this permission on (you can't give what you don't have). */
		canGrant: (permission: Permission) => boolean;
		onSave: (role: Role) => Promise<boolean>;
		onCreate: () => Promise<boolean>;
		onDelete: (roleId: string) => Promise<boolean>;
		onMove: (roleId: string, toIndex: number) => void;
		/** Room only: people, for the Manage Members tab. */
		members?: Participant[];
		canAssign?: (person: Participant, roleId: string) => boolean;
		onAssign?: (participantId: string, roleId: string) => void;
		/** Where people go when they're taken out of a role. */
		fallbackRoleId?: string;
	}

	let { roles, defaultRoleIds, canManage, canGrant, onSave, onCreate, onDelete, onMove, members, canAssign, onAssign, fallbackRoleId }: Props = $props();

	let selectedId = $state<string | null>(null);
	let tab = $state<'display' | 'permissions' | 'members'>('display');
	/** Phones show either the list or the editor. */
	let editing = $state(false);
	let draft = $state<Role | null>(null);
	let draftFor = '';
	let shake = $state(false);
	let busy = $state(false);
	let search = $state('');
	let memberSearch = $state('');
	let adding = $state(false);
	let selectNewAfter = $state<number | null>(null);

	const selectedIndex = $derived(roles.findIndex((r) => r.id === selectedId));
	const original = $derived(selectedIndex >= 0 ? roles[selectedIndex] : null);
	const editable = $derived(selectedIndex >= 0 && canManage(selectedIndex));
	const dirty = $derived(!!draft && !!original && !same(draft, original));

	function same(a: Role, b: Role) {
		return a.name === b.name && a.color === b.color && [...a.permissions].sort().join() === [...b.permissions].sort().join();
	}

	// Keep a selection, and pick up the new role after "Create Role".
	$effect(() => {
		if (selectNewAfter !== null && roles.length > selectNewAfter) {
			selectedId = roles[roles.length - 1].id;
			selectNewAfter = null;
			tab = 'display';
			editing = true;
		} else if (!roles.some((r) => r.id === selectedId)) {
			selectedId = roles[0]?.id ?? null;
		}
	});

	// The draft follows the selected role, unless it has unsaved changes.
	$effect(() => {
		const o = original;
		untrack(() => {
			if (!o) draft = null;
			else if (draftFor !== o.id || !dirty) {
				draft = { ...o, permissions: [...o.permissions] };
				draftFor = o.id;
			}
		});
	});

	function warn() {
		shake = true;
		setTimeout(() => (shake = false), 600);
	}

	function select(id: string) {
		if (dirty && id !== selectedId) return warn();
		selectedId = id;
		editing = true;
	}

	async function save() {
		if (!draft) return;
		busy = true;
		const ok = await onSave({ ...draft, name: draft.name.trim() });
		busy = false;
		if (!ok) warn();
	}

	function reset() {
		if (original) draft = { ...original, permissions: [...original.permissions] };
	}

	async function create() {
		if (dirty) return warn();
		const before = roles.length;
		if (await onCreate()) selectNewAfter = before;
	}

	async function remove() {
		if (!original || !confirm(`Delete the role "${original.name}"? People with it get the default role.`)) return;
		draftFor = '';
		await onDelete(original.id);
	}

	function toggle(permission: Permission, on: boolean) {
		if (!draft) return;
		draft.permissions = on ? [...draft.permissions, permission] : draft.permissions.filter((p) => p !== permission);
	}

	const groups = $derived(
		PERMISSION_GROUPS.map((g) => ({
			...g,
			permissions: g.permissions.filter((p) => `${p.name} ${p.description}`.toLowerCase().includes(search.trim().toLowerCase()))
		})).filter((g) => g.permissions.length > 0)
	);

	const roleMembers = $derived((members ?? []).filter((m) => m.roleId === selectedId && m.name.toLowerCase().includes(memberSearch.trim().toLowerCase())));
	const addable = $derived((members ?? []).filter((m) => m.roleId !== selectedId && selectedId && canAssign?.(m, selectedId)));

	// Dragging roles by their handle to change the ranking.
	const ROW = 36;
	let drag = $state<{ id: string; from: number; startY: number; offset: number } | null>(null);
	const dropIndex = $derived(drag ? clampDrop(drag.from + Math.round(drag.offset / ROW)) : -1);

	function clampDrop(index: number) {
		let i = Math.max(0, Math.min(roles.length - 1, index));
		while (i < roles.length - 1 && !canManage(i)) i++;
		return i;
	}

	function startDrag(event: PointerEvent, role: Role, index: number) {
		if (!canManage(index)) return;
		event.preventDefault();
		(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
		drag = { id: role.id, from: index, startY: event.clientY, offset: 0 };
	}

	function endDrag() {
		if (drag && dropIndex !== drag.from) onMove(drag.id, dropIndex);
		drag = null;
	}

	function shift(index: number) {
		if (!drag || index === drag.from) return 0;
		if (drag.from < index && index <= dropIndex) return -ROW;
		if (dropIndex <= index && index < drag.from) return ROW;
		return 0;
	}

	const label = 'mb-2 block text-xs font-bold tracking-wide text-d-muted uppercase';
	const input = 'w-full rounded bg-d-deep px-3 py-2.5 text-d-text outline-none placeholder:text-d-faint disabled:opacity-60';
</script>

<div class="flex h-full min-h-0 text-d-text">
	<!-- Role list -->
	<div class="{editing ? 'hidden md:flex' : 'flex'} w-full shrink-0 flex-col md:w-56 md:border-r md:border-d-line md:pr-3">
		<div class="flex items-center justify-between px-2 pb-2">
			<span class="text-xs font-bold tracking-wide text-d-muted uppercase">Roles — {roles.length}</span>
			<button class="rounded p-1 text-d-muted hover:bg-d-hover hover:text-d-head" aria-label="Create role" title="Create Role" onclick={create}>
				<svg viewBox="0 0 24 24" class="h-[18px] w-[18px]"><path fill="currentColor" d="M13 5h-2v6H5v2h6v6h2v-6h6v-2h-6z" /></svg>
			</button>
		</div>
		<div class="flex flex-col overflow-y-auto">
			{#each roles as role, index (role.id)}
				{@const dragging = drag?.id === role.id}
				<div
					class="group flex h-9 shrink-0 items-center gap-2 rounded px-2 select-none
						{role.id === selectedId ? 'bg-d-active text-d-head' : 'text-d-muted hover:bg-d-hover hover:text-d-text'}
						{dragging ? 'relative z-10 bg-d-active shadow-lg' : 'transition-transform'}"
					style:transform="translateY({dragging ? drag!.offset : shift(index)}px)"
				>
					{#if canManage(index)}
						<span
							class="cursor-grab touch-none text-d-faint opacity-0 group-hover:opacity-100 max-md:opacity-100"
							aria-hidden="true"
							onpointerdown={(e) => startDrag(e, role, index)}
							onpointermove={(e) => drag && (drag.offset = e.clientY - drag.startY)}
							onpointerup={endDrag}
							onpointercancel={() => (drag = null)}
						>
							<Icon name="drag" size={16} />
						</span>
					{:else}
						<svg viewBox="0 0 24 24" class="h-4 w-4 shrink-0 text-d-faint" aria-label="Above your role"><path fill="currentColor" d="M17 9V7A5 5 0 0 0 7 7v2a3 3 0 0 0-3 3v7a3 3 0 0 0 3 3h10a3 3 0 0 0 3-3v-7a3 3 0 0 0-3-3zM9 7a3 3 0 1 1 6 0v2H9z" /></svg>
					{/if}
					<button class="flex min-w-0 flex-1 items-center gap-2 text-left" onclick={() => select(role.id)}>
						<span class="h-3 w-3 shrink-0 rounded-full" style:background={role.color}></span>
						<span class="truncate text-[15px] font-medium">{role.name}</span>
					</button>
				</div>
			{/each}
		</div>
	</div>

	<!-- Selected role -->
	{#if draft && original}
		<div class="{editing ? 'flex' : 'hidden md:flex'} min-w-0 flex-1 flex-col md:pl-6">
			<div class="flex items-center gap-2 pb-3">
				<button class="rounded p-1 text-d-muted hover:text-d-head md:hidden" aria-label="Back to roles" onclick={() => (dirty ? warn() : (editing = false))}>
					<svg viewBox="0 0 24 24" class="h-5 w-5"><path fill="currentColor" d="M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20z" /></svg>
				</button>
				<h3 class="truncate text-xs font-bold tracking-wide text-d-muted uppercase">Edit Role — {original.name}</h3>
			</div>

			<div class="flex gap-5 border-b border-d-line">
				{#each [['display', 'Display'], ['permissions', 'Permissions'], ...(members ? [['members', `Manage Members (${(members ?? []).filter((m) => m.roleId === selectedId).length})`]] : [])] as [id, name] (id)}
					<button
						class="-mb-px border-b-2 pb-2.5 text-[15px] font-medium {tab === id ? 'border-d-blurple text-d-head' : 'border-transparent text-d-muted hover:text-d-text'}"
						onclick={() => (tab = id as typeof tab)}
					>
						{name}
					</button>
				{/each}
			</div>

			{#if !editable}
				<p class="mt-4 flex items-center gap-2 rounded bg-d-deep px-3 py-2 text-sm text-d-muted">
					<svg viewBox="0 0 24 24" class="h-4 w-4 shrink-0"><path fill="currentColor" d="M17 9V7A5 5 0 0 0 7 7v2a3 3 0 0 0-3 3v7a3 3 0 0 0 3 3h10a3 3 0 0 0 3-3v-7a3 3 0 0 0-3-3zM9 7a3 3 0 1 1 6 0v2H9z" /></svg>
					You can only edit roles below your own.
				</p>
			{/if}

			<div class="min-h-0 flex-1 overflow-y-auto py-5 pr-1">
				{#if tab === 'display'}
					<label class="mb-6 block">
						<span class={label}>Role Name <span class="text-d-red">*</span></span>
						<input class={input} bind:value={draft.name} maxlength="32" disabled={!editable} />
					</label>

					<span class={label}>Role Color <span class="text-d-red">*</span></span>
					<p class="mb-3 text-sm text-d-muted">People with this role show their name in this color.</p>
					<div class="mb-8 flex flex-wrap gap-3">
						<button
							class="relative h-[50px] w-[66px] rounded border border-d-line"
							style:background={DEFAULT_ROLE_COLOR}
							title="Default"
							aria-label="Default color"
							disabled={!editable}
							onclick={() => draft && (draft.color = DEFAULT_ROLE_COLOR)}
						>
							{#if draft.color === DEFAULT_ROLE_COLOR}<span class="absolute inset-0 flex items-center justify-center text-white">✓</span>{/if}
						</button>
						<label class="relative h-[50px] w-[66px] cursor-pointer rounded" style:background={draft.color} title="Custom color">
							<input type="color" class="absolute inset-0 h-full w-full cursor-pointer opacity-0" bind:value={draft.color} disabled={!editable} />
							<svg viewBox="0 0 24 24" class="absolute top-1 right-1 h-3.5 w-3.5 text-white drop-shadow"><path fill="currentColor" d="M20.7 5.6 18.4 3.3a1 1 0 0 0-1.4 0l-3.1 3.1-1.4-1.4-1.4 1.4 1.4 1.4L4 16.3V20h3.7l8.5-8.5 1.4 1.4 1.4-1.4-1.4-1.4 3.1-3.1a1 1 0 0 0 0-1.4z" /></svg>
						</label>
						<div class="grid grid-cols-10 gap-2.5">
							{#each ROLE_COLORS as color (color)}
								<button
									class="relative h-5 w-5 rounded"
									style:background={color}
									aria-label={color}
									disabled={!editable}
									onclick={() => draft && (draft.color = color)}
								>
									{#if draft.color === color}<span class="absolute inset-0 flex items-center justify-center text-[11px] text-white">✓</span>{/if}
								</button>
							{/each}
						</div>
					</div>

					{#if editable}
						<div class="border-t border-d-line pt-6">
							{#if defaultRoleIds.includes(original.id)}
								<p class="text-sm text-d-muted">New people get this role, so it can't be deleted.</p>
							{:else if roles.length > 1}
								<button class="rounded bg-d-red px-4 py-2 text-sm font-medium text-white hover:bg-[#a12828]" onclick={remove}>Delete Role</button>
							{/if}
						</div>
					{/if}
				{:else if tab === 'permissions'}
					<div class="mb-4 flex items-center gap-3">
						<input class="{input} py-1.5" placeholder="Search permissions" bind:value={search} />
						{#if editable}
							<button class="shrink-0 text-sm font-medium text-[#00a8fc] hover:underline" onclick={() => draft && (draft.permissions = [])}>Clear permissions</button>
						{/if}
					</div>
					{#each groups as group (group.name)}
						<h4 class="mt-6 mb-1 text-xs font-bold tracking-wide text-d-muted uppercase first:mt-2">{group.name} Permissions</h4>
						{#each group.permissions as permission (permission.id)}
							{@const on = draft.permissions.includes(permission.id)}
							<div class="border-b border-d-line py-4">
								<div class="flex items-center justify-between gap-4">
									<span class="text-base font-medium text-d-head">{permission.name}</span>
									<Switch
										checked={on}
										label={permission.name}
										disabled={!editable || (!on && !canGrant(permission.id))}
										onchange={(v) => toggle(permission.id, v)}
									/>
								</div>
								<p class="mt-1 pr-14 text-sm text-d-muted">{permission.description}</p>
							</div>
						{/each}
					{:else}
						<p class="py-8 text-center text-d-muted">No permissions match.</p>
					{/each}
				{:else if tab === 'members'}
					<div class="mb-4 flex items-center gap-3">
						<input class="{input} py-1.5" placeholder="Search members" bind:value={memberSearch} />
						{#if addable.length > 0}
							<button class="shrink-0 rounded bg-d-blurple px-4 py-1.5 text-sm font-medium text-white hover:bg-[#4752c4]" onclick={() => (adding = !adding)}>
								Add Members
							</button>
						{/if}
					</div>
					{#if adding}
						<div class="mb-4 flex flex-col rounded bg-d-deep p-1">
							{#each addable as person (person.id)}
								<button
									class="flex items-center gap-3 rounded px-2 py-1.5 text-left hover:bg-d-hover"
									onclick={() => {
										onAssign?.(person.id, selectedId!);
										adding = false;
									}}
								>
									<span class="flex h-6 w-6 items-center justify-center rounded-full bg-d-active text-xs font-bold">{person.name[0]?.toUpperCase()}</span>
									{person.name}
								</button>
							{/each}
						</div>
					{/if}
					{#each roleMembers as person (person.id)}
						<div class="group flex items-center gap-3 rounded px-2 py-2 hover:bg-d-hover">
							<span class="flex h-8 w-8 items-center justify-center rounded-full text-sm font-bold text-white" style:background={original.color}>{person.name[0]?.toUpperCase()}</span>
							<span class="min-w-0 flex-1 truncate">{person.name} <span class="text-sm text-d-faint">{person.accountId ?? 'guest'}</span></span>
							{#if fallbackRoleId && fallbackRoleId !== selectedId && canAssign?.(person, fallbackRoleId)}
								<button
									class="rounded-full p-1 text-d-faint opacity-0 group-hover:opacity-100 hover:text-d-head max-md:opacity-100"
									aria-label="Remove from role"
									title="Remove from role"
									onclick={() => onAssign?.(person.id, fallbackRoleId)}
								>
									<Icon name="close" size={18} />
								</button>
							{/if}
						</div>
					{:else}
						<p class="py-8 text-center text-d-muted">Nobody has this role yet.</p>
					{/each}
				{/if}
			</div>

			{#if dirty}
				<div class="sticky bottom-0 pb-4">
					<SaveBar {shake} {busy} onReset={reset} onSave={save} />
				</div>
			{/if}
		</div>
	{/if}
</div>

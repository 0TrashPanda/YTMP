<script lang="ts">
	// Room settings, laid out like Discord's server settings: a sidebar of sections on the
	// left, the section on the right, ESC to close.
	import { onDestroy, onMount } from 'svelte';
	import { DEFAULT_ROLE_COLOR, rank } from '../../permissions';
	import type { Command, HostInfo, Participant, Permission, Role, RoomOpenMode } from '../../protocol.gen';
	import type { RoomConnection } from '../../room.svelte';
	import LeaveRoomDialog from '../LeaveRoomDialog.svelte';
	import MemberPermissions from './MemberPermissions.svelte';
	import RoleEditor from './RoleEditor.svelte';
	import SaveBar from './SaveBar.svelte';
	import Switch from './Switch.svelte';

	type Section = 'overview' | 'queue' | 'roles' | 'members' | 'bans';
	/** [start]: the section to open; 'list' opens the list of sections on phones (desktop shows the first one next to it). */
	let {
		room,
		host = null,
		start = 'members',
		onClose,
		onClone
	}: {
		room: RoomConnection;
		/** Where the room is hosted (a server can keep rooms always on). */
		host?: HostInfo | null;
		start?: Section | 'list';
		onClose: () => void;
		/** Opens "Clone room". */
		onClone?: () => void;
	} = $props();

	const rs = $derived(room.state!);
	const roles = $derived(rs.roles);
	const me = $derived(room.me);
	const myRank = $derived(me ? rank(me, roles) : roles.length);

	const sections = $derived(
		(
			[
				['overview', 'Overview', room.can('change_settings')],
				['queue', 'Queue', room.can('change_settings')],
				['roles', 'Roles', room.can('edit_roles')],
				['members', 'Members', true],
				['bans', 'Bans', room.can('ban')]
			] as [Section, string, boolean][]
		).filter(([, , shown]) => shown)
	);
	// svelte-ignore state_referenced_locally
	let section = $state<Section>(start === 'list' ? (sections[0]?.[0] ?? 'members') : start);
	/** Phones: a section (true) or the list of sections. */
	// svelte-ignore state_referenced_locally
	let showingSection = $state(start !== 'list');
	/** Phones: the list was shown, so Back goes there from a section. */
	// svelte-ignore state_referenced_locally
	let cameFromList = $state(start === 'list');
	let roleEditor = $state<RoleEditor>();

	/** The phone's Back button: one step back (a dialog, a menu, a role, the list). False: close the settings. */
	export function back(): boolean {
		if (leaving) leaving = false;
		else if (permissionsFor) permissionsFor = null;
		else if (menuFor) menuFor = null;
		else if (section === 'roles' && roleEditor?.back()) return true;
		// Wide screens show the list next to the section.
		else if (showingSection && cameFromList && !matchMedia('(min-width: 768px)').matches) showingSection = false;
		else return false;
		return true;
	}

	async function run(command: Command): Promise<boolean> {
		return (await room.run(command)) === null;
	}

	// --- people ---
	function outranks(person: Participant) {
		return !!me && !person.isOwner && (me.isOwner || myRank < rank(person, roles));
	}
	function canAssign(person: Participant, roleId: string) {
		const index = roles.findIndex((r) => r.id === roleId);
		return room.can('assign_roles') && outranks(person) && index >= 0 && (me!.isOwner || index > myRank);
	}
	const roleOf = (person: Participant) => roles.find((r) => r.id === person.roleId);
	const people = $derived(
		[...rs.participants].sort((a, b) => Number(b.online) - Number(a.online) || rank(a, roles) - rank(b, roles) || a.name.localeCompare(b.name))
	);
	let memberSearch = $state('');
	let menuFor = $state<string | null>(null);
	let permissionsFor = $state<Participant | null>(null);

	// --- overview ---
	let name = $state('');
	let guestRole = $state('');
	let accountRole = $state('');
	let autoplay = $state(true);
	let alwaysOn = $state(false);
	let startWaitSeconds = $state(2);
	let karaoke = $state(false);
	let overviewBusy = $state(false);
	function resetOverview() {
		name = rs.room.name;
		guestRole = rs.settings.defaultGuestRole;
		accountRole = rs.settings.defaultAccountRole;
		autoplay = rs.settings.autoplay;
		alwaysOn = rs.settings.alwaysOn;
		startWaitSeconds = rs.settings.startWaitSeconds;
		karaoke = rs.settings.karaoke;
	}
	resetOverview();
	const overviewDirty = $derived(
		name.trim() !== rs.room.name ||
			guestRole !== rs.settings.defaultGuestRole ||
			accountRole !== rs.settings.defaultAccountRole ||
			autoplay !== rs.settings.autoplay ||
			alwaysOn !== rs.settings.alwaysOn ||
			startWaitSeconds !== rs.settings.startWaitSeconds ||
			karaoke !== rs.settings.karaoke
	);
	async function saveOverview() {
		overviewBusy = true;
		await run({
			kind: 'UpdateSettings',
			name: name.trim(),
			defaultGuestRole: guestRole,
			defaultAccountRole: accountRole,
			autoplay,
			alwaysOn,
			startWaitSeconds,
			karaoke,
			...unchangedQueueSettings
		});
		overviewBusy = false;
	}

	/** Null: the host leaves it as it is. */
	const unchangedQueueSettings = { fairOrder: null, voteSkip: null, voteSkipPercent: null, maxSongsPerPerson: null, maxSongMinutes: null, noRepeatMinutes: null };

	// --- queue: fair order, vote to skip, limits (docs/features/queue.md) ---
	let fairOrder = $state(false);
	let voteSkip = $state(false);
	let voteSkipPercent = $state(50);
	let maxSongsPerPerson = $state(0);
	let maxSongMinutes = $state(0);
	let noRepeatMinutes = $state(0);
	let queueBusy = $state(false);
	function resetQueue() {
		({ fairOrder, voteSkip, voteSkipPercent, maxSongsPerPerson, maxSongMinutes, noRepeatMinutes } = rs.settings);
	}
	resetQueue();
	const queueDirty = $derived(
		fairOrder !== rs.settings.fairOrder ||
			voteSkip !== rs.settings.voteSkip ||
			voteSkipPercent !== rs.settings.voteSkipPercent ||
			maxSongsPerPerson !== rs.settings.maxSongsPerPerson ||
			maxSongMinutes !== rs.settings.maxSongMinutes ||
			noRepeatMinutes !== rs.settings.noRepeatMinutes
	);
	async function saveQueue() {
		queueBusy = true;
		await run({ kind: 'UpdateSettings', name: null, defaultGuestRole: null, defaultAccountRole: null, autoplay: null, alwaysOn: null, startWaitSeconds: null, karaoke: null, fairOrder, voteSkip, voteSkipPercent, maxSongsPerPerson, maxSongMinutes, noRepeatMinutes });
		queueBusy = false;
	}
	/** Choices for a limit, with the current value even when it isn't one of them (set elsewhere). */
	const choices = (values: number[], current: number) => [...new Set([0, ...values, current])].sort((a, b) => a - b);
	const minutes = (m: number) => (m % 60 === 0 && m >= 60 ? `${m / 60} hour${m === 60 ? '' : 's'}` : `${m} min`);

	// --- password ---
	let newPassword = $state('');
	let changingPassword = $state(false);
	async function setPassword(password: string | null) {
		if (await run({ kind: 'SetPassword', password })) {
			newPassword = '';
			changingPassword = false;
		}
	}

	// --- owner and leaving ---
	async function makeOwner(person: Participant) {
		menuFor = null;
		if (!confirm(`Make ${person.name} the owner of this room? You keep the top role, but they can take it from you.`)) return;
		await run({ kind: 'ChangeOwner', participantId: person.id });
	}

	let leaving = $state(false);

	function onKey(event: KeyboardEvent) {
		if (event.key === 'Escape' && !permissionsFor) onClose();
	}
	onMount(() => window.addEventListener('keydown', onKey));
	onDestroy(() => window.removeEventListener('keydown', onKey));

	const label = 'mb-2 block text-xs font-bold tracking-wide text-d-muted uppercase';
	const input = 'w-full rounded bg-d-deep px-3 py-2.5 text-d-text outline-none disabled:opacity-60';
</script>

<div class="fixed inset-0 z-[60] flex bg-d-bg text-d-text" role="dialog" aria-modal="true" aria-label="Room settings">
	<!-- Sidebar -->
	<nav class="{showingSection ? 'hidden md:flex' : 'flex'} w-full shrink-0 justify-end bg-d-side md:w-[min(40%,280px)] lg:w-[calc(50%-370px)] lg:min-w-[218px]">
		<div class="flex w-full flex-col gap-0.5 px-2 py-6 md:w-[218px] md:pt-[60px] md:pr-1.5 md:pl-5">
			<h2 class="truncate px-2.5 pb-1.5 text-xs font-bold tracking-wide text-d-muted uppercase">{rs.room.name}</h2>
			{#each sections as [id, title] (id)}
				<button
					class="rounded px-2.5 py-1.5 text-left text-base font-medium {section === id ? 'bg-d-active text-d-head' : 'text-d-muted hover:bg-d-hover hover:text-d-text'}"
					onclick={() => ((section = id), (showingSection = true))}
				>
					{title}
				</button>
			{/each}
			<div class="mx-2.5 my-2 h-px bg-d-line"></div>
			{#if onClone && room.can('clone_room')}
				<button class="rounded px-2.5 py-1.5 text-left text-base font-medium text-d-muted hover:bg-d-hover hover:text-d-text" onclick={onClone}>Clone Room</button>
			{/if}
			<button class="rounded px-2.5 py-1.5 text-left text-base font-medium text-d-muted hover:bg-d-hover hover:text-d-text" onclick={onClose}>Close</button>
			<!-- Like Discord's "Leave Server". You can come back with the code, but as someone new. -->
			<button
				class="flex items-center justify-between rounded px-2.5 py-1.5 text-left text-base font-medium text-[#f23f43] hover:bg-d-red/10"
				onclick={() => (leaving = true)}
			>
				Leave Room
				<svg viewBox="0 0 24 24" class="h-4 w-4"><path fill="currentColor" d="M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h5v-2H5V5h5V3zm6.6 4.4L15.2 8.8 17.4 11H9v2h8.4l-2.2 2.2 1.4 1.4L21.2 12z" /></svg>
			</button>
		</div>
	</nav>

	<!-- Section -->
	<div class="{showingSection ? 'flex' : 'hidden md:flex'} min-w-0 flex-1 overflow-hidden">
		<div class="flex h-full w-full max-w-[740px] min-w-0 flex-col px-4 pt-4 md:px-10 md:pt-[60px]">
			<div class="mb-5 flex items-center gap-2">
				<button class="rounded p-1 text-d-muted hover:text-d-head md:hidden" aria-label="Back" onclick={() => ((showingSection = false), (cameFromList = true))}>
					<svg viewBox="0 0 24 24" class="h-5 w-5"><path fill="currentColor" d="M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20z" /></svg>
				</button>
				<h1 class="text-xl font-semibold text-d-head">{sections.find(([id]) => id === section)?.[1] ?? 'Members'}</h1>
			</div>

			{#if section === 'overview'}
				<div class="min-h-0 flex-1 overflow-y-auto">
					<label class="mb-6 block">
						<span class={label}>Room Name</span>
						<input class={input} bind:value={name} maxlength="64" />
					</label>
					<div class="mb-6 h-px bg-d-line"></div>
					<h3 class="mb-1 text-base font-semibold text-d-head">Default Roles</h3>
					<p class="mb-4 text-sm text-d-muted">The role people get when they join. You can change anyone's role later under Members.</p>
					<label class="mb-5 block">
						<span class={label}>Guests</span>
						<select class={input} bind:value={guestRole}>
							{#each roles as role (role.id)}<option value={role.id}>{role.name}</option>{/each}
						</select>
					</label>
					<label class="mb-5 block">
						<span class={label}>People with an account</span>
						<select class={input} bind:value={accountRole}>
							{#each roles as role (role.id)}<option value={role.id}>{role.name}</option>{/each}
						</select>
					</label>
					<div class="mb-6 h-px bg-d-line"></div>
					<div class="flex items-center gap-4">
						<div class="min-w-0 flex-1">
							<h3 class="text-base font-semibold text-d-head">Autoplay</h3>
							<p class="text-sm text-d-muted">When the queue runs out, keep playing songs like the last one, as YouTube Music does.</p>
						</div>
						<Switch checked={autoplay} label="Autoplay" onchange={(on) => (autoplay = on)} />
					</div>
					<div class="my-6 h-px bg-d-line"></div>
					<div class="flex items-center gap-4">
						<div class="min-w-0 flex-1">
							<h3 class="text-base font-semibold text-d-head">Karaoke mode</h3>
							<p class="text-sm text-d-muted">The party screen and everyone's full player show the song's lyrics large, line by line, instead of the album art.</p>
						</div>
						<Switch checked={karaoke} label="Karaoke mode" onchange={(on) => (karaoke = on)} />
					</div>
					<div class="my-6 h-px bg-d-line"></div>
					<h3 class="mb-1 text-base font-semibold text-d-head">Starting a song</h3>
					<p class="mb-3 text-sm text-d-muted">
						A new song waits until every phone, browser, TV and speaker playing the room has loaded it, so they all start it together. A slow one joins late after this.
					</p>
					<select class={input} bind:value={startWaitSeconds} aria-label="Starting a song">
						{#each choices([1, 2, 3, 5, 10], startWaitSeconds) as seconds (seconds)}
							<option value={seconds}>{seconds === 0 ? "Don't wait (some miss the start)" : `Wait at most ${seconds} s`}</option>
						{/each}
					</select>
					<div class="my-6 h-px bg-d-line"></div>
					<h3 class="mb-1 text-base font-semibold text-d-head">Password</h3>
					<p class="mb-3 text-sm text-d-muted">
						{rs.room.hasPassword
							? "People need the password to join. Who's already in, the owner and the hosting phone don't."
							: 'Anyone with the code (or the link) can join. Set a password to ask for it first.'}
					</p>
					{#if changingPassword || !rs.room.hasPassword}
						<form
							class="flex gap-2"
							onsubmit={(e) => {
								e.preventDefault();
								if (newPassword) setPassword(newPassword);
							}}
						>
							<input class="{input} flex-1" type="text" bind:value={newPassword} maxlength="64" placeholder="New password" autocomplete="off" />
							<button class="rounded bg-d-blurple px-4 text-sm font-medium text-white disabled:opacity-50" disabled={!newPassword}>Set</button>
						</form>
					{:else}
						<div class="flex gap-2">
							<button class="rounded bg-d-active px-4 py-2 text-sm font-medium text-d-head hover:bg-d-hover" onclick={() => (changingPassword = true)}>Change</button>
							<button class="rounded px-4 py-2 text-sm font-medium text-[#f23f43] hover:bg-d-red/10" onclick={() => setPassword(null)}>Remove password</button>
						</div>
					{/if}
					{#if host?.kind === 'server' && rs.room.visibility === 'public'}
						<div class="my-6 h-px bg-d-line"></div>
						<div class="flex items-center gap-4">
							<div class="min-w-0 flex-1">
								<h3 class="text-base font-semibold text-d-head">Always on</h3>
								<p class="text-sm text-d-muted">Keep the room on the server when everyone has left, so it's there next time. Otherwise it goes after an hour without anyone in it.</p>
							</div>
							<Switch checked={alwaysOn} label="Always on" onchange={(on) => (alwaysOn = on)} />
						</div>
					{/if}
					{#if me?.isOwner && me.accountId}
						<!-- Rooms of an account live on one of its hosts and can move (docs/features/room-sync.md). -->
						<div class="my-6 h-px bg-d-line"></div>
						<label class="block">
							<span class={label}>Opening it on your other device</span>
							<select
								class={input}
								value={rs.settings.openElsewhere ?? ''}
								onchange={(e) => run({ kind: 'SetOpenElsewhere', mode: (e.currentTarget.value || null) as RoomOpenMode | null })}
							>
								<option value="">Your account's setting</option>
								<option value="ask">Ask: open it here, or move it</option>
								<option value="move">Move it there</option>
								<option value="move_if_away">Move it only if this device is away</option>
							</select>
						</label>
						<p class="mt-2 text-sm text-d-muted">This room lives here. When you open it on your phone or server, it can move there and continue.</p>
					{/if}
				</div>
				{#if overviewDirty}
					<div class="pb-4"><SaveBar busy={overviewBusy} onReset={resetOverview} onSave={saveOverview} /></div>
				{/if}
			{:else if section === 'queue'}
				<div class="min-h-0 flex-1 overflow-y-auto">
					<div class="flex items-center gap-4">
						<div class="min-w-0 flex-1">
							<h3 class="text-base font-semibold text-d-head">Fair order</h3>
							<p class="text-sm text-d-muted">
								The queue goes round by round, one song per person per round, so nobody takes over. Someone with nothing queued gets their song near the top. People
								who may reorder the queue can still drag songs anywhere.
							</p>
						</div>
						<Switch checked={fairOrder} label="Fair order" onchange={(on) => (fairOrder = on)} />
					</div>
					<div class="my-6 h-px bg-d-line"></div>
					<div class="flex items-center gap-4">
						<div class="min-w-0 flex-1">
							<h3 class="text-base font-semibold text-d-head">Vote to skip</h3>
							<p class="text-sm text-d-muted">
								People with <em>Vote to Skip</em> (Listeners) vote instead of skipping right away; the song is skipped once enough of the people here voted. People with
								<em>Skip</em> (DJs, Admins) still skip right away.
							</p>
						</div>
						<Switch checked={voteSkip} label="Vote to skip" onchange={(on) => (voteSkip = on)} />
					</div>
					{#if voteSkip}
						<label class="mt-4 block">
							<span class={label}>Votes needed</span>
							<select class={input} bind:value={voteSkipPercent}>
								{#each choices([25, 33, 50, 66, 75, 100], voteSkipPercent).filter((p) => p > 0) as percent (percent)}
									<option value={percent}>{percent === 100 ? 'Everyone' : `${percent}% of the people here`}</option>
								{/each}
							</select>
						</label>
					{/if}
					<div class="my-6 h-px bg-d-line"></div>
					<h3 class="mb-1 text-base font-semibold text-d-head">Limits</h3>
					<p class="mb-4 text-sm text-d-muted">For everyone who can't change the room's settings. Songs over a limit aren't added, and they're told why.</p>
					<label class="mb-5 block">
						<span class={label}>Songs per person in the queue</span>
						<select class={input} bind:value={maxSongsPerPerson}>
							{#each choices([1, 2, 3, 5, 10, 20], maxSongsPerPerson) as count (count)}
								<option value={count}>{count === 0 ? 'No limit' : `At most ${count}`}</option>
							{/each}
						</select>
					</label>
					<label class="mb-5 block">
						<span class={label}>Song length</span>
						<select class={input} bind:value={maxSongMinutes}>
							{#each choices([5, 7, 10, 15, 20, 30, 60], maxSongMinutes) as m (m)}
								<option value={m}>{m === 0 ? 'No limit' : `At most ${minutes(m)}`}</option>
							{/each}
						</select>
					</label>
					<label class="mb-5 block">
						<span class={label}>Played again</span>
						<select class={input} bind:value={noRepeatMinutes}>
							{#each choices([30, 60, 120, 240, 480], noRepeatMinutes) as m (m)}
								<option value={m}>{m === 0 ? 'Any time' : `Not within ${minutes(m)} of the last time`}</option>
							{/each}
						</select>
					</label>
					<p class="text-sm text-d-muted">Shuffle and repeat are next to the controls in the player.</p>
				</div>
				{#if queueDirty}
					<div class="pb-4"><SaveBar busy={queueBusy} onReset={resetQueue} onSave={saveQueue} /></div>
				{/if}
			{:else if section === 'roles'}
				<p class="-mt-2 mb-5 text-sm text-d-muted">
					Everyone has one role. Higher roles come first; you can only manage people and roles below your own. The owner can do everything.
				</p>
				<div class="min-h-0 flex-1">
					<RoleEditor
						bind:this={roleEditor}
						{roles}
						defaultRoleIds={[rs.settings.defaultGuestRole, rs.settings.defaultAccountRole]}
						canManage={(index) => room.can('edit_roles') && (!!me?.isOwner || index > myRank)}
						canGrant={(p: Permission) => room.can(p)}
						onSave={(role: Role) => run({ kind: 'UpdateRole', role })}
						onCreate={() => run({ kind: 'CreateRole', name: 'new role', color: DEFAULT_ROLE_COLOR, permissions: [] })}
						onDelete={(roleId) => run({ kind: 'DeleteRole', roleId })}
						onMove={(roleId, toIndex) => run({ kind: 'MoveRole', roleId, toIndex })}
						members={rs.participants}
						{canAssign}
						onAssign={(participantId, roleId) => run({ kind: 'AssignRole', participantId, roleId })}
						fallbackRoleId={rs.settings.defaultGuestRole}
					/>
				</div>
			{:else if section === 'members'}
				<input class="{input} mb-4 py-1.5" placeholder="Search members" bind:value={memberSearch} />
				<div class="min-h-0 flex-1 overflow-y-auto pb-6">
					<div class="mb-1 flex px-2 text-xs font-bold tracking-wide text-d-muted uppercase">
						<span class="flex-1">Name</span><span class="w-36 max-sm:hidden">Role</span><span class="w-8"></span>
					</div>
					{#each people.filter((p) => p.name.toLowerCase().includes(memberSearch.trim().toLowerCase())) as person (person.id)}
						{@const role = roleOf(person)}
						{@const manageable = outranks(person)}
						<div class="group relative flex items-center gap-3 border-t border-d-line px-2 py-2.5 hover:bg-d-hover {person.online ? '' : 'opacity-60'}">
							<div class="relative shrink-0">
								<span class="flex h-8 w-8 items-center justify-center rounded-full text-sm font-bold text-white" style:background={role?.color ?? DEFAULT_ROLE_COLOR}>
									{person.name[0]?.toUpperCase()}
								</span>
								<span class="absolute -right-0.5 -bottom-0.5 h-3.5 w-3.5 rounded-full border-[3px] border-d-bg {person.online ? 'bg-d-green' : 'bg-[#80848e]'}"></span>
							</div>
							<div class="min-w-0 flex-1">
								<p class="flex items-center gap-1 truncate font-medium" style:color={role?.color}>
									{person.name}
									{#if person.isOwner}
										<svg viewBox="0 0 24 24" class="h-3.5 w-3.5 shrink-0 text-[#f0b232]" aria-label="Room owner"><path fill="currentColor" d="M5 16 3 5l5.5 5L12 4l3.5 6L21 5l-2 11H5zm14 3c0 .6-.4 1-1 1H6c-.6 0-1-.4-1-1v-1h14v1z" /></svg>
									{/if}
									{#if person.id === me?.id}<span class="text-xs font-normal text-d-faint">(you)</span>{/if}
								</p>
								<p class="truncate text-xs text-d-faint">
									{person.screen ? 'Shows the room on a TV' : (person.accountId ?? 'Guest')}{person.speakers.length ? ` · plays on ${person.speakers.join(', ')}` : ''}{person.allow.length || person.deny.length ? ' · own permissions' : ''}
								</p>
							</div>
							<div class="w-36 max-sm:w-auto">
								{#if role && roles.some((r) => canAssign(person, r.id))}
									<select
										class="max-w-full cursor-pointer rounded-full bg-d-deep py-0.5 pr-2 pl-2 text-xs font-medium text-d-text outline-none"
										value={person.roleId}
										onchange={(e) => run({ kind: 'AssignRole', participantId: person.id, roleId: e.currentTarget.value })}
									>
										{#each roles as r (r.id)}
											<option value={r.id} disabled={!canAssign(person, r.id)}>● {r.name}</option>
										{/each}
									</select>
								{:else}
									<span class="inline-flex max-w-full items-center gap-1.5 rounded-full bg-d-deep px-2 py-0.5 text-xs font-medium">
										<span class="h-2.5 w-2.5 shrink-0 rounded-full" style:background={role?.color ?? DEFAULT_ROLE_COLOR}></span>
										<span class="truncate">{person.isOwner ? 'Owner' : person.screen ? 'Screen' : (role?.name ?? '—')}</span>
									</span>
								{/if}
							</div>
							<div class="w-8">
								{#if (manageable && (room.can('kick') || room.can('ban') || room.can('assign_roles'))) || (me?.isOwner && person.id !== me.id)}
									<button
										class="rounded p-1 text-d-muted hover:bg-d-active hover:text-d-head"
										aria-label="More for {person.name}"
										onclick={() => (menuFor = menuFor === person.id ? null : person.id)}
									>
										<svg viewBox="0 0 24 24" class="h-5 w-5"><path fill="currentColor" d="M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4z" /></svg>
									</button>
								{/if}
							</div>
							{#if menuFor === person.id}
								<div class="absolute top-11 right-2 z-10 flex w-48 flex-col rounded bg-d-bar p-1.5 shadow-xl">
									{#if me?.isOwner && person.id !== me.id && !person.screen}
										<button class="rounded px-2 py-1.5 text-left text-sm hover:bg-d-blurple hover:text-white" onclick={() => makeOwner(person)}>Make Owner</button>
									{/if}
									{#if manageable && room.can('assign_roles')}
										<button class="rounded px-2 py-1.5 text-left text-sm hover:bg-d-blurple hover:text-white" onclick={() => ((permissionsFor = person), (menuFor = null))}>Permissions</button>
									{/if}
									{#if manageable && room.can('kick')}
										<button class="rounded px-2 py-1.5 text-left text-sm text-[#f23f43] hover:bg-d-red hover:text-white" onclick={() => ((menuFor = null), run({ kind: 'Kick', participantId: person.id }))}>
											Kick {person.name}
										</button>
									{/if}
									{#if manageable && room.can('ban')}
										<button
											class="rounded px-2 py-1.5 text-left text-sm text-[#f23f43] hover:bg-d-red hover:text-white"
											onclick={() => {
												menuFor = null;
												if (confirm(`Ban ${person.name}? They can't join this room again until the ban is lifted.`)) run({ kind: 'Ban', participantId: person.id });
											}}
										>
											Ban {person.name}
										</button>
									{/if}
								</div>
							{/if}
						</div>
					{/each}
				</div>
			{:else if section === 'bans'}
				<p class="-mt-2 mb-5 text-sm text-d-muted">Account holders are banned by account, guests by their browser (a guest can get around that).</p>
				<div class="min-h-0 flex-1 overflow-y-auto">
					{#each rs.bans as ban (ban.id)}
						<div class="flex items-center gap-3 border-t border-d-line px-2 py-3">
							<span class="flex h-8 w-8 items-center justify-center rounded-full bg-d-active text-sm font-bold">{ban.name[0]?.toUpperCase()}</span>
							<div class="min-w-0 flex-1">
								<p class="truncate font-medium text-d-head">{ban.name}</p>
								<p class="truncate text-xs text-d-faint">{ban.accountId ?? 'Guest'}</p>
							</div>
							<button class="rounded border border-d-red px-3 py-1 text-sm font-medium text-white hover:bg-d-red" onclick={() => run({ kind: 'Unban', banId: ban.id })}>Revoke Ban</button>
						</div>
					{:else}
						<p class="py-12 text-center text-d-muted">Nobody is banned.</p>
					{/each}
				</div>
			{/if}
		</div>

		<!-- Discord's ESC button -->
		<div class="hidden shrink-0 pt-[60px] pr-5 md:block">
			<button class="flex flex-col items-center gap-1.5 text-d-muted hover:text-d-head" onclick={onClose} aria-label="Close settings">
				<span class="flex h-9 w-9 items-center justify-center rounded-full border-2 border-current">
					<svg viewBox="0 0 24 24" class="h-[18px] w-[18px]"><path fill="currentColor" d="M18.4 4 12 10.4 5.6 4 4 5.6 10.4 12 4 18.4 5.6 20 12 13.6 18.4 20 20 18.4 13.6 12 20 5.6z" /></svg>
				</span>
				<span class="text-[13px] font-semibold">ESC</span>
			</button>
		</div>
	</div>
</div>

{#if leaving}
	<LeaveRoomDialog {room} onClose={() => (leaving = false)} />
{/if}

{#if permissionsFor}
	{@const current = rs.participants.find((p) => p.id === permissionsFor!.id)}
	{#if current}
		<MemberPermissions
			person={current}
			role={roleOf(current)}
			canGrant={(p) => room.can(p)}
			onSave={(allow, deny) => run({ kind: 'SetParticipantPermissions', participantId: current.id, allow, deny })}
			onClose={() => (permissionsFor = null)}
		/>
	{/if}
{/if}

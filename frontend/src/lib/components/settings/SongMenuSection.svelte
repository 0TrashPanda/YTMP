<script lang="ts">
	// Settings → Song menu: the order of its actions, which show, the dividers, the big
	// buttons at the top of the phone's menu, and what holding an action (or a player button)
	// offers (see songMenu.svelte.ts).
	import {
		BUTTON_COUNT,
		DEFAULT_BUTTONS,
		HOLD_CHOICES,
		HOLD_DOES,
		PLAYER_BUTTONS,
		holds,
		setChoiceShown,
		setHoldOn,
		type Holdable,
		MENU_ACTIONS,
		addDivider,
		isDefaultLayout,
		moveItem,
		removeDivider,
		resetLayout,
		setButton,
		setShown,
		songMenuLayout,
		type MenuAction
	} from '../../songMenu.svelte';
	import Icon from '../Icon.svelte';
	import Switch from './Switch.svelte';


	const songMenu = $derived(songMenuLayout());

	const actions = Object.keys(MENU_ACTIONS) as MenuAction[];
	const onButton = (action: MenuAction) => songMenu.buttons.includes(action);

	/** What can be held: the menu's actions (in their order), then the player's buttons. */
	const holdable = $derived<Holdable[]>([
		...songMenu.items.filter((i): i is MenuAction => i !== 'divider' && i in HOLD_CHOICES),
		...(Object.keys(PLAYER_BUTTONS) as Holdable[])
	]);
	const nameOf = (h: Holdable) => (h in MENU_ACTIONS ? MENU_ACTIONS[h as MenuAction] : PLAYER_BUTTONS[h as keyof typeof PLAYER_BUTTONS]);
	let open = $state<Holdable | null>(null);
</script>

<div class="flex flex-col gap-8">
	<section class="flex flex-col gap-3">
		<div class="flex items-end gap-2">
			<div class="min-w-0 flex-1">
				<p class="text-sm font-medium">Big buttons</p>
				<p class="text-xs text-muted">At the top of the menu on a phone, like YouTube Music. They aren't repeated in the list below them.</p>
			</div>
			{#if !isDefaultLayout()}
				<button class="shrink-0 rounded-full px-3 py-1 text-xs text-muted hover:bg-raised hover:text-white" onclick={resetLayout}>Defaults</button>
			{/if}
		</div>
		{#each { length: BUTTON_COUNT } as _, i (i)}
			<label class="flex items-center gap-4">
				<span class="min-w-0 flex-1 text-sm">Button {i + 1}</span>
				<select
					class="rounded-lg bg-raised px-3 py-2 text-sm outline-none"
					value={songMenu.buttons[i] ?? ''}
					onchange={(e) => setButton(i, (e.currentTarget.value || null) as MenuAction | null)}
				>
					<option value="">None</option>
					{#each actions as action (action)}
						<option value={action}>{MENU_ACTIONS[action].name}{action === DEFAULT_BUTTONS[i] ? ' (default)' : ''}</option>
					{/each}
				</select>
			</label>
		{/each}
	</section>

	<section class="flex flex-col gap-3">
		<div>
			<p class="text-sm font-medium">The menu</p>
			<p class="text-xs text-muted">Move actions up or down, hide the ones you don't use, and put dividers between them. An action only shows where it can be used.</p>
		</div>
		<ul class="flex flex-col">
			{#each songMenu.items as item, i (i)}
				<li class="flex items-center gap-3 border-b border-line/60 py-1.5">
					{#if item === 'divider'}
						<div class="h-px min-w-0 flex-1 bg-white/30"></div>
						<span class="text-xs text-muted">Divider</span>
						<button class="rounded-full p-1.5 text-muted hover:bg-raised hover:text-white" aria-label="Remove divider" onclick={() => removeDivider(i)}><Icon name="close" size={18} /></button>
					{:else}
						{@const shown = !songMenu.hidden.includes(item)}
						<Icon name={MENU_ACTIONS[item].icon} size={20} class="shrink-0 {shown ? '' : 'text-muted'}" />
						<div class="min-w-0 flex-1">
							<p class="truncate text-sm {shown ? '' : 'text-muted line-through'}">{MENU_ACTIONS[item].name}</p>
							{#if onButton(item) || MENU_ACTIONS[item].hint}
								<p class="truncate text-xs text-muted">{onButton(item) ? 'A big button on a phone' : MENU_ACTIONS[item].hint}</p>
							{/if}
						</div>
						<Switch checked={shown} label="Show {MENU_ACTIONS[item].name}" onchange={(on) => setShown(item, on)} />
					{/if}
					<button class="rounded-full p-1.5 text-muted hover:bg-raised hover:text-white disabled:opacity-30" aria-label="Move up" disabled={i === 0} onclick={() => moveItem(i, -1)}>
						<Icon name="expand" size={18} />
					</button>
					<button
						class="rounded-full p-1.5 text-muted hover:bg-raised hover:text-white disabled:opacity-30"
						aria-label="Move down"
						disabled={i === songMenu.items.length - 1}
						onclick={() => moveItem(i, 1)}
					>
						<Icon name="collapse" size={18} />
					</button>
				</li>
			{/each}
		</ul>
		<button class="flex items-center gap-2 self-start rounded-full px-3 py-1.5 text-sm text-muted hover:bg-raised hover:text-white" onclick={addDivider}>
			<Icon name="add" size={18} /> Add a divider
		</button>
	</section>

	<section class="flex flex-col gap-3">
		<div>
			<p class="text-sm font-medium">Holding</p>
			<p class="text-xs text-muted">
				Hold an action in the menu (with a mouse: right-click it, or its ›) for more choices, like Start radio from only your listening. Switch holding off, or hide
				choices you don't use.
			</p>
		</div>
		<ul class="flex flex-col">
			{#each holdable as h (h)}
				{@const on = !songMenu.hold[h]?.off}
				<li class="border-b border-line/60 py-1.5">
					<div class="flex items-center gap-3">
						<Icon name={nameOf(h).icon} size={20} class="shrink-0 {on ? '' : 'text-muted'}" />
						<button class="min-w-0 flex-1 text-left" onclick={() => (open = open === h ? null : h)} disabled={!HOLD_CHOICES[h]?.length}>
							<p class="truncate text-sm">{nameOf(h).name}</p>
							<p class="truncate text-xs text-muted">
								{HOLD_DOES[h] ?? `${(HOLD_CHOICES[h] ?? []).filter((c) => !songMenu.hold[h]?.hidden.includes(c.id)).length} choices`}
							</p>
						</button>
						{#if HOLD_CHOICES[h]?.length}
							<Icon name="chevron" size={18} class="shrink-0 text-muted transition-transform {open === h ? 'rotate-180' : ''}" />
						{/if}
						<Switch checked={!songMenu.hold[h]?.off} label="Holding {nameOf(h).name}" onchange={(v) => setHoldOn(h, v)} />
					</div>
					{#if open === h}
						<ul class="mt-1 ml-8 flex flex-col">
							{#each HOLD_CHOICES[h] ?? [] as c (c.id)}
								{@const shown = !songMenu.hold[h]?.hidden.includes(c.id)}
								<li class="flex items-center gap-3 py-1.5">
									<span class="min-w-0 flex-1 text-sm {shown ? '' : 'text-muted line-through'}">{c.name}</span>
									<Switch checked={shown} disabled={!!songMenu.hold[h]?.off} label="Show {c.name}" onchange={(v) => setChoiceShown(h, c.id, v)} />
								</li>
							{/each}
						</ul>
					{/if}
				</li>
			{/each}
		</ul>
	</section>
</div>

<script lang="ts">
	// Settings → Taps and swipes: what tapping, swiping and holding a song does, in song lists,
	// in the queue and in Autoplay (see gestures.svelte.ts).
	import { ACTION_NAMES, ACTIONS, DEFAULTS, GESTURES, gestureSetting, gestures, resetGestures, setGesture, type QueueAction, type Where } from '../../gestures.svelte';
	import { settings } from '../../settings.svelte';
	import DeviceNote from './DeviceNote.svelte';
	import Toggle from './Toggle.svelte';
	import Choice from './Choice.svelte';
	import type { SwipeSkips } from '../../storage';
	import DeviceToggle from './DeviceToggle.svelte';

	const groups: [Where, string, string][] = [
		['list', 'Songs in lists', 'Search, albums, artists, playlists and Local.'],
		['queue', 'Songs in the queue', 'In Up next: the queue, the song playing and what played.'],
		['autoplay', 'Songs in Autoplay', 'In Up next: what plays after the queue.']
	];

	const isDefault = (where: Where) => GESTURES.every(([g]) => gestures[where][g] === DEFAULTS[where][g]);
</script>

<div class="flex flex-col gap-8">
	{#each groups as [where, title, hint] (where)}
		<section class="flex flex-col gap-3">
			<div class="flex items-end gap-2">
				<div class="min-w-0 flex-1">
					<p class="text-sm font-medium">{title}</p>
					<p class="text-xs text-muted">{hint}</p>
				</div>
				<DeviceToggle setting={gestureSetting(where)} label={title} />
				{#if !isDefault(where)}
					<button class="shrink-0 rounded-full px-3 py-1 text-xs text-muted hover:bg-raised hover:text-white" onclick={() => resetGestures(where)}>Defaults</button>
				{/if}
			</div>
			{#each GESTURES as [gesture, label] (gesture)}
				<label class="flex items-center gap-4">
					<span class="min-w-0 flex-1 text-sm">{label}</span>
					<select
						class="rounded-lg bg-raised px-3 py-2 text-sm outline-none"
						value={gestures[where][gesture]}
						onchange={(e) => setGesture(where, gesture, e.currentTarget.value as QueueAction)}
					>
						{#each ACTIONS[where] as action (action)}
							<option value={action}>{ACTION_NAMES[action]}{action === DEFAULTS[where][gesture] ? ' (default)' : ''}</option>
						{/each}
					</select>
				</label>
			{/each}
			<DeviceNote setting={gestureSetting(where)} />
		</section>
	{/each}
	<Choice
		label="Swipe to skip"
		hint="Swipe the player sideways: left plays the next song, right goes back, like YouTube Music."
		setting={settings.swipeSkips}
		options={[
			['both', 'Mini player and art'],
			['mini', 'Mini player'],
			['art', 'Art in the player'],
			['off', 'Off']
		] as [SwipeSkips, string][]}
	/>
	<Toggle
		label="Bigger buttons"
		hint="On a touch screen, small buttons like ⋮, the drag handle and the ones at the top get a finger-sized tap area (48 pixels). The icons stay the same size."
		setting={settings.bigTaps}
	/>
	<Toggle
		label="⋮ on songs"
		hint="On a touch screen, songs show their ⋮ button. Off: hold a song for its menu (where Hold opens the menu; elsewhere the ⋮ stays)."
		setting={settings.songMoreButton}
	/>
	<Toggle label="Holding doesn't select text" hint="On a touch screen, so holding a song or button never selects its words. Text fields can still be selected." setting={settings.holdNoSelect} />
	<p class="text-xs text-muted">
		Swipes are for touch screens. A right-click (or ⋮) always opens the song menu, also when Hold does something else.
	</p>
</div>

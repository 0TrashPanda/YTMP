<script lang="ts">
	// The equalizer (Settings → Playback, where YouTube Music has it): on or off, a preset, and
	// ten bands. In the app it's for what the phone plays on now (each of your headphones has its own).
	import { EQ_BANDS, EQ_MAX_DB, EQ_PRESETS, equalizer } from '../../equalizer.svelte';
	import { nativeBridge } from '../../native';
	import Switch from './Switch.svelte';

	const settings = $derived(equalizer.settings);

	function label(hz: number): string {
		return hz >= 1000 ? `${hz / 1000}k` : String(hz);
	}

	function db(value: number): string {
		return value > 0 ? `+${value}` : String(value);
	}
</script>

<div class="flex flex-col gap-3">
	<div class="flex items-center gap-3">
		<div class="flex-1">
			<p class="text-sm font-medium">Equalizer</p>
			<p class="text-xs text-muted">
				{#if nativeBridge}
					For <strong class="font-medium text-white">{equalizer.output?.name ?? 'this phone'}</strong>. The phone's speaker, wired headphones and each
					Bluetooth device keep their own.
				{:else}
					For this browser, when it plays the room.
				{/if}
				Speakers and TVs under <em>Play on</em> don't change.
			</p>
		</div>
		<Switch checked={settings.on} label="Equalizer" onchange={(on) => equalizer.setOn(on)} />
	</div>

	{#if settings.on}
		<div class="flex flex-wrap gap-2" role="radiogroup" aria-label="Preset">
			{#each EQ_PRESETS as preset (preset.id)}
				<button
					class="rounded-lg px-3 py-1.5 text-sm {settings.preset === preset.id ? 'bg-white text-black' : 'bg-raised hover:bg-line'}"
					role="radio"
					aria-checked={settings.preset === preset.id}
					onclick={() => equalizer.usePreset(preset.id)}>{preset.name}</button
				>
			{/each}
			{#if settings.preset === null}
				<span class="rounded-lg bg-white px-3 py-1.5 text-sm text-black">Custom</span>
			{/if}
		</div>

		<div class="grid grid-cols-10 gap-1 rounded-xl bg-raised px-1 py-3">
			{#each EQ_BANDS as hz, i (hz)}
				<div class="flex min-w-0 flex-col items-center gap-2">
					<span class="text-[11px] tabular-nums text-muted">{db(settings.gains[i])}</span>
					<input
						type="range"
						class="h-36 w-6 accent-white [direction:rtl] [writing-mode:vertical-lr]"
						min={-EQ_MAX_DB}
						max={EQ_MAX_DB}
						step="1"
						value={settings.gains[i]}
						aria-label="{label(hz)}Hz"
						aria-valuetext="{db(settings.gains[i])} dB"
						oninput={(e) => equalizer.setBand(i, Number(e.currentTarget.value))}
					/>
					<span class="text-[11px] text-muted">{label(hz)}</span>
				</div>
			{/each}
		</div>
		<p class="text-xs text-muted">
			Hz along the bottom, dB up and down. Boosting turns the rest down a little, so it doesn't distort.
			{#if settings.preset !== 'flat'}
				<button class="underline" onclick={() => equalizer.usePreset('flat')}>Reset</button>
			{/if}
		</p>
	{/if}
</div>

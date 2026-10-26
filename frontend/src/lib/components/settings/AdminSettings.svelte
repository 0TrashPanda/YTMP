<script lang="ts">
	// Server admin → Settings: the server settings that can change while it runs. They start
	// as in ytmp.toml; what's changed here is saved on the server and wins (Reset: ytmp.toml's again).
	import { authServer } from '../../account';
	import type { AdminSettings, ServerSettingOverrides, ServerSettingValues, SignupMode } from '../../protocol.gen';
	import NumberField from '../NumberField.svelte';
	import CacheSection from './CacheSection.svelte';
	import CrawlSection from './CrawlSection.svelte';
	import GraphBuildBar from '../GraphBuildBar.svelte';
	import Choice from './Choice.svelte';
	import Switch from './Switch.svelte';

	let { onToast }: { onToast: (text: string) => void } = $props();

	let settings = $state<AdminSettings | null>(null);
	let busy = $state(false);
	let newTrusted = $state('');

	const GB = 1024 ** 3;

	function show(next: AdminSettings) {
		settings = next;
	}

	authServer
		.serverSettings()
		.then(show)
		.catch((e) => onToast(e instanceof Error ? e.message : "Couldn't load the server settings"));

	/** Saves one setting (null: back to ytmp.toml's). */
	async function set<K extends keyof ServerSettingOverrides>(key: K, value: ServerSettingOverrides[K]) {
		if (!settings) return;
		busy = true;
		try {
			show(await authServer.saveServerSettings({ ...settings.overrides, [key]: value }));
		} catch (e) {
			onToast(e instanceof Error ? e.message : "Couldn't save");
			if (settings) show(settings);
		} finally {
			busy = false;
		}
	}

	function addTrusted(event: SubmitEvent) {
		event.preventDefault();
		const url = newTrusted.trim().replace(/\/+$/, '');
		if (!settings || !url) return;
		set('trusted', [...settings.values.trusted, url]).then(() => (newTrusted = ''));
	}

	const describe: { [K in keyof ServerSettingValues]: (v: ServerSettingValues[K]) => string } = {
		signup: (v) => ({ open: 'open', invite: 'invite', admin: 'admin' })[v],
		guests: (v) => (v ? 'on' : 'off'),
		trackingDefault: (v) => (v ? 'on' : 'off'),
		trusted: (v) => (v.length ? v.join(', ') : 'none'),
		roomCodeStyle: (v) => v,
		roomCodeLength: (v) => String(v),
		audioProxy: (v) => v,
		cacheBytes: (v) => (v ? `${Math.round((v / GB) * 100) / 100} GB` : 'off'),
		logLevel: (v) => v,
		roomCreate: (v) => v
	};

	const signupOptions: [SignupMode, string][] = [
		['open', 'Anyone'],
		['invite', 'Invite link'],
		['admin', 'Admins only']
	];
	const createOptions: [string, string][] = [
		['everyone', 'Everyone'],
		['accounts', 'People with an account'],
		['admins', 'Server admins']
	];
	const styleOptions: [string, string][] = [
		['letters', 'Letters'],
		['digits', 'Digits'],
		['alnum', 'Both']
	];
	const proxyOptions: [string, string][] = [
		['always', 'Always'],
		['lan', 'Home network'],
		['never', 'Never']
	];
	const logOptions: [string, string][] = [
		['info', 'Normal'],
		['debug', 'Detailed']
	];

	const card = 'flex flex-col gap-4 rounded-xl bg-raised/60 p-4';
	const textInput = 'min-w-0 rounded-lg bg-raised px-3 py-2 outline-none ring-white/40 focus:ring-2';
</script>

<!-- Under a setting changed here: what ytmp.toml says, and Reset. -->
{#snippet fromFile(key: keyof ServerSettingValues)}
	{#if settings && settings.overrides[key] !== null}
		<p class="-mt-2 text-xs text-muted">
			Changed here (ytmp.toml: {describe[key](settings.file[key] as never)}) ·
			<button class="underline disabled:opacity-40" disabled={busy} onclick={() => set(key, null)}>Reset</button>
		</p>
	{/if}
{/snippet}

{#if settings}
	{@const v = settings.values}
	<section class={card}>
		<h3 class="font-bold">Accounts</h3>
		<Choice label="Who can sign up" hint="The first account is always allowed and becomes a server admin." options={signupOptions} value={v.signup} onchange={(x) => set('signup', x)} />
		{@render fromFile('signup')}
		<div class="flex items-center gap-3">
			<div class="flex-1">
				<p class="text-sm font-medium">Guests</p>
				<p class="text-xs text-muted">People without an account can use this server. Off: they only see the login page (speakers and TVs still play).</p>
			</div>
			<Switch checked={v.guests} disabled={busy} label="Guests" onchange={(on) => set('guests', on)} />
		</div>
		{@render fromFile('guests')}
		<div class="flex items-center gap-3">
			<div class="flex-1">
				<p class="text-sm font-medium">Listening history for new accounts</p>
				<p class="text-xs text-muted">New accounts keep what they listen to until they turn it off. Accounts that already exist keep their own choice.</p>
			</div>
			<Switch checked={v.trackingDefault} disabled={busy} label="Listening history for new accounts" onchange={(on) => set('trackingDefault', on)} />
		</div>
		{@render fromFile('trackingDefault')}
		<div class="flex flex-col gap-2">
			<div>
				<p class="text-sm font-medium">Trusted servers</p>
				<p class="text-xs text-muted">Other YTMP servers whose accounts can join rooms here.</p>
			</div>
			{#each v.trusted as url (url)}
				<div class="flex items-center gap-3 text-sm">
					<span class="min-w-0 flex-1 truncate font-mono">{url}</span>
					<button
						class="text-sm text-muted underline disabled:opacity-40"
						disabled={busy}
						onclick={() =>
							set(
								'trusted',
								v.trusted.filter((u) => u !== url)
							)}>Remove</button
					>
				</div>
			{/each}
			<form class="flex gap-2" onsubmit={addTrusted}>
				<input class="{textInput} flex-1 font-mono text-sm" bind:value={newTrusted} placeholder="https://ytmp.example.com" inputmode="url" autocapitalize="none" spellcheck="false" aria-label="Server address" />
				<button class="rounded-full bg-raised px-4 py-2 text-sm font-medium hover:bg-line disabled:opacity-40" disabled={busy || !newTrusted.trim()}>Add</button>
			</form>
		</div>
		{@render fromFile('trusted')}
	</section>

	<section class={card}>
		<h3 class="font-bold">Rooms</h3>
		<Choice label="Room codes" hint="What new rooms' codes are made of. Rooms that exist keep theirs." options={styleOptions} value={v.roomCodeStyle} onchange={(x) => set('roomCodeStyle', x)} />
		{@render fromFile('roomCodeStyle')}
		<div class="flex items-center gap-3">
			<div class="flex-1">
				<p class="text-sm font-medium">Code length</p>
				<p class="text-xs text-muted">3 to 8 characters.</p>
			</div>
			<NumberField value={v.roomCodeLength} min={3} max={8} label="Code length" disabled={busy} onchange={(n) => set('roomCodeLength', n)} />
		</div>
		{@render fromFile('roomCodeLength')}
		<Choice label="Who can create rooms" hint="Joining a room is never limited by this. Rooms that exist stay." options={createOptions} value={v.roomCreate} onchange={(x) => set('roomCreate', x)} />
		{@render fromFile('roomCreate')}
	</section>

	<section class={card}>
		<h3 class="font-bold">Playback</h3>
		<Choice
			label="Audio through the server"
			hint="For players whose direct YouTube link doesn't work. Home network: only devices at home (speakers, phones on the Wi-Fi). Never: Chromecast and Sonos can't play."
			options={proxyOptions}
			value={v.audioProxy}
			onchange={(x) => set('audioProxy', x)}
		/>
		{@render fromFile('audioProxy')}
	</section>

	<section class={card}>
		{#key v.cacheBytes}
			<CacheSection load={authServer.songCache} clear={authServer.clearSongCache} card {onToast} />
		{/key}
		<div class="flex items-center gap-3">
			<div class="flex-1">
				<p class="text-sm font-medium">Size</p>
				<p class="text-xs text-muted">At most this much; 0 turns it off. When it's full, the songs played longest ago go first.</p>
			</div>
			<NumberField value={v.cacheBytes / GB} min={0} max={10000} step={0.1} unit="GB" label="Song cache size in GB (0: off)" disabled={busy} onchange={(n) => set('cacheBytes', Math.round(n * GB))} />
		</div>
		{@render fromFile('cacheBytes')}
	</section>

	<section class={card}>
		<!-- The song graph being built again (a new version of the server builds it once). -->
		<GraphBuildBar load={authServer.graphStatus} />
		<CrawlSection
			load={authServer.radioCrawl}
			save={authServer.setRadioCrawl}
			card
			{onToast}
			hint="Slowly fetches YouTube Music's radio (without an account) of the server's local music that has a YouTube Music ID and of the songs in its cache, for the song graph: everyone's radios and On this device get better from it."
		/>
	</section>

	<section class={card}>
		<h3 class="font-bold">Logs</h3>
		<Choice label="Log detail" hint="Detailed writes much more, to find out why something goes wrong." options={logOptions} value={v.logLevel} onchange={(x) => set('logLevel', x)} />
		{@render fromFile('logLevel')}
	</section>

	<p class="px-1 text-xs text-muted">
		Changes work right away and win over ytmp.toml. The server's address, port, database and module key are only in ytmp.toml.
	</p>
{/if}

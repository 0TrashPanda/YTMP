import { describe, expect, it, vi } from 'vitest';

const setting = vi.hoisted(() => ({ value: true }));
vi.mock('./settings.svelte', () => ({ settings: { playStartsHere: setting } }));

import { play, type RoomPlayer } from './player.svelte';
import type { RoomConnection } from './room.svelte';

function fakes(options: { visibility?: string; alone?: boolean; enabled?: boolean; may?: boolean } = {}) {
	const sent: unknown[] = [];
	const room = {
		state: { room: { visibility: options.visibility ?? 'private' } },
		listeningAlone: options.alone ?? true,
		can: () => options.may ?? true,
		run: async (command: unknown) => {
			sent.push(command);
			return null;
		}
	} as unknown as RoomConnection;
	const player = { enabled: options.enabled ?? false, enable: vi.fn(async () => {}) } as unknown as RoomPlayer & { enable: ReturnType<typeof vi.fn> };
	return { room, player, sent };
}

describe('play', () => {
	it('plays here in a solo room where nothing plays it', async () => {
		const { room, player, sent } = fakes();
		await play(room, player);
		expect(player.enable).toHaveBeenCalled();
		expect(sent).toEqual([{ kind: 'Play' }]);
	});

	it('only plays the room in a party, while something plays it, or already playing here', async () => {
		for (const options of [{ visibility: 'public' }, { alone: false }, { enabled: true }, { may: false }]) {
			const { room, player, sent } = fakes(options);
			await play(room, player);
			expect(player.enable).not.toHaveBeenCalled();
			expect(sent).toEqual([{ kind: 'Play' }]);
		}
	});

	it('not with the setting off', async () => {
		setting.value = false;
		const { room, player } = fakes();
		await play(room, player);
		expect(player.enable).not.toHaveBeenCalled();
		setting.value = true;
	});
});

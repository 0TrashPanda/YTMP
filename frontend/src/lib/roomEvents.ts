// The room after an event: how the page keeps its copy of the room up to date between
// snapshots (docs/implementation/protocol.md). The same as RoomState.apply in
// protocol/.../RoomEvents.kt; roomEvents.test.ts checks that both get the same room.
import type { Event, RoomState } from './protocol.gen';

/** Applies [e] to [s] (in place). */
export function applyEvent(s: RoomState, e: Event): void {
	switch (e.kind) {
		case 'ParticipantJoined':
		case 'ParticipantUpdated': {
			const index = s.participants.findIndex((p) => p.id === e.participant.id);
			if (index >= 0) s.participants[index] = e.participant;
			else s.participants.push(e.participant);
			break;
		}
		case 'ParticipantLeft':
			s.participants = s.participants.filter((p) => p.id !== e.participantId);
			break;
		case 'QueueItemsAdded':
			s.queue.splice(e.index, 0, ...e.items);
			break;
		case 'QueueItemRemoved':
			s.queue = s.queue.filter((i) => i.itemId !== e.itemId);
			break;
		case 'QueueItemMoved': {
			const item = s.queue.find((i) => i.itemId === e.itemId);
			if (!item) break;
			s.queue = s.queue.filter((i) => i !== item);
			s.queue.splice(e.toIndex, 0, item);
			break;
		}
		case 'HistoryAppended':
			s.history.push(e.item);
			break;
		case 'HistoryItemRemoved':
			s.history = s.history.filter((i) => i.itemId !== e.itemId);
			break;
		case 'NowPlayingChanged':
			s.nowPlaying = e.item ? { item: e.item, streamUrl: null } : null;
			break;
		case 'StreamReady':
			if (s.nowPlaying?.item.itemId === e.itemId) s.nowPlaying.streamUrl = e.streamUrl;
			break;
		case 'PlaybackChanged':
			s.playback = e.playback;
			break;
		case 'OutputsChanged':
			s.outputs = e.outputs;
			break;
		case 'RoomUpdated':
			s.room = e.room;
			break;
		case 'QueueReplaced':
			s.queue = e.items;
			break;
		case 'HistoryReplaced':
			s.history = e.items;
			break;
		case 'AutoplayChanged':
			s.autoplay = e.items;
			s.autoplaySeed = e.seed;
			break;
		case 'RadioOptionsChanged':
			s.radioOptions = e.options;
			break;
		case 'RolesChanged':
			s.roles = e.roles;
			break;
		case 'SettingsChanged':
			s.settings = e.settings;
			break;
		case 'BansChanged':
			s.bans = e.bans;
			break;
		case 'Notice':
			// Shown, not kept.
			break;
		case 'MigrationChanged':
			s.migration = e.migration;
			break;
		case 'AdminlessChanged':
			s.adminless = e.adminless;
			break;
		case 'RepeatChanged':
			s.repeat = e.mode;
			break;
		case 'SkipVotesChanged':
			s.skipVotes = e.skipVotes;
			break;
		default: {
			// A new event kind that isn't handled here is a type error.
			const unhandled: never = e;
			void unhandled;
		}
	}
}

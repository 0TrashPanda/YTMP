import type { Participant } from './protocol.gen';

/**
 * Another device of yours switches "Play here" on this one on or off (Command.SetDeviceListening,
 * docs/features/playback-and-outputs.md#your-devices). Like DeviceVolume: it acts when
 * [Participant.listenRequests] goes up, not on what was asked before this page was there.
 */
export class ListenRequests {
	/** [Participant.listenRequests] as last seen, for [seenFor]. */
	private seen = 0;
	private seenFor: string | null = null;

	/** What to do now that [me] (this device in the room) changed: switch "Play here" on, off, or nothing. */
	update(me: Participant | null): 'on' | 'off' | null {
		if (!me) return null;
		if (this.seenFor !== me.id) {
			// The first time (also after a reload): what was asked before doesn't count.
			this.seenFor = me.id;
			this.seen = me.listenRequests;
			return null;
		}
		if (me.listenRequests === this.seen) return null;
		this.seen = me.listenRequests;
		return me.listenRequest ? 'on' : 'off';
	}
}

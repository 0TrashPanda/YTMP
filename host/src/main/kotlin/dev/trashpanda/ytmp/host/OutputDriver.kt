package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.OutputDevice
import dev.trashpanda.ytmp.core.OutputRoom
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/** One kind of speaker or TV the host can play a room on: [CastOutputs], [SonosOutputs], [MpdOutputs]. */
interface OutputDriver {
    /** The devices found or configured, as room outputs. */
    val devices: StateFlow<List<OutputDevice>>

    /**
     * Plays a room on these devices (the ones in its [dev.trashpanda.ytmp.core.RoomView.activeOutputs])
     * until the job is cancelled: a room on another host that this phone plays on speakers near it.
     */
    fun play(room: OutputRoom): Job
}

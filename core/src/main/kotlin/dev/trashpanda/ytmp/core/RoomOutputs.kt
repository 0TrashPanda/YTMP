package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.OutputInfo

/**
 * The speakers and TVs the host found ([available]), the ones a room plays on ([active], with
 * their wanted volume, null: as it is), and those showing its party screen ([screens]: output id ->
 * the screen participant whose page runs there). Only used with the room locked.
 */
internal class RoomOutputs {
    var available: List<OutputDevice> = emptyList()
        private set
    val active = LinkedHashMap<String, Double?>()
    val screens = HashMap<String, String>()

    /** The host found or lost devices: the room stops playing on the ones that are gone. Returns screens of gone outputs. */
    fun setAvailable(devices: List<OutputDevice>): Set<String> {
        available = devices
        active.keys.retainAll(devices.map { it.id }.toSet())
        return screens.keys - active.keys
    }

    fun device(id: String): OutputDevice? = available.firstOrNull { it.id == id }

    fun infos(): List<OutputInfo> = available.map {
        OutputInfo(it.id, it.name, it.kind, active = it.id in active, volume = active[it.id], screen = it.id in screens)
    }
}

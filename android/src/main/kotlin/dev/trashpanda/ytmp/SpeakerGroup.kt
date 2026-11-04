package dev.trashpanda.ytmp

/** A speaker or TV the room plays on: a host output, or one this phone plays on itself (nearby). */
data class Speaker(val id: String, val volume: Double?, val nearby: Boolean)

/**
 * The volume of all the speakers a room plays on as one, like a speaker group in Google Home:
 * its level is the loudest speaker's, and changing it scales every speaker by the same factor,
 * so the balance between them (set per speaker under *Play on*, also for speakers that are
 * louder at the same percentage) stays. At 0 the group remembers that balance for when it goes
 * up again.
 */
class SpeakerGroup {
    /** Each speaker's volume as a share of the loudest one, from the last time it wasn't all 0. */
    private var balance = emptyMap<String, Double>()

    /** The group's level (0–1), or null when no speaker says its volume. */
    fun level(speakers: List<Speaker>): Double? {
        val top = speakers.mapNotNull { it.volume }.maxOrNull() ?: return null
        if (top > 0) balance = speakers.associate { it.id to (it.volume ?: top) / top }
        return top
    }

    /** The volume for each speaker to put the group at [level] (0–1). */
    fun setTo(speakers: List<Speaker>, level: Double): Map<String, Double> {
        val wanted = level.coerceIn(0.0, 1.0)
        level(speakers)
        return speakers.associate { it.id to ((balance[it.id] ?: 1.0) * wanted).coerceIn(0.0, 1.0) }
    }
}

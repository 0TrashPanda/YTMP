package dev.trashpanda.ytmp.host

/**
 * A speaker or TV a room plays on is playing something else now (another app took it, e.g.
 * Spotify on a Sonos): the room lets go of it ([dev.trashpanda.ytmp.core.Room.outputTakenOver]).
 */
class OutputTakenOver(message: String) : Exception(message)

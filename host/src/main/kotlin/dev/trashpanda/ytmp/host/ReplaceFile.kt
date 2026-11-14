package dev.trashpanda.ytmp.host

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Moves this (a `.part` file just written) to [target], replacing what's there. `File.renameTo`
 * doesn't replace an existing file on Windows (the desktop app), where it then kept the old one.
 */
internal fun File.replace(target: File): Boolean = try {
    try {
        Files.move(toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    true
} catch (_: IOException) {
    false
}

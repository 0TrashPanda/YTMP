package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.protocol.BackupInfo
import java.io.File
import java.io.InputStream

/**
 * The admin page's backup and restore (docs/features/server-admin.md#backup): a copy of the
 * whole database to download, and an uploaded one that replaces it at the next start. SQLite only.
 */
class Backups(private val db: Database) {
    val supported: Boolean get() = db.sqlitePath != null

    /** A restore is waiting for the next start. */
    val restorePending: Boolean get() = db.sqlitePath?.let { Database.restoreFile(it).isFile } == true

    /** A copy of the database in a temporary file; the caller deletes it. */
    fun create(): File {
        val file = File.createTempFile("ytmp-backup", ".db")
        db.backupTo(file)
        return file
    }

    /** Checks the uploaded backup in [input] and keeps it for the next start. */
    fun stage(input: InputStream): BackupInfo {
        val path = db.sqlitePath ?: throw IllegalStateException("Restoring from here is for SQLite")
        val upload = File.createTempFile("ytmp-restore", ".db", File(path).absoluteFile.parentFile)
        try {
            upload.outputStream().use { out -> input.copyTo(out) }
            val info = Database.stageRestore(path, upload)
            return BackupInfo(info.accounts, info.rooms)
        } finally {
            upload.delete()
        }
    }
}

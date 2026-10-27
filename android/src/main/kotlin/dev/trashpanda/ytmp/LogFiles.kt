package dev.trashpanda.ytmp

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The app's log files (written by logback, see assets/logback.xml), to share when something goes wrong. */
object LogFiles {
    private fun dir(context: Context) = File(context.filesDir, "logs")

    /** YouTube pages the module couldn't read (ytm-module/ytmp_ytm/keep.py), sent along with the logs. */
    fun pagesDir(context: Context) = File(dir(context), "pages")

    /**
     * All log files in one text file, oldest first, with what's needed to read them (app
     * version, phone, Android version) at the top. The share sheet sends it where you choose.
     */
    fun share(context: Context): Intent {
        val files = dir(context).listFiles { file -> file.isFile && file.name.endsWith(".log") }.orEmpty()
            // ytmp.log is today's (the newest); the rolled ones are named by day and number.
            .sortedWith(compareBy<File> { it.name == "ytmp.log" }.thenBy { it.lastModified() })
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date())
        val out = File(File(context.cacheDir, "shared").apply { mkdirs() }, "ytmp-logs-$stamp.txt")
        File(context.cacheDir, "shared").listFiles()?.filter { it != out && it.name.startsWith("ytmp-logs-") }?.forEach { it.delete() }
        out.bufferedWriter().use { writer ->
            writer.appendLine("YTMP ${Updates.installedVersion(context)} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            writer.appendLine()
            if (files.isEmpty()) writer.appendLine("(no logs yet)")
            for (file in files) {
                writer.appendLine("===== ${file.name}")
                file.bufferedReader().use { it.copyTo(writer) }
            }
            // After the logs: one line of JSON each, for a test of that page.
            val pages = pagesDir(context).listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
            for (file in pages) {
                writer.appendLine()
                writer.appendLine("===== YouTube page ${file.name}")
                file.bufferedReader().use { it.copyTo(writer) }
                writer.appendLine()
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", out)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "YTMP logs $stamp")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Send YTMP logs")
    }
}

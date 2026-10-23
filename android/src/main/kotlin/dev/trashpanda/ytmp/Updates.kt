package dev.trashpanda.ytmp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * New versions of the app, from the GitHub releases (.github/workflows/release.yml builds one
 * signed APK per release). Checked when the app starts and from the settings; installing
 * goes through Android's own installer, which asks you first.
 */
object Updates {
    private const val TAG = "YtmpUpdates"
    private const val RELEASES = "https://api.github.com/repos/0TrashPanda/YTMP/releases?per_page=10"

    class Release(val version: String, val notes: String, val apkUrl: String) {
        val code = versionCode(version)
    }

    fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    private fun installedCode(context: Context): Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    /** Debug builds are signed with another key: a release can't update them. */
    fun isDebugBuild(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Like android/build.gradle.kts: 1.2.3 -> 10203. */
    fun versionCode(version: String): Long =
        (version.removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toLongOrNull() ?: 0 } + listOf(0L, 0L, 0L))
            .take(3).let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

    /** The newest release with an APK (pre-releases count: every 0.x release is one). */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val connection = URL(RELEASES).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != 200) throw IllegalStateException("GitHub answered ${connection.responseCode}")
            val releases = JSONArray(connection.inputStream.bufferedReader().readText())
            (0 until releases.length()).asSequence().map(releases::getJSONObject)
                .filter { !it.optBoolean("draft") }
                .mapNotNull { release ->
                    val assets = release.optJSONArray("assets") ?: return@mapNotNull null
                    val apk = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.optString("name").endsWith(".apk") }
                        ?: return@mapNotNull null
                    Release(release.getString("tag_name").removePrefix("v"), release.optString("body"), apk.getString("browser_download_url"))
                }
                .maxByOrNull { it.code }
        } finally {
            connection.disconnect()
        }
    }

    /** Whether [release] is newer than this app. */
    fun isNewer(context: Context, release: Release) = release.code > installedCode(context)

    /**
     * Asks to update to [release]: Update downloads and installs it. [fromStart]: offered by
     * itself, so it also offers to skip this version.
     */
    fun offer(activity: Activity, scope: CoroutineScope, release: Release, fromStart: Boolean) {
        val prefs = activity.getSharedPreferences("ytmp", Context.MODE_PRIVATE)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("YTMP ${release.version} is available")
            .setMessage("You have ${installedVersion(activity)}.${if (onMobileData(activity)) "\nYou're on mobile data: the update is a big download.\n" else ""}\n\n${plainNotes(release.notes)}".trimEnd())
            .setPositiveButton("Update") { _, _ -> download(activity, scope, release) }
            .setNegativeButton("Later", null)
        if (fromStart) dialog.setNeutralButton("Skip this version") { _, _ -> prefs.edit().putString(SKIPPED, release.version).apply() }
        dialog.show()
    }

    /** Connected through mobile data (a metered network). */
    private fun onMobileData(context: Context): Boolean {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** At app start: offers a new version, unless you skipped it. Not for debug builds. */
    fun checkOnStart(activity: Activity, scope: CoroutineScope) {
        if (isDebugBuild(activity)) return
        val skipped = activity.getSharedPreferences("ytmp", Context.MODE_PRIVATE).getString(SKIPPED, null)
        scope.launch {
            val release = runCatching { latest() }.onFailure { Log.i(TAG, "Couldn't check for updates: $it") }.getOrNull() ?: return@launch
            Log.i(TAG, "Newest release ${release.version}, this is ${installedVersion(activity)}")
            if (isNewer(activity, release) && release.version != skipped && !activity.isFinishing) offer(activity, scope, release, fromStart = true)
        }
    }

    /** From the settings: offers the new version, or says this is the newest. */
    fun checkNow(activity: Activity, scope: CoroutineScope) {
        scope.launch {
            val release = try {
                latest()
            } catch (e: Exception) {
                Log.i(TAG, "Couldn't check for updates: $e")
                AlertDialog.Builder(activity).setMessage("Couldn't check for updates: ${e.message}").setPositiveButton("OK", null).show()
                return@launch
            }
            if (activity.isFinishing) return@launch
            if (release != null && isNewer(activity, release)) {
                offer(activity, scope, release, fromStart = false)
            } else {
                val debug = if (isDebugBuild(activity)) "\n\nThis is a debug build: releases can't update it (another signing key)." else ""
                AlertDialog.Builder(activity)
                    .setMessage("You have the newest version (${installedVersion(activity)}).$debug")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /** Downloads the APK with a progress dialog, then hands it to Android's installer. */
    private fun download(activity: Activity, scope: CoroutineScope, release: Release) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val box = FrameLayout(activity).apply { setPadding(padding, padding / 2, padding, 0); addView(bar) }
        var job: Job? = null
        val progress = AlertDialog.Builder(activity)
            .setTitle("Downloading YTMP ${release.version}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> job?.cancel() }
            .show()
        job = scope.launch {
            val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, "ytmp-${release.version}.apk")
            try {
                withContext(Dispatchers.IO) {
                    val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 15_000
                        connection.readTimeout = 30_000
                        if (connection.responseCode != 200) throw IllegalStateException("GitHub answered ${connection.responseCode}")
                        val total = connection.contentLengthLong
                        connection.inputStream.use { input ->
                            apk.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    done += read
                                    if (total > 0) bar.post { bar.isIndeterminate = false; bar.progress = (done * 100 / total).toInt() }
                                }
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
                progress.dismiss()
                Log.i(TAG, "Downloaded ${release.version} (${apk.length() / 1024} KB), opening the installer")
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            } catch (e: Exception) {
                progress.dismiss()
                apk.delete()
                if (e is kotlinx.coroutines.CancellationException) return@launch
                Log.w(TAG, "Downloading ${release.version} failed: $e")
                AlertDialog.Builder(activity).setMessage("Downloading the update didn't work: ${e.message}").setPositiveButton("OK", null).show()
            }
        }
    }

    /** The release notes (Markdown) as plain text for a dialog: no heading marks or bold, bullets as •, without the install part. */
    fun plainNotes(markdown: String): String {
        // The Install section (download the APK below…) and the changelog link are for the release page.
        val lines = markdown.lines().takeWhile { line ->
            !Regex("^#+\\s*Install", RegexOption.IGNORE_CASE).containsMatchIn(line) && !line.trimStart('*', ' ').startsWith("Full Changelog")
        }
        val text = lines.joinToString("\n") { line ->
            line.replace(Regex("^#+\\s*"), "").replace(Regex("^\\s*[-*]\\s+"), "• ").replace("**", "").replace(Regex("`"), "")
        }.replace(Regex("\n{3,}"), "\n\n").trim()
        return if (text.length > MAX_NOTES) text.take(MAX_NOTES).substringBeforeLast('\n') + "\n…" else text
    }

    private const val SKIPPED = "update.skipped"
    private const val MAX_NOTES = 1500
}

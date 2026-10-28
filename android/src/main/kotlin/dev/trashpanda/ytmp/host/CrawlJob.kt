package dev.trashpanda.ytmp.host

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.trashpanda.ytmp.YtmpApp

/**
 * Learning radios overnight (*Settings → Radio → Learn radios*, docs/features/history-and-recommendations.md#learning-from-youtube-music):
 * without it, the crawl only runs while the app is awake. Android runs this job while the phone
 * charges on Wi-Fi, every 15 minutes, and lets it run up to 10; for that long the app stays
 * awake and the crawl ([RadioCrawl], woken with [RadioCrawl.nudge]) goes on at its own pace.
 */
class CrawlJob : JobService() {
    private val handler = Handler(Looper.getMainLooper())
    private var done: Runnable? = null

    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(TAG, "Learning radios in the background for a while")
        (application as YtmpApp).host.crawlAwake()
        // Before Android's 10 minutes are over: finished, the next run comes with the period.
        val end = Runnable { jobFinished(params, false) }
        done = end
        handler.postDelayed(end, RUN_MS)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // Unplugged or off Wi-Fi: the next run comes when it charges on Wi-Fi again.
        done?.let(handler::removeCallbacks)
        done = null
        return false
    }

    companion object {
        private const val TAG = "CrawlJob"
        private const val JOB_ID = 4_711
        private const val PERIOD_MS = 15 * 60_000L
        private const val RUN_MS = 9 * 60_000L

        /** Runs while charging on Wi-Fi from now on ([on]), also after a restart of the phone, or not. */
        fun schedule(context: Context, on: Boolean) {
            val jobs = context.getSystemService(JobScheduler::class.java) ?: return
            if (!on) {
                jobs.cancel(JOB_ID)
                return
            }
            if (jobs.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, CrawlJob::class.java))
                .setRequiresCharging(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build()
            runCatching { jobs.schedule(job) }.onFailure { Log.w(TAG, "Couldn't plan learning radios overnight", it) }
        }
    }
}

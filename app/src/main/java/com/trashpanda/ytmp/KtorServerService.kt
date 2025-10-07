package com.trashpanda.ytmp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

class KtorServerService : Service() {

    val context = this

    private lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>

    private val onNotificationDismissedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        showNotification()
        startServer()
        registerReceiver(
            onNotificationDismissedReceiver,
            IntentFilter("DISMISSED_ACTION"),
            RECEIVER_NOT_EXPORTED // This is required on Android 14
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        unregisterReceiver(onNotificationDismissedReceiver)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        server = embeddedServer(CIO, port = 8080, host = "0.0.0.0") {
            routing {
                serveAssets(this@KtorServerService)
            }
        }.start(wait = false)
    }

    private fun stopServer() {
        server.stop(1000, 2000)
    }

    fun Routing.serveAssets(context: Context) {
        get("/web/static/{dir}/{file?}") {
            val dir = call.parameters["dir"]
            val file = call.parameters["file"]
            val fullPath = if (file == null) {
                "web/$dir"
            } else {
                "web/static/$dir/$file"
            }

            try {
                val stream = context.assets.open(fullPath)
                val bytes = stream.readBytes()
                val contentType = ContentType.defaultForFilePath(fullPath)
                call.respondBytes(bytes, contentType)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.NotFound, "Not found: $dir/$file")
            }
        }

        get("/") {
            call.respondRedirect("/web/static/index.html")
        }
    }

    // class NotificationReposter : BroadcastReceiver() {
    //     override fun onReceive(context: Context, intent: Intent?) {
    //         Log.d("NotificationReposter", "Received intent: ${intent?.action}")
    //         if (intent?.action == "com.trashpanda.ytmp.NOTIF_DISMISSED") {
    //             Log.d("NotificationReposter", "Reposting notification")
    //             val repostIntent = Intent(context, KtorServerService::class.java)
    //             context.startForegroundService(repostIntent)
    //         }
    //     }
    // }

    // private fun startForegroundServiceWithNotification() {
    //     val channelId = "ktor_server_channel"
    //     val channelName = "Ktor Server Notifications"
    //     val notificationId = 100

    //     val channel = NotificationChannel(
    //         channelId,
    //         channelName,
    //         NotificationManager.IMPORTANCE_DEFAULT
    //     ).apply {
    //         description = "Notification channel for Ktor server"
    //     }

    //     val manager = getSystemService(NotificationManager::class.java)
    //     manager.createNotificationChannel(channel)


    //     val deleteIntent = Intent(applicationContext, NotificationReposter::class.java).apply {
    //         action = "com.trashpanda.ytmp.NOTIF_DISMISSED"
    //     }
    //     val pendingDeleteIntent = PendingIntent.getBroadcast(
    //         applicationContext,
    //         0,
    //         deleteIntent,
    //         PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    //     )

    //     val notification = NotificationCompat.Builder(this, channelId)
    //         .setContentTitle("Ktor Server Running")
    //         .setContentText("Listening on port 8080")
    //         .setSmallIcon(android.R.drawable.stat_notify_sync)
    //         .setDeleteIntent(pendingDeleteIntent)
    //         .setOngoing(true)
    //         .build()

    //     ServiceCompat.startForeground(
    //         this,
    //         notificationId,
    //         notification,
    //         FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    //     )
    // }
    fun showNotification() {
        val notificationId = 100
        val channelId = "ktor_server_channel"
        val dismissedIntent = Intent("DISMISSED_ACTION")
        dismissedIntent.setPackage(packageName) // This is required on Android 14
        val dismissedPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            dismissedIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setChannelId(channelId)
            .setContentTitle("Ktor Server Running")
            .setContentText("Listening on port 8080")
            // .setSmallIcon(R.drawable.tracking_notification_animation)
            // .setOngoing(true)
            // .setContentIntent(contentIntent)
            .setDeleteIntent(dismissedPendingIntent)
            .build()

        startForeground(notificationId, notification)
    }
}

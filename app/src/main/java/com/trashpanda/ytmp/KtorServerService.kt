package com.trashpanda.ytmp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class KtorServerService : Service() {

    private lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        startServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        server = embeddedServer(CIO, port = 8080, host = "0.0.0.0") {
            routing {
                get("/") {
                    val time = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    call.respondText("Hello from Ktor! Time: $time")
                }
            }
        }.start(wait = false)
    }

    private fun stopServer() {
        server.stop(1000, 2000)
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "ktor_server_channel"
        val channelName = "Ktor Server Notifications"
        val notificationId = 100

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_DEFAULT // <-- More visible than LOW
            ).apply {
                description = "Notification channel for Ktor server"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Ktor Server Running")
            .setContentText("Listening on port 8080")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

            startForegroundService(Intent(this, KtorServerService::class.java))
            ServiceCompat.startForeground(this, notificationId, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        Log.d("Server", "Notification channel created")
    }

}

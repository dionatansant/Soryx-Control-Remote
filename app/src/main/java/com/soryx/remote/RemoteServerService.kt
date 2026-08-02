package com.soryx.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RemoteServerService : Service() {

    private var server: RemoteHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        server = RemoteHttpServer(applicationContext, PORT).also { it.start() }
        InputDaemonLauncher.ensureRunning(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "soryx_remote_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId,
                "Soryx Control Remote",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Soryx Control Remote")
            .setContentText("Servidor de controle remoto ativo na porta $PORT")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val PORT = 8080
        private const val NOTIFICATION_ID = 1
    }
}

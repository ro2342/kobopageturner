package com.rodcarvalho.kobopageturner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder

// Hosts BlePeripheralService as a foreground service (type "connectedDevice")
// instead of letting it live inside the Activity. A plain Activity-owned BLE
// GATT server is subject to Android's background execution limits the moment
// the screen locks or the app isn't in the foreground — exactly what a
// "turn pages on a separate e-reader without staring at your phone" app
// needs to survive. The real-device log showed the Kobo bonding, connecting,
// and reading the Report Map, then the connection repeatedly dropping and
// never reaching a subscribed state — consistent with this exact limitation.
class BleForegroundService : Service() {

    inner class LocalBinder : Binder() {
        val service: BleForegroundService get() = this@BleForegroundService
    }

    private val binder = LocalBinder()

    var onEvent: ((String) -> Unit)? = null

    val ble: BlePeripheralService by lazy {
        BlePeripheralService(applicationContext) { line -> onEvent?.invoke(line) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Kobo Page Turner", NotificationManager.IMPORTANCE_LOW)
        )

        val notification = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Kobo Page Turner")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        ble.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "kobo_page_turner_ble"
        private const val NOTIFICATION_ID = 1
    }
}

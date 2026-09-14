package com.tz.camerastreamerwebrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground-сервис, который удерживает CPU и Wi-Fi в активном состоянии
 * во время стриминга. Без него Android при заблокированном экране входит
 * в Doze-режим, замораживает сеть и убивает WebSocket / RTP-потоки.
 */
class StreamingService : Service() {

    private companion object {
        const val TAG = "StreamingService"
        const val CHANNEL_ID = "streaming_channel"
        const val NOTIFICATION_ID = 1
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        startForegroundNotification()
        Log.i(TAG, "Service created, locks acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: если система убьёт сервис, она попытается его перезапустить
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed, releasing locks")
        releaseLocks()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Камера — стриминг",
                NotificationManager.IMPORTANCE_LOW // Без звука, без вибрации
            ).apply {
                description = "Удерживает видеопоток активным в фоне"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Камера активна")
            .setContentText("Видеопоток передаётся…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true) // Нельзя смахнуть
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // На Android 13+ может упасть, если POST_NOTIFICATIONS не выдано
            Log.e(TAG, "startForeground failed: ${e.message}")
        }
    }

    private fun acquireLocks() {
        // 1. CPU WakeLock — не даёт процессору уснуть
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "App:StreamWakeLock"
        ).apply {
            setReferenceCounted(false)
            // Страховочный таймаут 30 минут. Реальное освобождение — в releaseLocks().
            acquire(30 * 60 * 1000L)
        }

        // 2. Wi-Fi Lock — не даёт Wi-Fi модулю перейти в режим экономии
        val wifiManager =
            applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "App:StreamWifiLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release error: ${e.message}")
        }

        try {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock release error: ${e.message}")
        }
    }
}
package com.tsproxy.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.tsproxy.android.R
import com.tsproxy.android.TsProxyApp
import com.tsproxy.android.ui.MainActivity
import tsproxy.Tsproxy

class TsProxyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val saved = loadSavedParams()
        when (intent?.action) {
            null -> {
                // Restarted by the system (START_STICKY): resume the last account.
                startProxy(saved.first, saved.second, saved.third)
            }
            ACTION_START -> {
                val socks = intent.getStringExtra(EXTRA_SOCKS) ?: saved.first
                val hostname = intent.getStringExtra(EXTRA_HOSTNAME) ?: saved.second
                val tsnetDir = intent.getStringExtra(EXTRA_TSNET_DIR) ?: saved.third
                startProxy(socks, hostname, tsnetDir)
            }
            ACTION_SWITCH -> {
                val socks = intent.getStringExtra(EXTRA_SOCKS) ?: saved.first
                val hostname = intent.getStringExtra(EXTRA_HOSTNAME) ?: saved.second
                val tsnetDir = intent.getStringExtra(EXTRA_TSNET_DIR) ?: saved.third
                switchProxy(socks, hostname, tsnetDir)
            }
            ACTION_STOP -> {
                stopProxy(startId)
            }
            ACTION_LOGOUT -> {
                logoutProxy(startId)
            }
        }
        return START_STICKY
    }

    private fun loadSavedParams(): Triple<String, String, String> {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        return Triple(
            p.getString(KEY_SOCKS, "127.0.0.1:1080") ?: "127.0.0.1:1080",
            p.getString(KEY_HOSTNAME, "ts-socks5") ?: "ts-socks5",
            p.getString(KEY_DIR, "") ?: ""
        )
    }

    private fun saveParams(socks: String, hostname: String, dir: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_SOCKS, socks)
            .putString(KEY_HOSTNAME, hostname)
            .putString(KEY_DIR, dir)
            .apply()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ts-proxy::keepalive"
            )
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours
            TsProxyApp.appendLog("WakeLock acquired")
        } catch (e: Exception) {
            TsProxyApp.appendLog("WakeLock failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    TsProxyApp.appendLog("WakeLock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            TsProxyApp.appendLog("WakeLock release error: ${e.message}")
        }
    }

    private fun startProxy(socks: String, hostname: String, tsnetDir: String) {
        startForeground(NOTIFICATION_ID, buildNotification("Starting Tailsocks5..."))
        acquireWakeLock()

        Thread({
            synchronized(opLock) {
                runStart(socks, hostname, tsnetDir)
            }
        }, "ts-proxy-start").start()
    }

    /**
     * Swap the tsnet state directory (i.e. the account). Tsproxy.Stop() must
     * finish before Start() can rebind the SOCKS port, so both run under one
     * lock on the same background thread.
     */
    private fun switchProxy(socks: String, hostname: String, tsnetDir: String) {
        startForeground(NOTIFICATION_ID, buildNotification("Switching account..."))
        acquireWakeLock()

        Thread({
            synchronized(opLock) {
                try {
                    Tsproxy.stop()
                    TsProxyApp.appendLog("switchProxy: old node stopped")
                } catch (e: Exception) {
                    TsProxyApp.appendLog("switchProxy stop EXCEPTION: ${e.message}")
                }
                runStart(socks, hostname, tsnetDir)
            }
        }, "ts-proxy-switch").start()
    }

    private fun runStart(socks: String, hostname: String, tsnetDir: String) {
        val resolvedDir = tsnetDir.ifEmpty {
            "${filesDir.absolutePath}/accounts/default"
        }
        saveParams(socks, hostname, resolvedDir)

        TsProxyApp.appendLog("startProxy: socks=$socks host=$hostname dir=$resolvedDir")

        try {
            val result = Tsproxy.start(socks, hostname, resolvedDir)
            val status = when {
                result.startsWith("ERROR:") -> "Failed: ${result.removePrefix("ERROR: ").take(80)}"
                else -> "Running on $socks"
            }
            TsProxyApp.appendLog("Tsproxy.start returned: $status")
            updateNotification(status)
        } catch (e: Exception) {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            TsProxyApp.appendLog("startProxy EXCEPTION: $sw")
            updateNotification("Crash: ${e.message?.take(80) ?: "unknown"}")
        }
    }

    private fun stopProxy(startId: Int) {
        Thread({
            synchronized(opLock) {
                try {
                    Tsproxy.stop()
                    TsProxyApp.appendLog("stopProxy: stopped")
                } catch (e: Exception) {
                    TsProxyApp.appendLog("stopProxy EXCEPTION: ${e.message}")
                }
            }
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }, "ts-proxy-stop").start()
    }

    /**
     * Sign the current account out. Go keeps the tsnet directory so the same
     * account slot can be re-authenticated later.
     */
    private fun logoutProxy(startId: Int) {
        Thread({
            synchronized(opLock) {
                try {
                    val err = Tsproxy.logout() ?: ""
                    TsProxyApp.appendLog("logout: ${err.ifBlank { "ok" }}")
                } catch (e: Exception) {
                    TsProxyApp.appendLog("logout EXCEPTION: ${e.message}")
                }
            }
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }, "ts-proxy-logout").start()
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, TsProxyApp.CHANNEL_ID)
            .setContentTitle("Tailsocks5")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to update notification", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Restart service when user swipes app from recents
        val restartIntent = Intent(applicationContext, TsProxyService::class.java).apply {
            action = ACTION_START
        }
        val pendingIntent = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(
            android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TsProxyService"
        const val ACTION_START = "com.tsproxy.android.START"
        const val ACTION_SWITCH = "com.tsproxy.android.SWITCH"
        const val ACTION_STOP = "com.tsproxy.android.STOP"
        const val ACTION_LOGOUT = "com.tsproxy.android.LOGOUT"
        const val EXTRA_SOCKS = "socks"
        const val EXTRA_HOSTNAME = "hostname"
        const val EXTRA_TSNET_DIR = "tsnet_dir"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS = "tsproxy_service"
        private const val KEY_SOCKS = "last_socks"
        private const val KEY_HOSTNAME = "last_hostname"
        private const val KEY_DIR = "last_dir"

        // Start/stop/switch all touch the Go singleton and the SOCKS port, so
        // they must never overlap.
        private val opLock = Any()

        fun start(context: Context, socks: String, hostname: String, tsnetDir: String) {
            val intent = Intent(context, TsProxyService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOCKS, socks)
                putExtra(EXTRA_HOSTNAME, hostname)
                putExtra(EXTRA_TSNET_DIR, tsnetDir)
            }
            context.startForegroundService(intent)
        }

        fun switchTo(context: Context, socks: String, hostname: String, tsnetDir: String) {
            val intent = Intent(context, TsProxyService::class.java).apply {
                action = ACTION_SWITCH
                putExtra(EXTRA_SOCKS, socks)
                putExtra(EXTRA_HOSTNAME, hostname)
                putExtra(EXTRA_TSNET_DIR, tsnetDir)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TsProxyService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun logout(context: Context) {
            val intent = Intent(context, TsProxyService::class.java).apply {
                action = ACTION_LOGOUT
            }
            context.startService(intent)
        }
    }
}

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
import com.tsproxy.android.TaildropPrefs
import com.tsproxy.android.TsProxyApp
import com.tsproxy.android.taildrop.TaildropDest
import com.tsproxy.android.taildrop.TaildropReceiver
import com.tsproxy.android.taildrop.WaitingFile
import com.tsproxy.android.taildrop.parseTransfers
import com.tsproxy.android.taildrop.parseWaitingFiles
import com.tsproxy.android.ui.MainActivity
import com.tsproxy.android.tsproxyDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import tsproxy.Tsproxy
import java.io.File

class TsProxyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    // True between requesting stop/logout and the node actually stopping; also
    // prevents onTaskRemoved from issuing a second shutdown.
    @Volatile
    private var sessionEnding = false

    // Latest onStartCommand id, so onTaskRemoved can stop this service.
    @Volatile
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
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
                logoutProxy(
                    startId,
                    intent.getStringExtra(EXTRA_TSNET_DIR) ?: "",
                    intent.getBooleanExtra(EXTRA_DELETE_DATA, false),
                    intent.getBooleanExtra(EXTRA_DELETE_TAILDROP, false)
                )
            }
            ACTION_SEND_FILES -> {
                val targetId = intent.getStringExtra(EXTRA_TARGET_ID) ?: ""
                val paths = intent.getStringArrayExtra(EXTRA_PATHS)?.toList() ?: emptyList()
                sendFiles(targetId, paths)
            }
        }
        return START_STICKY
    }

    private fun loadSavedParams(): Triple<String, String, String> = savedParams(this)

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
        sessionEnding = false
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
        sessionEnding = false
        stopTaildropWatcher()
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
            startTaildropWatcher()
        } catch (e: Exception) {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            TsProxyApp.appendLog("startProxy EXCEPTION: $sw")
            updateNotification("Crash: ${e.message?.take(80) ?: "unknown"}")
        }
    }

    private fun stopProxy(startId: Int) {
        sessionEnding = true
        stopTaildropWatcher()
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
    private fun logoutProxy(
        startId: Int,
        dir: String,
        deleteData: Boolean,
        deleteTaildropFiles: Boolean
    ) {
        sessionEnding = true
        stopTaildropWatcher()
        Thread({
            synchronized(opLock) {
                try {
                    val err = Tsproxy.logout() ?: ""
                    TsProxyApp.appendLog("logout: ${err.ifBlank { "ok" }}")
                } catch (e: Exception) {
                    TsProxyApp.appendLog("logout EXCEPTION: ${e.message}")
                }
            }
            // The node is stopped now; removing the account also removes its
            // device identity (tsnet state) and Taildrop staging directory.
            if (dir.isNotBlank()) {
                val target = File(dir)
                val withinApp = target.absolutePath
                    .startsWith(filesDir.absolutePath + File.separator)
                when {
                    !withinApp -> TsProxyApp.appendLog(
                        "logout: refusing to delete outside app dir: $dir"
                    )
                    deleteData -> {
                        var ok = target.deleteRecursively()
                        if (!ok) {
                            Thread.sleep(200)
                            ok = target.deleteRecursively()
                        }
                        TsProxyApp.appendLog("logout: delete dir=$dir ok=$ok")
                    }
                    deleteTaildropFiles -> {
                        val staging = File(target, "files")
                        val ok = staging.deleteRecursively()
                        TsProxyApp.appendLog("logout: delete taildrop staging=$staging ok=$ok")
                    }
                }
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_DIR).apply()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }, "ts-proxy-logout").start()
    }

    // ------------------------------------------------------------ taildrop

    @Volatile
    private var taildropWatcherRunning = false
    private var taildropThread: Thread? = null
    private val taildropNotified = HashSet<String>()
    // Files already saved by this service. Prevents a duplicate save when the
    // staging delete failed and the file is still listed on the next poll.
    private val taildropSaved = HashSet<String>()

    private fun startTaildropWatcher() {
        if (taildropWatcherRunning) return
        taildropWatcherRunning = true
        taildropThread = Thread({
            while (taildropWatcherRunning) {
                try {
                    pollTaildropOnce()
                } catch (t: Throwable) {
                    TsProxyApp.appendLog("taildrop watcher: ${t.message}")
                }
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "ts-taildrop").apply { start() }
    }

    private fun stopTaildropWatcher() {
        taildropWatcherRunning = false
        taildropThread?.interrupt()
        taildropThread = null
    }

    private data class ReceiveEvent(val name: String, val ok: Boolean, val detail: String)

    private val receiveLock = Any()
    private val receiveEvents = ArrayDeque<ReceiveEvent>()
    private var lastReceiveAt = 0L

    private fun pollTaildropOnce() {
        val prefs = runBlocking { applicationContext.tsproxyDataStore.data.first() }
        val enabled = prefs[TaildropPrefs.ENABLED] ?: true
        val dest = prefs[TaildropPrefs.DEST] ?: TaildropPrefs.DEST_MEDIA
        val (files, error) = parseWaitingFiles(Tsproxy.getWaitingFiles() ?: "{}")
        if (error.isNotBlank()) {
            TsProxyApp.appendLog("taildrop waiting: $error")
        }
        if (files.isEmpty()) {
            synchronized(taildropNotified) { taildropNotified.clear() }
            synchronized(taildropSaved) { taildropSaved.clear() }
            return
        }
        val currentKeys = files.map { "${it.name}:${it.size}" }.toSet()
        synchronized(taildropSaved) { taildropSaved.retainAll(currentKeys) }

        // Auto-save disabled: a single notification lists everything waiting.
        if (!enabled) {
            val fresh = files.filter { f ->
                synchronized(taildropNotified) { taildropNotified.add("${f.name}:${f.size}") }
            }
            if (fresh.isNotEmpty()) postPendingNotification(files)
            return
        }
        // Android 8/9: without the storage permission nothing can be saved.
        if (!TaildropPrefs.isTree(dest) && TaildropDest.legacyPermissionNeeded(this)) {
            val fresh = files.filter { f ->
                synchronized(taildropNotified) { taildropNotified.add("${f.name}:${f.size}") }
            }
            if (fresh.isNotEmpty()) {
                postTaildropNotification(
                    "无法保存文件",
                    "需要存储权限才能写入 ${TaildropDest.describe(this, dest)}",
                    NOTIFICATION_TAILDROP_RECEIVE,
                    openTaildrop = true,
                    style = null
                )
            }
            return
        }

        for (file in files) {
            val key = "${file.name}:${file.size}"
            if (synchronized(taildropSaved) { key in taildropSaved }) continue

            val result = runBlocking { TaildropReceiver.process(this@TsProxyService, dest, file) }
            if (result.ok) {
                synchronized(taildropNotified) { taildropNotified.remove(key) }
                synchronized(taildropSaved) { taildropSaved.add(key) }
                val saved = result.saved
                if (saved != null) {
                    postReceiveEvent(saved.displayName, true, dest)
                }
            } else if (synchronized(taildropNotified) { taildropNotified.add(key) }) {
                postReceiveEvent(file.name, false, dest, result.error)
            }
        }
    }

    private fun postPendingNotification(files: List<WaitingFile>) {
        val title = "收到 ${files.size} 个文件"
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        files.takeLast(6).forEach { style.addLine(it.name) }
        postTaildropNotification(
            title,
            "点击打开并保存",
            NOTIFICATION_TAILDROP_RECEIVE,
            openTaildrop = true,
            style = style
        )
    }

    /** Groups consecutive receives into one notification instead of one per file. */
    private fun postReceiveEvent(name: String, ok: Boolean, dest: String, error: String = "") {
        synchronized(receiveLock) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (receiveEvents.isNotEmpty() && now - lastReceiveAt > 15_000L) {
                receiveEvents.clear()
            }
            lastReceiveAt = now
            receiveEvents.addLast(ReceiveEvent(name, ok, error))
            while (receiveEvents.size > 8) receiveEvents.removeFirst()

            val total = receiveEvents.size
            val failed = receiveEvents.count { !it.ok }
            val title = when {
                failed == 0 -> "文件已接收"
                failed == total -> "接收失败"
                else -> "接收完成（${total - failed} 成功 / $failed 失败）"
            }
            val text = if (failed == 0) {
                "共 $total 个文件 · 保存到 ${TaildropDest.describe(this, dest)}"
            } else {
                "共 $total 个文件"
            }
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
            receiveEvents.takeLast(6).forEach { event ->
                if (event.ok) {
                    style.addLine(event.name)
                } else {
                    style.addLine("${event.name}：${event.detail.take(50)}")
                }
            }
            postTaildropNotification(
                title,
                text,
                NOTIFICATION_TAILDROP_RECEIVE,
                openTaildrop = true,
                style = style
            )
        }
    }

    private fun sendFiles(targetId: String, paths: List<String>) {
        if (targetId.isBlank() || paths.isEmpty()) {
            notifyTaildrop("发送失败", "没有可发送的文件", NOTIFICATION_TAILDROP_SEND, openTaildrop = true)
            return
        }
        Thread({
            Tsproxy.resetTransfers()
            val err = Tsproxy.sendFiles(targetId, org.json.JSONArray(paths).toString()) ?: ""
            if (err.isNotBlank()) {
                notifyTaildrop("发送失败", err.removePrefix("ERROR: "), NOTIFICATION_TAILDROP_SEND, openTaildrop = true)
            } else {
                notifyTaildrop("正在发送", "${paths.size} 个文件", NOTIFICATION_TAILDROP_SEND, openTaildrop = false)
                while (true) {
                    val transfers = parseTransfers(Tsproxy.getTransfers() ?: "{}")
                    val job = transfers.jobs.lastOrNull()
                    if (!transfers.active) {
                        val failed = transfers.jobs.firstOrNull { it.error.isNotBlank() }
                        if (failed != null) {
                            notifyTaildrop(
                                "发送失败",
                                "${failed.name}：${failed.error}",
                                NOTIFICATION_TAILDROP_SEND,
                                openTaildrop = true
                            )
                        } else {
                            notifyTaildrop(
                                "发送完成",
                                "${transfers.jobs.count { it.done }} 个文件已发送",
                                NOTIFICATION_TAILDROP_SEND,
                                openTaildrop = true
                            )
                        }
                        break
                    }
                    if (job != null && job.total > 0) {
                        val pct = (job.sent * 100 / job.total).toInt()
                        notifyTaildrop(
                            "正在发送",
                            "${job.name} · $pct%",
                            NOTIFICATION_TAILDROP_SEND,
                            openTaildrop = false
                        )
                    }
                    try {
                        Thread.sleep(800)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                // The UI may be gone; clean up the cache copies here too.
                paths.forEach { runCatching { java.io.File(it).delete() } }
            }
        }, "ts-taildrop-send").start()
    }

    private fun notifyTaildrop(title: String, text: String, id: Int, openTaildrop: Boolean) {
        postTaildropNotification(title, text, id, openTaildrop, style = null)
    }

    private fun postTaildropNotification(
        title: String,
        text: String,
        id: Int,
        openTaildrop: Boolean,
        style: NotificationCompat.Style?
    ) {
        try {
            val tap = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (openTaildrop) putExtra(MainActivity.EXTRA_OPEN_TAILDROP, true)
            }
            val pi = PendingIntent.getActivity(
                this, id, tap,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(this, TsProxyApp.TAILDROP_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_vpn_key)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            if (style != null) builder.setStyle(style)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(id, builder.build())
        } catch (t: Throwable) {
            TsProxyApp.appendLog("taildrop notify failed: ${t.message}")
        }
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
        // Removing the task is an explicit "close": stop the proxy instead of
        // resurrecting it. Otherwise the service restarts itself from the
        // background, the proxy keeps running, and the Quick Settings tile
        // rightly-but-confusingly keeps showing 运行中 after the app is gone.
        if (!sessionEnding) {
            TsProxyApp.appendLog("onTaskRemoved: stopping proxy")
            stopProxy(lastStartId)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopTaildropWatcher()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TsProxyService"
        const val ACTION_START = "com.tsproxy.android.START"
        const val ACTION_SWITCH = "com.tsproxy.android.SWITCH"
        const val ACTION_STOP = "com.tsproxy.android.STOP"
        const val ACTION_LOGOUT = "com.tsproxy.android.LOGOUT"
        const val ACTION_SEND_FILES = "com.tsproxy.android.SEND_FILES"
        const val EXTRA_SOCKS = "socks"
        const val EXTRA_HOSTNAME = "hostname"
        const val EXTRA_TSNET_DIR = "tsnet_dir"
        const val EXTRA_TARGET_ID = "target_id"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_DELETE_DATA = "delete_data"
        const val EXTRA_DELETE_TAILDROP = "delete_taildrop"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_TAILDROP_RECEIVE = 3001
        private const val NOTIFICATION_TAILDROP_SEND = 2002
        private const val NOTIFICATION_TAILDROP_ERROR = 2001
        private const val PREFS = "tsproxy_service"
        private const val KEY_SOCKS = "last_socks"
        private const val KEY_HOSTNAME = "last_hostname"
        private const val KEY_DIR = "last_dir"

        // Start/stop/switch all touch the Go singleton and the SOCKS port, so
        // they must never overlap.
        private val opLock = Any()

        private fun servicePrefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun savedParams(context: Context): Triple<String, String, String> {
            val p = servicePrefs(context)
            return Triple(
                p.getString(KEY_SOCKS, "127.0.0.1:1080") ?: "127.0.0.1:1080",
                p.getString(KEY_HOSTNAME, "ts-socks5") ?: "ts-socks5",
                p.getString(KEY_DIR, "") ?: ""
            )
        }

        /**
         * Starts the last account without opening the UI. Used by the Quick
         * Settings tile; returns false when there is no account to resume.
         */
        fun startFromSaved(context: Context): Boolean {
            val (socks, hostname, dir) = savedParams(context)
            if (dir.isBlank() || !File(dir).isDirectory) return false
            start(context, socks, hostname, dir)
            return true
        }

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

        /** Keeps START_STICKY resumption in sync with a hostname edited while authorizing. */
        fun updateSavedHostname(context: Context, hostname: String) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_HOSTNAME, hostname)
                .apply()
        }

        fun logout(
            context: Context,
            tsnetDir: String,
            deleteData: Boolean,
            deleteTaildropFiles: Boolean
        ) {
            val intent = Intent(context, TsProxyService::class.java).apply {
                action = ACTION_LOGOUT
                putExtra(EXTRA_TSNET_DIR, tsnetDir)
                putExtra(EXTRA_DELETE_DATA, deleteData)
                putExtra(EXTRA_DELETE_TAILDROP, deleteTaildropFiles)
            }
            context.startService(intent)
        }

        /** Hands prepared cache files to the running service for delivery. */
        fun sendFiles(context: Context, targetId: String, paths: List<String>) {
            val intent = Intent(context, TsProxyService::class.java).apply {
                action = ACTION_SEND_FILES
                putExtra(EXTRA_TARGET_ID, targetId)
                putExtra(EXTRA_PATHS, paths.toTypedArray())
            }
            context.startService(intent)
        }
    }
}

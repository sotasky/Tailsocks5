package com.tsproxy.android.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tsproxy.android.R
import com.tsproxy.android.ui.MainActivity
import tsproxy.Tsproxy

/**
 * 快捷设置磁贴（通知栏下拉顶部的开关）：一键启动/停止本地代理。
 *
 * 这里刻意不声明 ACTIVE_TILE：声明后系统只在点击时绑定磁贴，展开快捷面板
 * 时不会重新读取状态，App 进程被杀后磁贴会永远停在最后一次的「运行中」。
 * 普通磁贴每次展开面板都会回调 [onStartListening]，这里直接读 Go 的
 * [Tsproxy.isRunning]（与 App 界面同源），进程已死时必然是 false。
 *
 * 面板保持展开时用 1 秒轮询补上 tsnet 启动完成 / 停止的瞬间。
 */
class TsProxyTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var watchThread: Thread? = null

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_dots)
        tile.label = getString(R.string.app_name)
        render(tile, running = isProxyRunning())
        startWatching()
    }

    override fun onStopListening() {
        stopWatching()
        super.onStopListening()
    }

    override fun onDestroy() {
        stopWatching()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        if (isProxyRunning()) {
            TsProxyService.stop(this)
            render(tile, running = false)
        } else if (TsProxyService.startFromSaved(this)) {
            // 启动是异步的，轮询会在 tsnet 真正 running 后点亮磁贴。
            render(tile, running = false)
        } else {
            // 还没有启动过任何账号，打开 App 让用户登录或手动启动。
            openApp()
        }
    }

    private fun startWatching() {
        if (watchThread != null) return
        watchThread = Thread({
            var last: Boolean? = null
            while (watchThread === Thread.currentThread()) {
                val running = isProxyRunning()
                if (running != last) {
                    last = running
                    mainHandler.post {
                        runCatching { qsTile?.let { render(it, running) } }
                    }
                }
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "ts-tile-state").apply { start() }
    }

    private fun stopWatching() {
        val thread = watchThread ?: return
        watchThread = null
        thread.interrupt()
    }

    private fun isProxyRunning(): Boolean =
        runCatching { Tsproxy.isRunning() }.getOrDefault(false)

    private fun render(tile: Tile, running: Boolean) {
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        updateSubtitle(tile, if (running) "运行中" else "已停止")
    }

    private fun updateSubtitle(tile: Tile, subtitle: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = subtitle
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

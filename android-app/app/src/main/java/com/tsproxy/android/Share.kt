package com.tsproxy.android

import android.content.Context
import android.net.Uri
import android.os.StatFs
import com.tsproxy.android.taildrop.guessMime
import com.tsproxy.android.taildrop.queryDisplayName
import com.tsproxy.android.taildrop.querySize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A file share received from another app (ACTION_SEND / ACTION_SEND_MULTIPLE). */
data class ShareRequest(val uris: List<Uri>, val mime: String?)

object ShareBus {
    private val _request = MutableStateFlow<ShareRequest?>(null)
    val request: StateFlow<ShareRequest?> = _request.asStateFlow()

    /** Set by a Taildrop notification tap; consumed by MainScreen. */
    val openTaildrop = MutableStateFlow(false)

    fun post(request: ShareRequest) {
        _request.value = request
    }

    fun clear() {
        _request.value = null
    }
}

object SharePreparer {
    data class Prepared(val file: File, val displayName: String, val mime: String)

    /** Copies shared content URIs into the cache while the activity still holds the grant. */
    suspend fun copyToCache(ctx: Context, request: ShareRequest): List<Prepared> =
        withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "taildrop/out")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建缓存目录")
            val declared = request.uris.sumOf { querySize(ctx, it) }
            if (declared > 0) ensureCacheSpace(ctx, declared)
            val out = ArrayList<Prepared>(request.uris.size)
            for (uri in request.uris) {
                val rawName = queryDisplayName(ctx, uri)
                    ?: uri.lastPathSegment
                    ?: "shared-${System.currentTimeMillis()}"
                val name = File(rawName).name.ifBlank { "shared.bin" }
                val target = uniqueFile(dir, name)
                val mime = ctx.contentResolver.getType(uri)
                    ?: guessMime(name)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                } ?: throw IOException("无法读取分享的文件：$name")
                out.add(Prepared(target, name, mime))
            }
            out
        }

    fun ensureCacheSpace(ctx: Context, bytes: Long) {
        val stat = StatFs(ctx.cacheDir.absolutePath)
        if (stat.availableBytes < bytes + 16L * 1024 * 1024) {
            throw IOException("缓存空间不足")
        }
    }

    fun cleanup(ctx: Context) {
        runCatching { File(ctx.cacheDir, "taildrop/out").deleteRecursively() }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var target = File(dir, name)
        val dot = name.lastIndexOf('.')
        val base = if (dot <= 0) name else name.substring(0, dot)
        val ext = if (dot <= 0) "" else name.substring(dot)
        var i = 1
        while (target.exists()) {
            target = File(dir, "$base ($i)$ext")
            i++
        }
        return target
    }
}

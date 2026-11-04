package com.tsproxy.android.taildrop

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.tsproxy.android.TaildropPrefs
import com.tsproxy.android.TsProxyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tsproxy.Tsproxy
import java.io.File
import java.io.IOException

const val TAILDROP_DIR_NAME = "Tailsocks5"

data class WaitingFile(val name: String, val size: Long)

data class FileTarget(
    val id: String,
    val name: String,
    val dnsName: String,
    val os: String,
    val online: Boolean
)

data class TaildropInfo(
    val running: Boolean = false,
    val loggedIn: Boolean = false,
    val adminEnabled: Boolean = false,
    val backendState: String = "",
    val dir: String = "",
    val error: String = ""
)

data class TransferJob(
    val name: String = "",
    val total: Long = 0,
    val sent: Long = 0,
    val done: Boolean = false,
    val error: String = ""
)

data class Transfers(
    val active: Boolean = false,
    val jobs: List<TransferJob> = emptyList()
)

data class TaildropEntry(
    val name: String,
    val savedName: String,
    val size: Long,
    val uri: String,
    val accountEmail: String,
    val accountDir: String,
    val savedAt: Long,
    val status: String, // saved | failed
    val error: String = ""
)

// ---------------------------------------------------------------- JSON

fun parseWaitingFiles(json: String): Pair<List<WaitingFile>, String> {
    return try {
        val o = JSONObject(json)
        val arr = o.optJSONArray("files") ?: JSONArray()
        val files = (0 until arr.length()).mapNotNull { i ->
            val f = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = f.optString("name")
            if (name.isBlank()) null else WaitingFile(name, f.optLong("size"))
        }
        files to o.optString("error", "")
    } catch (_: Exception) {
        emptyList<WaitingFile>() to ""
    }
}

fun parseFileTargets(json: String): Pair<List<FileTarget>, String> {
    return try {
        val o = JSONObject(json)
        val arr = o.optJSONArray("targets") ?: JSONArray()
        val targets = (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = t.optString("id")
            if (id.isBlank()) null else FileTarget(
                id = id,
                name = t.optString("name").ifBlank { t.optString("dnsName") },
                dnsName = t.optString("dnsName"),
                os = t.optString("os"),
                online = t.optBoolean("online")
            )
        }
        targets to o.optString("error", "")
    } catch (_: Exception) {
        emptyList<FileTarget>() to ""
    }
}

fun parseTaildropInfo(json: String): TaildropInfo {
    return try {
        val o = JSONObject(json)
        TaildropInfo(
            running = o.optBoolean("running"),
            loggedIn = o.optBoolean("loggedIn"),
            adminEnabled = o.optBoolean("adminEnabled"),
            backendState = o.optString("backendState"),
            dir = o.optString("dir"),
            error = o.optString("error")
        )
    } catch (_: Exception) {
        TaildropInfo()
    }
}

fun parseTransfers(json: String): Transfers {
    return try {
        val o = JSONObject(json)
        val arr = o.optJSONArray("jobs") ?: JSONArray()
        val jobs = (0 until arr.length()).mapNotNull { i ->
            val j = arr.optJSONObject(i) ?: return@mapNotNull null
            TransferJob(
                name = j.optString("name"),
                total = j.optLong("total"),
                sent = j.optLong("sent"),
                done = j.optBoolean("done"),
                error = j.optString("error")
            )
        }
        Transfers(o.optBoolean("active"), jobs)
    } catch (_: Exception) {
        Transfers()
    }
}

// --------------------------------------------------------------- history

object TaildropHistory {
    private val _entries = MutableStateFlow<List<TaildropEntry>>(emptyList())
    val entries: StateFlow<List<TaildropEntry>> = _entries.asStateFlow()

    private var file: File? = null
    private var initialized = false
    private val lock = Any()
    private const val MAX_ENTRIES = 500

    fun init(ctx: Context) {
        synchronized(lock) {
            if (initialized) return
            val f = File(ctx.filesDir, "taildrop_history.json")
            file = f
            _entries.value = read(f)
            initialized = true
        }
    }

    fun refresh(ctx: Context) {
        synchronized(lock) {
            val f = file ?: File(ctx.filesDir, "taildrop_history.json").also { file = it }
            _entries.value = read(f)
        }
    }

    fun add(ctx: Context, entry: TaildropEntry) {
        synchronized(lock) {
            val f = file ?: File(ctx.filesDir, "taildrop_history.json").also { file = it }
            val next = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
            _entries.value = next
            write(f, next)
        }
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            val f = file ?: File(ctx.filesDir, "taildrop_history.json").also { file = it }
            _entries.value = emptyList()
            write(f, emptyList())
        }
    }

    /** Removes (and returns) the entries that belong to one account. */
    fun removeForAccount(ctx: Context, accountDir: String, accountEmail: String): List<TaildropEntry> {
        synchronized(lock) {
            val f = file ?: File(ctx.filesDir, "taildrop_history.json").also { file = it }
            val removed = _entries.value.filter {
                (accountDir.isNotBlank() && it.accountDir == accountDir) ||
                    (accountEmail.isNotBlank() && it.accountEmail == accountEmail &&
                        it.accountDir.isBlank())
            }
            if (removed.isEmpty()) return emptyList()
            val removedSet = removed.toSet()
            val kept = _entries.value.filterNot { it in removedSet }
            _entries.value = kept
            write(f, kept)
            return removed
        }
    }

    private fun read(f: File): List<TaildropEntry> {
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                TaildropEntry(
                    name = o.optString("name"),
                    savedName = o.optString("savedName"),
                    size = o.optLong("size"),
                    uri = o.optString("uri"),
                    accountEmail = o.optString("accountEmail"),
                    accountDir = o.optString("accountDir"),
                    savedAt = o.optLong("savedAt"),
                    status = o.optString("status", "saved"),
                    error = o.optString("error")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(f: File, list: List<TaildropEntry>) {
        try {
            val arr = JSONArray()
            list.forEach { e ->
                arr.put(
                    JSONObject().apply {
                        put("name", e.name)
                        put("savedName", e.savedName)
                        put("size", e.size)
                        put("uri", e.uri)
                        put("accountEmail", e.accountEmail)
                        put("accountDir", e.accountDir)
                        put("savedAt", e.savedAt)
                        put("status", e.status)
                        put("error", e.error)
                    }
                )
            }
            f.writeText(arr.toString())
        } catch (t: Throwable) {
            TsProxyApp.appendLog("taildrop history write failed: ${t.message}")
        }
    }
}

// ------------------------------------------------------------- destination

data class SavedFile(val uri: String, val displayName: String)

data class StoredFile(
    val name: String,
    val size: Long,
    val modified: Long,
    val uri: String
)

object TaildropDest {
    fun legacyPermissionNeeded(ctx: Context): Boolean =
        Build.VERSION.SDK_INT <= 28 &&
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

    fun hasLegacyPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT > 28 ||
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

    /** Human readable description of the configured destination. */
    fun describe(ctx: Context, dest: String): String {
        if (!TaildropPrefs.isTree(dest)) {
            return if (Build.VERSION.SDK_INT >= 29 || hasLegacyPermission(ctx)) {
                "下载/$TAILDROP_DIR_NAME"
            } else {
                "下载/$TAILDROP_DIR_NAME（需存储权限）"
            }
        }
        return treeDisplayName(ctx, TaildropPrefs.treeUri(dest)) ?: "自选文件夹"
    }

    fun treeDisplayName(ctx: Context, uriString: String): String? {
        return try {
            val tree = Uri.parse(uriString)
            val doc = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            ctx.contentResolver.query(
                doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    /** True when the destination folder currently contains at least one file. */
    fun hasFiles(ctx: Context, dest: String): Boolean {
        return try {
            when {
                TaildropPrefs.isTree(dest) -> {
                    val tree = Uri.parse(TaildropPrefs.treeUri(dest))
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree)
                    )
                    ctx.contentResolver.query(
                        children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                        null, null, null
                    )?.use { it.count > 0 } ?: false
                }
                Build.VERSION.SDK_INT >= 29 -> {
                    val collection = MediaStore.Downloads.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                    )
                    ctx.contentResolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$TAILDROP_DIR_NAME/%"),
                        null
                    )?.use { it.count > 0 } ?: false
                }
                else -> {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ),
                        TAILDROP_DIR_NAME
                    )
                    dir.listFiles()?.isNotEmpty() == true
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Lists the files currently in the destination folder (newest first). */
    fun listFiles(ctx: Context, dest: String): List<StoredFile> {
        return try {
            when {
                TaildropPrefs.isTree(dest) -> {
                    val tree = Uri.parse(TaildropPrefs.treeUri(dest))
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree)
                    )
                    val out = ArrayList<StoredFile>()
                    ctx.contentResolver.query(
                        children,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_SIZE,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID
                        ),
                        null, null, null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(3) ?: continue
                            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            out.add(
                                StoredFile(
                                    name = c.getString(0) ?: "",
                                    size = c.getLong(1),
                                    modified = c.getLong(2),
                                    uri = doc.toString()
                                )
                            )
                        }
                    }
                    out.sortedByDescending { it.modified }
                }
                Build.VERSION.SDK_INT >= 29 -> {
                    val collection = MediaStore.Downloads.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                    )
                    val out = ArrayList<StoredFile>()
                    ctx.contentResolver.query(
                        collection,
                        arrayOf(
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.SIZE,
                            MediaStore.MediaColumns.DATE_MODIFIED
                        ),
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                        arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$TAILDROP_DIR_NAME/"),
                        null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            out.add(
                                StoredFile(
                                    name = c.getString(1) ?: "",
                                    size = c.getLong(2),
                                    modified = c.getLong(3) * 1000L,
                                    uri = ContentUris.withAppendedId(collection, id).toString()
                                )
                            )
                        }
                    }
                    out.sortedByDescending { it.modified }
                }
                else -> {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ),
                        TAILDROP_DIR_NAME
                    )
                    dir.listFiles()?.map {
                        StoredFile(
                            name = it.name,
                            size = it.length(),
                            modified = it.lastModified(),
                            uri = Uri.fromFile(it).toString()
                        )
                    }?.sortedByDescending { it.modified } ?: emptyList()
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Opens the configured destination folder in the system Files app. */
    fun openDirectory(ctx: Context, dest: String): Boolean {
        val doc = if (TaildropPrefs.isTree(dest)) {
            val tree = Uri.parse(TaildropPrefs.treeUri(dest))
            DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
        } else {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:${Environment.DIRECTORY_DOWNLOADS}/$TAILDROP_DIR_NAME"
            )
        }

        // Device file managers disagree on which MIME type identifies a
        // directory, so try the common ones before falling back to the system
        // folder picker (which at least lands on the right location).
        val viewTypes = listOf(
            DocumentsContract.Document.MIME_TYPE_DIR,
            "resource/folder",
            "*/*"
        )
        for (mime in viewTypes) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(doc, mime)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            if (startQuietly(ctx, intent)) return true
        }

        // Last resort: open DocumentsUI at the folder so the user can browse it.
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, doc)
        }
        if (startQuietly(ctx, picker)) return true

        TsProxyApp.appendLog("taildrop: no activity for $doc")
        return false
    }

    private fun startQuietly(ctx: Context, intent: Intent): Boolean {
        return try {
            ctx.startActivity(intent)
            true
        } catch (t: Throwable) {
            TsProxyApp.appendLog("taildrop openDirectory: ${t.message}")
            false
        }
    }

    /** Creates a probe document and deletes it, to verify the tree is writable. */
    fun canWriteTree(ctx: Context, uriString: String): Boolean {
        return try {
            val tree = Uri.parse(uriString)
            val root = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val probe = DocumentsContract.createDocument(
                ctx.contentResolver, root, "application/octet-stream", ".tsproxy-write-test"
            ) ?: return false
            DocumentsContract.deleteDocument(ctx.contentResolver, probe)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun save(
        ctx: Context,
        dest: String,
        name: String,
        mime: String,
        src: File
    ): SavedFile = withContext(Dispatchers.IO) {
        if (TaildropPrefs.isTree(dest)) {
            saveToTree(ctx, TaildropPrefs.treeUri(dest), name, mime, src)
        } else if (Build.VERSION.SDK_INT >= 29) {
            saveToMediaStore(ctx, name, mime, src)
        } else {
            saveToLegacyDownloads(ctx, name, src)
        }
    }

    private fun saveToMediaStore(
        ctx: Context, name: String, mime: String, src: File
    ): SavedFile {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relPath = "${Environment.DIRECTORY_DOWNLOADS}/$TAILDROP_DIR_NAME"
        val displayName = uniqueMediaName(resolver, collection, relPath, name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("无法写入下载目录")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: throw IOException("无法打开下载目录输出流")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null, null
            )
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return SavedFile(uri.toString(), displayName)
    }

    private fun uniqueMediaName(
        resolver: android.content.ContentResolver,
        collection: Uri,
        relPath: String,
        name: String
    ): String {
        var candidate = name
        var i = 1
        while (true) {
            val exists = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("$relPath/", candidate),
                null
            )?.use { it.count > 0 } ?: false
            if (!exists) return candidate
            candidate = withSuffix(name, i++)
        }
    }

    private fun saveToTree(
        ctx: Context, uriString: String, name: String, mime: String, src: File
    ): SavedFile {
        val resolver = ctx.contentResolver
        val tree = Uri.parse(uriString)
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        if (!canWriteTree(ctx, uriString)) {
            throw IOException("所选文件夹不可写，请重新选择")
        }
        val displayName = uniqueTreeName(ctx, tree, name)
        val doc = DocumentsContract.createDocument(resolver, root, mime, displayName)
            ?: throw IOException("无法在所选文件夹创建文件")
        try {
            resolver.openOutputStream(doc)?.use { out ->
                src.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: throw IOException("无法打开输出流")
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw t
        }
        return SavedFile(doc.toString(), displayName)
    }

    private fun uniqueTreeName(ctx: Context, tree: Uri, name: String): String {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val existing = HashSet<String>()
        ctx.contentResolver.query(
            children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { existing.add(it) }
        }
        var candidate = name
        var i = 1
        while (candidate in existing) candidate = withSuffix(name, i++)
        return candidate
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyDownloads(ctx: Context, name: String, src: File): SavedFile {
        if (!hasLegacyPermission(ctx)) {
            throw SecurityException("没有存储权限，无法写入下载目录")
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            TAILDROP_DIR_NAME
        )
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建下载目录")
        var target = File(dir, name)
        var i = 1
        while (target.exists()) target = File(dir, withSuffix(name, i++))
        src.copyTo(target, overwrite = false)
        return SavedFile(Uri.fromFile(target).toString(), target.name)
    }

    private fun withSuffix(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name ($n)"
        else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
    }
}

// --------------------------------------------------------------- receiver

data class ReceiveResult(
    val ok: Boolean,
    val saved: SavedFile? = null,
    val error: String = ""
)

object TaildropReceiver {
    private val inFlight = HashSet<String>()

    /** Downloads one staged file, publishes it, records history and deletes staging. */
    suspend fun process(ctx: Context, dest: String, file: WaitingFile): ReceiveResult =
        withContext(Dispatchers.IO) {
            val safeName = File(file.name).name
            if (safeName != file.name || safeName.isBlank()) {
                return@withContext ReceiveResult(false, error = "非法文件名")
            }
            val alreadyRunning = synchronized(inFlight) { !inFlight.add(file.name) }
            if (alreadyRunning) return@withContext ReceiveResult(true)
            val tmp = File(ctx.cacheDir, "taildrop/inbox/$safeName")
            try {
                tmp.parentFile?.mkdirs()
                val err = Tsproxy.saveWaitingFile(file.name, tmp.absolutePath) ?: ""
                if (err.isNotBlank()) {
                    return@withContext ReceiveResult(false, error = err.removePrefix("ERROR: "))
                }
                val mime = guessMime(file.name)
                val saved = TaildropDest.save(ctx, dest, safeName, mime, tmp)
                Tsproxy.deleteWaitingFile(file.name)

                val account = parseAccount(Tsproxy.getAccountInfo() ?: "{}")
                TaildropHistory.add(
                    ctx,
                    TaildropEntry(
                        name = file.name,
                        savedName = saved.displayName,
                        size = file.size,
                        uri = saved.uri,
                        accountEmail = account.first,
                        accountDir = account.second,
                        savedAt = System.currentTimeMillis(),
                        status = "saved"
                    )
                )
                ReceiveResult(true, saved)
            } catch (t: Throwable) {
                TsProxyApp.appendLog("taildrop receive failed: ${t.message}")
                ReceiveResult(false, error = t.message ?: "保存失败")
            } finally {
                runCatching { tmp.delete() }
                synchronized(inFlight) { inFlight.remove(file.name) }
            }
        }

    private fun parseAccount(json: String): Pair<String, String> {
        return try {
            val o = JSONObject(json)
            o.optString("loginName") to o.optString("dir")
        } catch (_: Exception) {
            "" to ""
        }
    }
}

fun guessMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: "application/octet-stream"
}

/** Deletes one saved Taildrop file (MediaStore, SAF document or legacy file path). */
fun deleteSavedFile(ctx: Context, uriString: String): Boolean {
    if (uriString.isBlank()) return false
    return try {
        val uri = Uri.parse(uriString)
        when (uri.scheme) {
            "content" -> {
                val deleted = runCatching {
                    ctx.contentResolver.delete(uri, null, null)
                }.getOrDefault(0)
                if (deleted > 0) {
                    true
                } else {
                    // DocumentsProvider roots often reject ContentResolver.delete.
                    runCatching {
                        DocumentsContract.deleteDocument(ctx.contentResolver, uri)
                    }.isSuccess
                }
            }
            "file" -> {
                val path = uri.path ?: return false
                File(path).delete()
            }
            else -> false
        }
    } catch (_: Exception) {
        false
    }
}

fun queryDisplayName(ctx: Context, uri: Uri): String? {
    return try {
        ctx.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }
}

fun querySize(ctx: Context, uri: Uri): Long {
    return try {
        ctx.contentResolver.query(
            uri, arrayOf(OpenableColumns.SIZE), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
    } catch (_: Exception) {
        0L
    }
}

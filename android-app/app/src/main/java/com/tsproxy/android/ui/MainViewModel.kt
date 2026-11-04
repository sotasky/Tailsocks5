package com.tsproxy.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tsproxy.Tsproxy
import com.tsproxy.android.service.TsProxyService
import com.tsproxy.android.TsProxyApp
import com.tsproxy.android.TaildropPrefs
import com.tsproxy.android.tsproxyDataStore
import com.tsproxy.android.taildrop.TaildropHistory
import com.tsproxy.android.taildrop.TaildropInfo
import com.tsproxy.android.taildrop.TaildropReceiver
import com.tsproxy.android.taildrop.WaitingFile
import com.tsproxy.android.taildrop.TaildropEntry
import com.tsproxy.android.taildrop.parseTaildropInfo
import com.tsproxy.android.taildrop.parseWaitingFiles
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID

data class Peer(
    val id: String = "",
    val name: String = "",
    val hostName: String = "",
    val dnsName: String = "",
    val os: String = "",
    val ips: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val online: Boolean = false,
    val active: Boolean = false,
    val curAddr: String = "",
    val relay: String = "",
    val peerRelay: String = "",
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val exitNode: Boolean = false,
    val exitNodeOption: Boolean = false,
    val expired: Boolean = false,
    val lastSeen: String = "",
    val userName: String = "",
    val self: Boolean = false
) {
    val ipv4: String? get() = ips.firstOrNull { it.contains(':').not() }
    val ipv6: String? get() = ips.firstOrNull { it.contains(':') }
    val pingKey: String get() = id.ifBlank { ipv4 ?: ipv6 ?: name }
}

data class PingState(
    val latencyMs: Double = 0.0,
    val samples: List<Double?> = emptyList(),
    val running: Boolean = false,
    val endpoint: String = "",
    val peerRelay: String = "",
    val derpRegion: String = "",
    val error: String = ""
)

/**
 * One logged-in Tailscale identity. [dir] is the tsnet state directory that
 * belongs to this account; the app creates and manages it, users never see or
 * edit it. Switching accounts starts tsnet with another account's directory.
 */
data class Account(
    val id: String,
    val dir: String,
    val name: String = "",
    val email: String = "",
    val tailnet: String = "",
    // Device name used when this account's tsnet node starts. Each account can
    // present a different machine name for the same physical device.
    val hostname: String = "",
    val loggedOut: Boolean = false
) {
    val displayName: String get() = name.ifBlank { email.ifBlank { "新账号" } }
}

data class UiState(
    val running: Boolean = false,
    val socksAddr: String = "127.0.0.1:1080",
    val hostname: String = "ts-socks5",
    val accounts: List<Account> = emptyList(),
    val activeAccountId: String = "",
    val tailscaleIP: String = "",
    val account: String = "",
    val accountName: String = "",
    // Name of the tailnet the node belongs to. It can differ from the login
    // account when that account was invited into someone else's tailnet.
    val tailnet: String = "",
    val keyExpiry: String = "",
    val peers: List<Peer> = emptyList(),
    val pinnedPeers: Set<String> = emptySet(),
    val pings: Map<String, PingState> = emptyMap(),
    val loginUrl: String = "",
    val logs: String = "",
    val crashLog: String = "",
    val statusText: String = "Stopped",
    val logPaused: Boolean = false,
    // Taildrop
    val taildropEnabled: Boolean = true,
    val taildropDest: String = TaildropPrefs.DEST_MEDIA,
    val taildropInfo: TaildropInfo = TaildropInfo(),
    val waitingFiles: List<WaitingFile> = emptyList(),
    val taildropHistory: List<TaildropEntry> = emptyList(),
    val taildropNotice: String = ""
) {
    val activeAccount: Account? get() = accounts.firstOrNull { it.id == activeAccountId }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var pingJob: Job? = null
    private var activePingKey: String? = null
    private var reorderJob: Job? = null
    // True between requesting logout and the node actually stopping; status
    // from the still-running node must not repopulate the account.
    private var logoutInFlight = false

    init {
        loadConfig()
        startStatusPolling()
        viewModelScope.launch {
            TaildropHistory.entries.collect { list ->
                _ui.value = _ui.value.copy(taildropHistory = list)
            }
        }
    }

    private fun loadConfig() {
        viewModelScope.launch {
            val application = getApplication<Application>()
            val prefs = application.tsproxyDataStore.data.first()

            val storedAccounts = prefs[ACCOUNTS_KEY]
            var accounts = parseAccounts(storedAccounts ?: "")
            var activeId = prefs[ACTIVE_ACCOUNT_KEY] ?: ""
            if (storedAccounts == null) {
                // First run after upgrading from the single-directory version:
                // adopt the old directory (if any) as the default account so an
                // existing login is not lost. Older builds defaulted to
                // filesDir/tsnet when no custom directory was chosen.
                val legacyDir = prefs[TSNETDIR_KEY] ?: ""
                val legacyDefault = File(getApplication<Application>().filesDir, "tsnet")
                val dir = legacyDir.ifBlank {
                    if (legacyDefault.isDirectory) legacyDefault.absolutePath
                    else defaultDir(DEFAULT_ACCOUNT_ID)
                }
                accounts = listOf(
                    Account(
                        id = DEFAULT_ACCOUNT_ID,
                        dir = dir,
                        email = prefs[ACCOUNT_KEY] ?: ""
                    )
                )
            }
            if (accounts.isEmpty()) {
                // The user removed every account (logout deletes the entry);
                // do not recreate a default one on the next launch.
                activeId = ""
            } else if (accounts.none { it.id == activeId }) {
                activeId = accounts.first().id
            }
            val legacyHostname = prefs[HOSTNAME_KEY] ?: DEFAULT_HOSTNAME
            accounts = accounts.map {
                it.copy(
                    dir = it.dir.ifBlank { defaultDir(it.id) },
                    hostname = it.hostname.ifBlank { legacyHostname }
                )
            }

            application.tsproxyDataStore.edit { p ->
                p[ACCOUNTS_KEY] = accountsToJson(accounts)
                p[ACTIVE_ACCOUNT_KEY] = activeId
                // The data directory and hostname are app/account-managed now.
                p.remove(TSNETDIR_KEY)
                p.remove(ACCOUNT_KEY)
                p.remove(HOSTNAME_KEY)
            }

            val active = accounts.firstOrNull { it.id == activeId }
            _ui.value = _ui.value.copy(
                socksAddr = prefs[SOCKS_KEY] ?: "127.0.0.1:1080",
                hostname = active?.hostname ?: DEFAULT_HOSTNAME,
                pinnedPeers = prefs[PINNED_KEY] ?: emptySet(),
                accounts = accounts,
                activeAccountId = activeId,
                account = active?.email ?: "",
                taildropEnabled = prefs[TaildropPrefs.ENABLED] ?: true,
                taildropDest = prefs[TaildropPrefs.DEST] ?: TaildropPrefs.DEST_MEDIA
            )
        }
    }

    private fun defaultDir(id: String): String =
        File(getApplication<Application>().filesDir, "accounts/$id").absolutePath

    /** Current directory for the active account, creating the entry if needed. */
    private fun activeDir(): String {
        val state = _ui.value
        state.activeAccount?.let { return it.dir }
        val id = state.activeAccountId.ifBlank { DEFAULT_ACCOUNT_ID }
        return defaultDir(id)
    }

    private fun activeHostname(): String =
        _ui.value.activeAccount?.hostname?.ifBlank { DEFAULT_HOSTNAME } ?: DEFAULT_HOSTNAME

    fun togglePin(peerId: String) {
        if (peerId.isBlank()) return
        val current = _ui.value.pinnedPeers
        val next = if (peerId in current) current - peerId else current + peerId
        _ui.value = _ui.value.copy(pinnedPeers = next)
        viewModelScope.launch {
            getApplication<Application>().tsproxyDataStore.edit { prefs ->
                prefs[PINNED_KEY] = next
            }
        }
    }

    fun saveSocks(socks: String) {
        viewModelScope.launch {
            getApplication<Application>().tsproxyDataStore.edit { prefs ->
                prefs[SOCKS_KEY] = socks
            }
            _ui.value = _ui.value.copy(socksAddr = socks)
        }
    }

    /**
     * Change the active account's device name. Only exposed while the account
     * is waiting for authorization, so [Tsproxy.setHostname] can update the
     * pending node's prefs without a restart.
     */
    fun saveHostname(hostname: String) {
        val name = hostname.trim()
        if (name.isEmpty()) return
        val state = _ui.value
        val active = state.activeAccount ?: return
        val accounts = state.accounts.map {
            if (it.id == active.id) it.copy(hostname = name) else it
        }
        _ui.value = state.copy(accounts = accounts, hostname = name)
        TsProxyApp.appendLog("UI: saveHostname $name")
        TsProxyService.updateSavedHostname(getApplication(), name)
        viewModelScope.launch {
            persistAccounts(accounts, state.activeAccountId)
            val err = withContext(Dispatchers.IO) { Tsproxy.setHostname(name) ?: "" }
            if (err.isNotBlank()) {
                // "未启动" just means the value was stored for the next start.
                TsProxyApp.appendLog("UI setHostname: $err")
            }
        }
    }

    fun startProxy() {
        val ctx = getApplication<Application>()
        val state = _ui.value
        if (state.accounts.isEmpty()) {
            _ui.value = state.copy(statusText = "请先新增账号")
            return
        }
        logoutInFlight = false
        _ui.value = _ui.value.copy(statusText = "Starting...")
        val hostname = activeHostname()
        TsProxyApp.appendLog("UI: startProxy clicked, socks=${state.socksAddr} host=$hostname")
        try {
            TsProxyService.start(ctx, state.socksAddr, hostname, activeDir())
        } catch (e: Exception) {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            TsProxyApp.appendLog("UI startProxy EXCEPTION: $sw")
            _ui.value = _ui.value.copy(
                statusText = "Failed: ${e.message?.take(100) ?: "unknown"}",
                crashLog = TsProxyApp.readCrashLog()
            )
        }
    }

    fun stopProxy() {
        val ctx = getApplication<Application>()
        _ui.value = _ui.value.copy(statusText = "Stopping...")
        TsProxyApp.appendLog("UI: stopProxy clicked")
        try {
            TsProxyService.stop(ctx)
        } catch (e: Exception) {
            TsProxyApp.appendLog("UI stopProxy EXCEPTION: ${e.message}")
        }
    }

    /**
     * Create a brand-new account slot. The current connection is dropped and
     * tsnet is started against a fresh, empty directory, which makes the next
     * login URL point at a new device identity.
     */
    fun addAccount() {
        val ctx = getApplication<Application>()
        logoutInFlight = false
        val id = UUID.randomUUID().toString()
        val account = Account(id = id, dir = defaultDir(id), hostname = DEFAULT_HOSTNAME)
        val accounts = _ui.value.accounts + account
        _ui.value = _ui.value.copy(
            accounts = accounts,
            activeAccountId = id,
            account = "",
            accountName = "",
            hostname = account.hostname,
            tailscaleIP = "",
            keyExpiry = "",
            loginUrl = "",
            statusText = "Starting..."
        )
        TsProxyApp.appendLog("UI: addAccount id=$id dir=${account.dir}")
        viewModelScope.launch { persistAccounts(accounts, id) }
        try {
            TsProxyService.switchTo(ctx, _ui.value.socksAddr, _ui.value.hostname, account.dir)
        } catch (e: Exception) {
            TsProxyApp.appendLog("UI addAccount EXCEPTION: ${e.message}")
        }
    }

    /** Use an existing account: stop the current tsnet node and boot this one. */
    fun selectAccount(account: Account) {
        val state = _ui.value
        if (account.id == state.activeAccountId && state.running) return
        val ctx = getApplication<Application>()
        logoutInFlight = false
        _ui.value = state.copy(
            activeAccountId = account.id,
            account = "",
            accountName = "",
            hostname = account.hostname.ifBlank { DEFAULT_HOSTNAME },
            tailscaleIP = "",
            keyExpiry = "",
            loginUrl = "",
            statusText = "Starting..."
        )
        val hostname = account.hostname.ifBlank { DEFAULT_HOSTNAME }
        TsProxyApp.appendLog("UI: selectAccount id=${account.id} dir=${account.dir} host=$hostname")
        viewModelScope.launch { persistAccounts(state.accounts, account.id) }
        try {
            TsProxyService.switchTo(ctx, state.socksAddr, hostname, account.dir)
        } catch (e: Exception) {
            TsProxyApp.appendLog("UI selectAccount EXCEPTION: ${e.message}")
        }
    }

    /** Moves an account within the list (drag to reorder). */
    fun moveAccount(from: Int, to: Int) {
        val state = _ui.value
        if (from == to || from !in state.accounts.indices || to !in state.accounts.indices) return
        val list = state.accounts.toMutableList()
        val item = list.removeAt(from)
        list.add(to, item)
        _ui.value = state.copy(accounts = list)
        // Dragging crosses several rows quickly; debounce the disk write.
        reorderJob?.cancel()
        reorderJob = viewModelScope.launch {
            delay(250)
            persistAccounts(list, state.activeAccountId)
        }
    }

    /** Ask control for a fresh login URL for the current account. */
    fun reauthenticate() {
        if (!_ui.value.running) {
            startProxy()
            return
        }
        _ui.value = _ui.value.copy(statusText = "正在请求重新认证...")
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { Tsproxy.reauthenticate() ?: "" }
            TsProxyApp.appendLog("UI: reauthenticate -> ${err.ifBlank { "ok" }}")
            _ui.value = _ui.value.copy(
                statusText = if (err.isBlank()) "请打开登录链接完成认证" else err
            )
        }
    }

    /** Sign out: the account stays in the list but must re-authenticate next time. */
    /**
     * Sign out. The caller chooses whether the tsnet data directory (and thus
     * the account entry) is deleted, and whether this account's received
     * Taildrop files/history are removed. Keeping the data keeps the account
     * in the list marked as logged out.
     */
    fun logout(deleteTsData: Boolean = false, deleteTaildropFiles: Boolean = false) {
        val ctx = getApplication<Application>()
        val state = _ui.value
        val active = state.activeAccount ?: return
        // Taildrop cleanup is only offered together with the tsnet data.
        val deleteTaildrop = deleteTaildropFiles && deleteTsData
        logoutInFlight = true
        TsProxyApp.appendLog(
            "UI: logout id=${active.id} deleteData=$deleteTsData deleteTaildrop=$deleteTaildrop"
        )

        if (deleteTsData) {
            val remaining = state.accounts.filterNot { it.id == active.id }
            val next = remaining.firstOrNull()
            _ui.value = state.copy(
                accounts = remaining,
                activeAccountId = next?.id ?: "",
                hostname = next?.hostname?.ifBlank { DEFAULT_HOSTNAME } ?: DEFAULT_HOSTNAME,
                account = next?.email ?: "",
                accountName = next?.name ?: "",
                tailnet = next?.tailnet ?: "",
                tailscaleIP = "",
                keyExpiry = "",
                loginUrl = "",
                running = false,
                statusText = if (remaining.isEmpty()) "已删除账号" else "已登出并删除该账号"
            )
            viewModelScope.launch { persistAccounts(remaining, next?.id ?: "") }
        } else {
            val accounts = state.accounts.map {
                if (it.id == active.id) it.copy(loggedOut = true) else it
            }
            _ui.value = state.copy(
                accounts = accounts,
                account = active.email,
                accountName = active.name,
                tailnet = active.tailnet,
                tailscaleIP = "",
                keyExpiry = "",
                loginUrl = "",
                running = false,
                statusText = "已登出"
            )
            viewModelScope.launch { persistAccounts(accounts, state.activeAccountId) }
        }

        if (deleteTaildrop) {
            viewModelScope.launch {
                val removed = TaildropHistory.removeForAccount(ctx, active.dir, active.email)
                var deleted = 0
                var failed = 0
                withContext(Dispatchers.IO) {
                    removed.forEach { entry ->
                        if (entry.uri.isNotBlank()) {
                            if (com.tsproxy.android.taildrop.deleteSavedFile(ctx, entry.uri)) {
                                deleted++
                            } else {
                                failed++
                            }
                        }
                    }
                }
                TsProxyApp.appendLog(
                    "UI: logout taildrop files deleted=$deleted failed=$failed"
                )
            }
        }

        try {
            TsProxyService.logout(ctx, active.dir, deleteTsData, deleteTaildrop)
        } catch (e: Exception) {
            TsProxyApp.appendLog("UI logout EXCEPTION: ${e.message}")
        }
    }

    private suspend fun persistAccounts(accounts: List<Account>, activeId: String) {
        getApplication<Application>().tsproxyDataStore.edit { prefs ->
            prefs[ACCOUNTS_KEY] = accountsToJson(accounts)
            prefs[ACTIVE_ACCOUNT_KEY] = activeId
        }
    }

    fun clearCrashLog() {
        TsProxyApp.clearCrashLog()
        _ui.value = _ui.value.copy(crashLog = "")
    }

    fun clearLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                Tsproxy.clearLogs()
            }
            _ui.value = _ui.value.copy(logs = "")
        }
    }

    fun toggleLogPause() {
        _ui.value = _ui.value.copy(logPaused = !_ui.value.logPaused)
    }

    // ------------------------------------------------------------ taildrop

    fun setTaildropEnabled(enabled: Boolean) {
        _ui.value = _ui.value.copy(taildropEnabled = enabled)
        viewModelScope.launch {
            getApplication<Application>().tsproxyDataStore.edit { it[TaildropPrefs.ENABLED] = enabled }
        }
    }

    fun setTaildropDest(dest: String) {
        _ui.value = _ui.value.copy(taildropDest = dest)
        viewModelScope.launch {
            getApplication<Application>().tsproxyDataStore.edit { it[TaildropPrefs.DEST] = dest }
        }
    }

    /** Re-reads staging + history; called when the Taildrop page opens. */
    fun refreshTaildrop() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val (files, _) = withContext(Dispatchers.IO) {
                parseWaitingFiles(Tsproxy.getWaitingFiles() ?: "{}")
            }
            TaildropHistory.refresh(ctx)
            _ui.value = _ui.value.copy(waitingFiles = files)
        }
    }

    fun saveWaitingFile(file: WaitingFile) {
        val ctx = getApplication<Application>()
        val dest = _ui.value.taildropDest
        viewModelScope.launch {
            val result = TaildropReceiver.process(ctx, dest, file)
            TsProxyApp.appendLog(
                "UI saveWaitingFile ${file.name}: ${if (result.ok) "ok" else result.error}"
            )
            val (files, _) = withContext(Dispatchers.IO) {
                parseWaitingFiles(Tsproxy.getWaitingFiles() ?: "{}")
            }
            _ui.value = _ui.value.copy(waitingFiles = files)
            TaildropHistory.refresh(ctx)
        }
    }

    fun deleteWaitingFile(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Tsproxy.deleteWaitingFile(name) }
            val (files, _) = withContext(Dispatchers.IO) {
                parseWaitingFiles(Tsproxy.getWaitingFiles() ?: "{}")
            }
            _ui.value = _ui.value.copy(waitingFiles = files)
        }
    }

    /**
     * Clears the receive ledger. When [deleteFiles] is true the saved
     * destination files are deleted too (MediaStore / SAF / legacy path).
     */
    fun clearTaildropHistory(deleteFiles: Boolean) {
        val ctx = getApplication<Application>()
        val entries = _ui.value.taildropHistory
        viewModelScope.launch {
            var deleted = 0
            var failed = 0
            if (deleteFiles) {
                withContext(Dispatchers.IO) {
                    entries.forEach { entry ->
                        if (entry.uri.isNotBlank()) {
                            if (com.tsproxy.android.taildrop.deleteSavedFile(ctx, entry.uri)) {
                                deleted++
                            } else {
                                failed++
                            }
                        }
                    }
                }
            }
            TaildropHistory.clear(ctx)
            _ui.value = _ui.value.copy(
                taildropHistory = emptyList(),
                taildropNotice = when {
                    !deleteFiles -> "已清空接收记录"
                    failed == 0 -> "已清空记录并删除 $deleted 个文件"
                    deleted == 0 -> "记录已清空，$failed 个文件删除失败"
                    else -> "记录已清空，删除 $deleted 个，$failed 个失败"
                }
            )
        }
    }

    fun consumeTaildropNotice() {
        if (_ui.value.taildropNotice.isNotEmpty()) {
            _ui.value = _ui.value.copy(taildropNotice = "")
        }
    }

    /** 开始对本节点持续 ping（最多 10 次）。 */
    fun startPing(peer: Peer) {
        val key = peer.pingKey
        if (key.isBlank() || !_ui.value.running) return
        val ip = peer.ipv4 ?: peer.ipv6 ?: return
        pingJob?.cancel()
        activePingKey = key
        updatePing(key) { PingState(running = true) }

        pingJob = viewModelScope.launch {
            val samples = mutableListOf<Double?>()
            var endpoint = ""
            var peerRelay = ""
            var derpRegion = ""

            for (i in 0 until PING_MAX_SAMPLES) {
                if (!isActive) break
                val json = withContext(Dispatchers.IO) { Tsproxy.pingPeer(ip) }
                val result = parsePing(json)
                if (result.error.isNotEmpty()) {
                    // 超时/失败：记录一个空点，折线在此断开
                    samples.add(null)
                } else {
                    samples.add(result.latencyMs)
                    // 每次都覆盖：直连 <-> DERP 切换时不能保留旧值
                    endpoint = result.endpoint
                    peerRelay = result.peerRelay
                    derpRegion = result.derpRegion
                }

                val snapshot = samples.toList()
                val lastOk = snapshot.lastOrNull { it != null }
                updatePing(key) {
                    it.copy(
                        samples = snapshot,
                        latencyMs = lastOk ?: it.latencyMs,
                        running = i < PING_MAX_SAMPLES - 1,
                        endpoint = endpoint,
                        peerRelay = peerRelay,
                        derpRegion = derpRegion,
                        error = result.error
                    )
                }

                if (i < PING_MAX_SAMPLES - 1) kotlinx.coroutines.delay(PING_INTERVAL_MS)
            }

            updatePing(key) { it.copy(running = false) }
            if (activePingKey == key) activePingKey = null
        }
    }

    /** 停止当前 ping。 */
    fun closePing() {
        pingJob?.cancel()
        pingJob = null
        val key = activePingKey
        activePingKey = null
        if (key != null) {
            updatePing(key) { it.copy(running = false) }
        }
    }

    private fun updatePing(key: String, transform: (PingState) -> PingState) {
        val current = _ui.value.pings[key] ?: PingState()
        _ui.value = _ui.value.copy(
            pings = _ui.value.pings + (key to transform(current))
        )
    }

    private fun startStatusPolling() {
        viewModelScope.launch {
            while (true) {
                withContext(Dispatchers.IO) {
                    val actuallyRunning = Tsproxy.isRunning()
                    if (!actuallyRunning) logoutInFlight = false
                    // Keep the UI "stopped" while the logout/delete is still
                    // finishing on the service thread.
                    val running = actuallyRunning && !logoutInFlight
                    val activeDir = _ui.value.activeAccount?.dir ?: ""
                    val rawInfo = if (running) parseAccountInfo(Tsproxy.getAccountInfo() ?: "{}") else null
                    // During an account switch the old node's status can still
                    // arrive for a moment. Only trust status that reports the
                    // active account's own data directory.
                    val info = if (logoutInFlight) null else
                        rawInfo?.takeIf { activeDir.isBlank() || it.dir == activeDir }
                    // While tsnet is still waiting for authorization the server
                    // is not published yet, so read the URL directly.
                    val loginUrl = if (logoutInFlight) "" else
                        info?.loginUrl ?: (Tsproxy.getLoginURL() ?: "")
                    val activeId = _ui.value.activeAccountId

                    var accounts = _ui.value.accounts
                    var accountsChanged = false
                    if (!logoutInFlight && info != null && info.loggedIn) {
                        val idx = accounts.indexOfFirst { it.id == activeId }
                        if (idx >= 0) {
                            val current = accounts[idx]
                            if (current.name != info.displayName ||
                                current.email != info.loginName ||
                                current.tailnet != info.tailnetName ||
                                current.loggedOut
                            ) {
                                accounts = accounts.toMutableList().also {
                                    it[idx] = current.copy(
                                        name = info.displayName,
                                        email = info.loginName,
                                        tailnet = info.tailnetName,
                                        loggedOut = false
                                    )
                                }
                                accountsChanged = true
                            }
                        }
                    }
                    if (accountsChanged) {
                        getApplication<Application>().tsproxyDataStore.edit { prefs ->
                            prefs[ACCOUNTS_KEY] = accountsToJson(accounts)
                        }
                    }

                    val peers = if (running) parsePeers(Tsproxy.getPeers() ?: "[]") else emptyList()
                    val logs = Tsproxy.getLogs() ?: ""
                    val crashLog = TsProxyApp.readCrashLog()
                    val waiting = if (running) {
                        parseWaitingFiles(Tsproxy.getWaitingFiles() ?: "{}").first
                    } else {
                        emptyList()
                    }
                    val taildropInfo = if (running) {
                        parseTaildropInfo(Tsproxy.getTaildropInfo() ?: "{}")
                    } else {
                        TaildropInfo()
                    }
                    val loggedInInfo = info?.takeIf { it.loggedIn }
                    val newStatus = when {
                        running -> "Running"
                        loginUrl.isNotEmpty() -> "需要授权"
                        _ui.value.statusText == "Stopping..." -> "Stopped"
                        else -> _ui.value.statusText
                    }
                    _ui.value = _ui.value.copy(
                        running = running,
                        tailscaleIP = info?.tailscaleIP ?: "",
                        account = loggedInInfo?.loginName ?: "",
                        accountName = loggedInInfo?.displayName ?: "",
                        tailnet = loggedInInfo?.tailnetName ?: "",
                        hostname = accounts.firstOrNull { it.id == _ui.value.activeAccountId }?.hostname
                            ?: _ui.value.hostname,
                        keyExpiry = info?.keyExpiry ?: "",
                        accounts = accounts,
                        peers = peers,
                        logs = logs,
                        loginUrl = loginUrl,
                        crashLog = crashLog,
                        statusText = newStatus,
                        waitingFiles = waiting,
                        taildropInfo = taildropInfo
                    )
                }
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    companion object {
        private const val DEFAULT_ACCOUNT_ID = "default"
        private const val DEFAULT_HOSTNAME = "ts-socks5"
        private val SOCKS_KEY = stringPreferencesKey("socks_addr")
        private val HOSTNAME_KEY = stringPreferencesKey("hostname")
        // Only read once, to migrate the old user-selectable directory.
        private val TSNETDIR_KEY = stringPreferencesKey("tsnet_dir")
        // Only read once, to migrate the old cached account email.
        private val ACCOUNT_KEY = stringPreferencesKey("account_email")
        private val ACCOUNTS_KEY = stringPreferencesKey("accounts_json")
        private val ACTIVE_ACCOUNT_KEY = stringPreferencesKey("active_account_id")
        private val PINNED_KEY = stringSetPreferencesKey("pinned_nodes")
        private const val PING_MAX_SAMPLES = 10
        private const val PING_INTERVAL_MS = 600L
    }
}

private data class LiveAccountInfo(
    val loggedIn: Boolean,
    val loginName: String,
    val displayName: String,
    val tailnetName: String,
    val keyExpiry: String,
    val tailscaleIP: String,
    val loginUrl: String,
    val backendState: String,
    val dir: String
)

private fun accountsToJson(accounts: List<Account>): String {
    val arr = org.json.JSONArray()
    accounts.forEach { a ->
        arr.put(
            org.json.JSONObject().apply {
                put("id", a.id)
                put("dir", a.dir)
                put("name", a.name)
                put("email", a.email)
                put("tailnet", a.tailnet)
                put("hostname", a.hostname)
                put("loggedOut", a.loggedOut)
            }
        )
    }
    return arr.toString()
}

private fun parseAccounts(json: String): List<Account> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) return@mapNotNull null
            Account(
                id = id,
                dir = o.optString("dir"),
                name = o.optString("name"),
                email = o.optString("email"),
                tailnet = o.optString("tailnet"),
                hostname = o.optString("hostname"),
                loggedOut = o.optBoolean("loggedOut")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun parseAccountInfo(json: String): LiveAccountInfo? {
    if (json.isBlank() || json == "{}") return null
    return try {
        val o = org.json.JSONObject(json)
        LiveAccountInfo(
            loggedIn = o.optBoolean("loggedIn"),
            loginName = o.optString("loginName"),
            displayName = o.optString("displayName"),
            tailnetName = o.optString("tailnetName"),
            keyExpiry = o.optString("keyExpiry"),
            tailscaleIP = o.optString("tailscaleIP"),
            loginUrl = o.optString("loginUrl"),
            backendState = o.optString("backendState"),
            dir = o.optString("dir")
        )
    } catch (_: Exception) {
        null
    }
}

private fun parsePing(json: String): PingState {
    return try {
        val o = org.json.JSONObject(json)
        PingState(
            latencyMs = o.optDouble("latencyMs", 0.0),
            endpoint = o.optString("endpoint", ""),
            peerRelay = o.optString("peerRelay", ""),
            derpRegion = o.optString("derpRegion", ""),
            error = o.optString("err", "")
        )
    } catch (_: Exception) {
        PingState(error = "解析失败")
    }
}

/** Parses the JSON produced by tsproxy.GetPeers. */
private fun parsePeers(json: String): List<Peer> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ipsArr = o.optJSONArray("ips")
            val ips = if (ipsArr == null) emptyList() else
                (0 until ipsArr.length()).map { j -> ipsArr.getString(j) }
            val tagsArr = o.optJSONArray("tags")
            val tags = if (tagsArr == null) emptyList() else
                (0 until tagsArr.length()).map { j -> tagsArr.getString(j) }
            Peer(
                id = o.optString("id"),
                name = o.optString("name"),
                hostName = o.optString("hostName"),
                dnsName = o.optString("dnsName"),
                os = o.optString("os"),
                ips = ips,
                tags = tags,
                online = o.optBoolean("online"),
                active = o.optBoolean("active"),
                curAddr = o.optString("curAddr"),
                relay = o.optString("relay"),
                peerRelay = o.optString("peerRelay", ""),
                rxBytes = o.optLong("rxBytes"),
                txBytes = o.optLong("txBytes"),
                exitNode = o.optBoolean("exitNode"),
                exitNodeOption = o.optBoolean("exitNodeOption"),
                expired = o.optBoolean("expired"),
                lastSeen = o.optString("lastSeen"),
                userName = o.optString("userName"),
                self = o.optBoolean("self")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

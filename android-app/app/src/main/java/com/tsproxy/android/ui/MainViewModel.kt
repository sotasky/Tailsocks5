package com.tsproxy.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tsproxy.Tsproxy
import com.tsproxy.android.service.TsProxyService
import com.tsproxy.android.TsProxyApp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Application.dataStore by preferencesDataStore("tsproxy_prefs")

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
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val exitNode: Boolean = false,
    val exitNodeOption: Boolean = false,
    val expired: Boolean = false,
    val lastSeen: String = "",
    val userName: String = ""
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
    val derpRegion: String = "",
    val error: String = ""
)

data class UiState(
    val running: Boolean = false,
    val socksAddr: String = "127.0.0.1:1080",
    val hostname: String = "ts-socks5",
    val tsnetDir: String = "",
    val tailscaleIP: String = "",
    val account: String = "",
    val peers: List<Peer> = emptyList(),
    val pinnedPeers: Set<String> = emptySet(),
    val pings: Map<String, PingState> = emptyMap(),
    val loginUrl: String = "",
    val logs: String = "",
    val crashLog: String = "",
    val statusText: String = "Stopped",
    val logPaused: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var pingJob: Job? = null
    private var activePingKey: String? = null

    init {
        loadConfig()
        startStatusPolling()
    }

    private fun loadConfig() {
        viewModelScope.launch {
            val prefs = getApplication<Application>().dataStore.data.first()
            _ui.value = _ui.value.copy(
                socksAddr = prefs[SOCKS_KEY] ?: "127.0.0.1:1080",
                hostname = prefs[HOSTNAME_KEY] ?: "ts-socks5",
                tsnetDir = prefs[TSNETDIR_KEY] ?: "",
                pinnedPeers = prefs[PINNED_KEY] ?: emptySet(),
                account = prefs[ACCOUNT_KEY] ?: ""
            )
        }
    }

    fun togglePin(peerId: String) {
        if (peerId.isBlank()) return
        val current = _ui.value.pinnedPeers
        val next = if (peerId in current) current - peerId else current + peerId
        _ui.value = _ui.value.copy(pinnedPeers = next)
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[PINNED_KEY] = next
            }
        }
    }

    fun saveConfig(socks: String, hostname: String, tsnetDir: String) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { prefs ->
                prefs[SOCKS_KEY] = socks
                prefs[HOSTNAME_KEY] = hostname
                prefs[TSNETDIR_KEY] = tsnetDir
            }
            _ui.value = _ui.value.copy(
                socksAddr = socks,
                hostname = hostname,
                tsnetDir = tsnetDir
            )
        }
    }

    fun startProxy() {
        val ctx = getApplication<Application>()
        val state = _ui.value
        _ui.value = _ui.value.copy(statusText = "Starting...")
        TsProxyApp.appendLog("UI: startProxy clicked, socks=${state.socksAddr} host=${state.hostname}")
        try {
            TsProxyService.start(ctx, state.socksAddr, state.hostname, state.tsnetDir)
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
                    if (result.endpoint.isNotBlank()) endpoint = result.endpoint
                    if (result.derpRegion.isNotBlank()) derpRegion = result.derpRegion
                }

                val snapshot = samples.toList()
                val lastOk = snapshot.lastOrNull { it != null }
                updatePing(key) {
                    it.copy(
                        samples = snapshot,
                        latencyMs = lastOk ?: it.latencyMs,
                        running = i < PING_MAX_SAMPLES - 1,
                        endpoint = endpoint,
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
                    val running = Tsproxy.isRunning()
                    val ip = if (running) Tsproxy.getTailscaleIP() ?: "" else _ui.value.tailscaleIP
                    // 账号只在拿到非空值时更新并缓存，避免启动过程中被清空而闪烁
                    val fetchedAccount = if (running) Tsproxy.getAccountName() ?: "" else ""
                    val account = fetchedAccount.ifBlank { _ui.value.account }
                    if (fetchedAccount.isNotBlank() && fetchedAccount != _ui.value.account) {
                        getApplication<Application>().dataStore.edit { prefs ->
                            prefs[ACCOUNT_KEY] = fetchedAccount
                        }
                    }
                    val peers = if (running) parsePeers(Tsproxy.getPeers() ?: "[]") else emptyList()
                    val logs = Tsproxy.getLogs() ?: ""
                    val crashLog = TsProxyApp.readCrashLog()
                    val url = Tsproxy.getLoginURL() ?: ""
                    val newStatus = when {
                        running -> "Running"
                        url.isNotEmpty() -> "需要授权"
                        _ui.value.statusText == "Stopping..." -> "Stopped"
                        else -> _ui.value.statusText
                    }
                    _ui.value = _ui.value.copy(
                        running = running,
                        tailscaleIP = ip,
                        account = account,
                        peers = peers,
                        logs = logs,
                        loginUrl = url,
                        crashLog = crashLog,
                        statusText = newStatus
                    )
                }
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    companion object {
        private val SOCKS_KEY = stringPreferencesKey("socks_addr")
        private val HOSTNAME_KEY = stringPreferencesKey("hostname")
        private val TSNETDIR_KEY = stringPreferencesKey("tsnet_dir")
        private val PINNED_KEY = stringSetPreferencesKey("pinned_nodes")
        private val ACCOUNT_KEY = stringPreferencesKey("account_email")
        private const val PING_MAX_SAMPLES = 10
        private const val PING_INTERVAL_MS = 600L
    }
}

private fun parsePing(json: String): PingState {
    return try {
        val o = org.json.JSONObject(json)
        PingState(
            latencyMs = o.optDouble("latencyMs", 0.0),
            endpoint = o.optString("endpoint", ""),
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
                rxBytes = o.optLong("rxBytes"),
                txBytes = o.optLong("txBytes"),
                exitNode = o.optBoolean("exitNode"),
                exitNodeOption = o.optBoolean("exitNodeOption"),
                expired = o.optBoolean("expired"),
                lastSeen = o.optString("lastSeen"),
                userName = o.optString("userName")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

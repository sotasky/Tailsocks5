package com.tsproxy.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

/** 从右侧滑入的二级页面。 */
private sealed interface Screen {
    data class PeerDetail(val peer: Peer) : Screen
    data object Config : Screen
    data object Settings : Screen
    data object TsLog : Screen
    data object AppLog : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val haptics = LocalHapticFeedback.current

    var navStack by remember { mutableStateOf<List<Screen>>(emptyList()) }
    fun push(screen: Screen) { navStack = navStack + screen }
    fun pop() { navStack = navStack.dropLast(1) }

    BackHandler(enabled = navStack.isNotEmpty()) { pop() }

    // 底部弹层状态（自绘，不用 ModalBottomSheet，避免残留窗口挡住点击）
    var pingSheetPeer by remember { mutableStateOf<Peer?>(null) }
    var actionPeer by remember { mutableStateOf<Peer?>(null) }
    // 退场动画期间保留内容
    var lastPingPeer by remember { mutableStateOf<Peer?>(null) }
    var lastActionPeer by remember { mutableStateOf<Peer?>(null) }
    LaunchedEffect(pingSheetPeer) { if (pingSheetPeer != null) lastPingPeer = pingSheetPeer }
    LaunchedEffect(actionPeer) { if (actionPeer != null) lastActionPeer = actionPeer }

    fun closePingSheet() {
        vm.closePing()
        pingSheetPeer = null
    }

    BackHandler(enabled = pingSheetPeer != null || actionPeer != null) {
        if (actionPeer != null) {
            actionPeer = null
        }
        if (pingSheetPeer != null) {
            closePingSheet()
        }
    }

    fun copyToClipboard(label: String, value: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
    }

    val tsLogCount = remember(ui.logs) {
        if (ui.logs.isBlank()) 0 else ui.logs.trim().split("\n").count { it.isNotBlank() }
    }
    val appLogCount = remember(ui.crashLog) {
        if (ui.crashLog.isBlank()) 0
        else ui.crashLog.trim().split("\n").count { it.isNotBlank() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // 顶部：启动/停止胶囊开关 + Tailscale 账户名 + 账户头像
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PowerToggle(
                    running = ui.running,
                    onToggle = { if (ui.running) vm.stopProxy() else vm.startProxy() }
                )

                Spacer(Modifier.width(12.dp))

                // 只显示缓存的账号；没有时不显示任何占位文字
                Text(
                    ui.account,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { push(Screen.Settings) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.AccountCircle,
                        contentDescription = "设置",
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 配置卡片：点击进入设置
            Card(
                onClick = { push(Screen.Config) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "配置",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // 始终显示，避免停止时整行消失导致布局跳动
                    StatusRow("Tailscale IP", ui.tailscaleIP.ifEmpty { "—" })
                    StatusRow("SOCKS5", ui.socksAddr)
                    StatusRow("主机名", ui.hostname)

                    // 仅在启动失败时追加提示
                    if (ui.statusText.startsWith("Failed")) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            ui.statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 登录提示（如有）
            if (ui.loginUrl.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "需要 Tailscale 认证",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ui.loginUrl)))
                        }) {
                            Icon(Icons.Filled.OpenInBrowser, null)
                            Spacer(Modifier.width(4.dp))
                            Text("打开登录链接")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 节点列表，始终展开
            NodeCard(
                peers = ui.peers,
                pinnedIds = ui.pinnedPeers,
                pings = ui.pings,
                onPeerClick = { push(Screen.PeerDetail(it)) },
                onPeerLongPress = { actionPeer = it },
                onPing = { peer ->
                    vm.startPing(peer)
                    pingSheetPeer = peer
                }
            )

            Spacer(Modifier.height(24.dp))
        }

        AnimatedContent(
            targetState = navStack,
            transitionSpec = {
                if (targetState.size > initialState.size) {
                    (slideInHorizontally(animationSpec = tween(280)) { it } + fadeIn()) togetherWith
                        (slideOutHorizontally(animationSpec = tween(280)) { -it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally(animationSpec = tween(280)) { -it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally(animationSpec = tween(280)) { it } + fadeOut())
                }
            },
            label = "pages",
            modifier = Modifier.fillMaxSize()
        ) { stack ->
            val top = stack.lastOrNull()
            if (top != null) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (top) {
                        is Screen.PeerDetail -> {
                            val peer = if (top.peer.id.isBlank()) top.peer
                            else ui.peers.firstOrNull { it.id == top.peer.id } ?: top.peer
                            PeerDetailScreen(peer = peer, onBack = { pop() })
                        }
                        Screen.Config -> ConfigScreen(ui = ui, vm = vm, onBack = { pop() })
                        Screen.Settings -> SettingsScreen(
                            tsLogCount = tsLogCount,
                            appLogCount = appLogCount,
                            onOpenTsLog = { push(Screen.TsLog) },
                            onOpenAppLog = { push(Screen.AppLog) },
                            onBack = { pop() }
                        )
                        Screen.TsLog -> LogScreen(
                            title = "ts日志",
                            raw = ui.logs,
                            newestFirst = true,
                            paused = ui.logPaused,
                            onTogglePause = { vm.toggleLogPause() },
                            onClear = { vm.clearLogs() },
                            onBack = { pop() },
                            containerColor = null
                        )
                        Screen.AppLog -> LogScreen(
                            title = "程序日志",
                            raw = ui.crashLog,
                            newestFirst = false,
                            paused = false,
                            onTogglePause = null,
                            onClear = { vm.clearCrashLog() },
                            onBack = { pop() },
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    }
                }
            }
        }

        // ping 详情弹层
        AppBottomSheet(
            visible = pingSheetPeer != null,
            onDismiss = { closePingSheet() }
        ) {
            val peer = pingSheetPeer ?: lastPingPeer
            if (peer != null) {
                PingSheet(peer = peer, ping = ui.pings[peer.pingKey])
            }
        }

        // 长按菜单弹层
        AppBottomSheet(
            visible = actionPeer != null,
            onDismiss = { actionPeer = null }
        ) {
            val peer = actionPeer ?: lastActionPeer
            if (peer != null) {
                PeerActionSheet(
                    peer = peer,
                    isPinned = peer.id.isNotBlank() && peer.id in ui.pinnedPeers,
                    onCopy = { label, value -> copyToClipboard(label, value) },
                    onTogglePin = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.togglePin(peer.id)
                        actionPeer = null
                    },
                    onDismiss = { actionPeer = null }
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NodeCard(
    peers: List<Peer>,
    pinnedIds: Set<String>,
    pings: Map<String, PingState>,
    onPeerClick: (Peer) -> Unit,
    onPeerLongPress: (Peer) -> Unit,
    onPing: (Peer) -> Unit
) {
    val onlineCount = peers.count { it.online }
    val pinned = peers.filter { it.id.isNotBlank() && it.id in pinnedIds }
    val unpinned = peers.filter { it.id.isBlank() || it.id !in pinnedIds }
    val normal = unpinned.filter { it.tags.isEmpty() }
    val tagged = unpinned.filter { it.tags.isNotEmpty() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "节点 (在线 $onlineCount/${peers.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                if (peers.isEmpty()) {
                    Text(
                        "暂无节点（未启动，或 ACL 未允许看到其他设备）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (pinned.isNotEmpty()) {
                        NodeGroup(
                            title = "置顶设备",
                            peers = pinned,
                            pings = pings,
                            onPeerClick = onPeerClick,
                            onPeerLongPress = onPeerLongPress,
                            onPing = onPing
                        )
                    }
                    if (normal.isNotEmpty()) {
                        NodeGroup(
                            title = "普通设备",
                            peers = normal,
                            pings = pings,
                            onPeerClick = onPeerClick,
                            onPeerLongPress = onPeerLongPress,
                            onPing = onPing
                        )
                    }
                    if (tagged.isNotEmpty()) {
                        NodeGroup(
                            title = "tagged 设备",
                            peers = tagged,
                            pings = pings,
                            onPeerClick = onPeerClick,
                            onPeerLongPress = onPeerLongPress,
                            onPing = onPing
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp)
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    content()
                }
            }
        }
    }
}

@Composable
private fun PeerActionSheet(
    peer: Peer,
    isPinned: Boolean,
    onCopy: (String, String) -> Unit,
    onTogglePin: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            peer.name.ifBlank { peer.hostName },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        SheetAction(
            label = "复制 MagicDNS 域名",
            enabled = peer.dnsName.isNotBlank()
        ) {
            onCopy("MagicDNS", peer.dnsName)
            onDismiss()
        }
        SheetAction(
            label = "复制 IPv4",
            enabled = peer.ipv4 != null
        ) {
            peer.ipv4?.let { onCopy("IPv4", it) }
            onDismiss()
        }
        SheetAction(
            label = "复制 IPv6",
            enabled = peer.ipv6 != null
        ) {
            peer.ipv6?.let { onCopy("IPv6", it) }
            onDismiss()
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        SheetAction(
            label = if (isPinned) "取消置顶" else "置顶",
            enabled = peer.id.isNotBlank()
        ) {
            onTogglePin()
        }
    }
}

@Composable
private fun SheetAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    ) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Start
        )
    }
}

@Composable
private fun NodeGroup(
    title: String,
    peers: List<Peer>,
    pings: Map<String, PingState>,
    onPeerClick: (Peer) -> Unit,
    onPeerLongPress: (Peer) -> Unit,
    onPing: (Peer) -> Unit
) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
    peers.forEach { peer ->
        PeerRow(
            peer = peer,
            ping = pings[peer.pingKey],
            onClick = { onPeerClick(peer) },
            onLongClick = { onPeerLongPress(peer) },
            onPing = { onPing(peer) }
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeerRow(
    peer: Peer,
    ping: PingState?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPing: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val dotColor = when {
        peer.expired -> MaterialTheme.colorScheme.error
        peer.online -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val ip = peer.ipv4 ?: peer.ipv6 ?: "—"
    val hasLatency = (ping?.latencyMs ?: 0.0) > 0.0

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                )
                .padding(start = 4.dp, top = 10.dp, bottom = 10.dp, end = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    peer.name.ifBlank { peer.hostName },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    ip,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1
                )
            }

            // 弹层关闭后，延迟显示在 ping 按钮左侧
            if (hasLatency) {
                Text(
                    formatLatency(ping?.latencyMs ?: 0.0),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                Spacer(Modifier.width(4.dp))
            }

            IconButton(
                onClick = onPing,
                enabled = peer.online,
                modifier = Modifier.size(36.dp)
            ) {
                if (ping?.running == true) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Filled.Speed,
                        contentDescription = "Ping",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PingSheet(peer: Peer, ping: PingState?) {
    val samples = ping?.samples ?: emptyList()
    val last = samples.lastOrNull()
    val mode = when {
        ping?.endpoint?.isNotBlank() == true -> "直连 ${ping.endpoint}"
        ping?.peerRelay?.isNotBlank() == true -> "Peer Relay ${ping.peerRelay}"
        ping?.derpRegion?.isNotBlank() == true -> "DERP ${ping.derpRegion}"
        peer.curAddr.isNotBlank() -> "直连"
        peer.peerRelay.isNotBlank() -> "Peer Relay ${peer.peerRelay}"
        peer.relay.isNotBlank() -> "DERP ${peer.relay}"
        else -> "—"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
    ) {
        Text(
            "Pinging ${peer.name.ifBlank { peer.hostName }}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                mode,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                when {
                    last != null -> formatLatency(last)
                    samples.isNotEmpty() -> "超时"
                    else -> "…"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (last == null && samples.isNotEmpty())
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(12.dp))

        if (samples.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "正在 ping…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            PingChart(samples)
        }

        if (ping?.error?.isNotBlank() == true) {
            Spacer(Modifier.height(6.dp))
            Text(
                ping.error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun PingChart(samples: List<Double?>) {
    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val lineColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val step = remember(samples) {
        nicePingStep(samples.filterNotNull().maxOrNull() ?: 0.0)
    }
    val axisMax = step * 4

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
    ) {
        val left = 50.dp.toPx()
        val right = 10.dp.toPx()
        val top = 14.dp.toPx()
        val bottom = 24.dp.toPx()
        val plotW = (size.width - left - right).coerceAtLeast(1f)
        val plotH = (size.height - top - bottom).coerceAtLeast(1f)
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f)
        val hair = 1.dp.toPx()
        val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

        val n = samples.size
        fun xAt(i: Int): Float =
            if (n <= 1) left else left + plotW * i / (n - 1).toFloat()

        fun yAt(v: Double): Float =
            top + plotH * (1f - (v / axisMax)).toFloat()

        // 横向虚线网格 + Y 轴刻度（0 和 4 档）
        for (t in 0..4) {
            val y = top + plotH * (1f - t / 4f)
            drawLine(
                color = gridColor,
                start = Offset(left, y),
                end = Offset(left + plotW, y),
                strokeWidth = hair,
                pathEffect = dash
            )
            val value = step * t
            val text = if (t == 4) "${formatAxis(value)} ms" else formatAxis(value)
            val layout = textMeasurer.measure(text, labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    (left - layout.size.width - 6.dp.toPx()).coerceAtLeast(0f),
                    y - layout.size.height / 2f
                )
            )
        }

        // 纵向虚线网格 + X 轴刻度（1..n）
        for (i in 0 until n) {
            val x = xAt(i)
            drawLine(
                color = gridColor,
                start = Offset(x, top),
                end = Offset(x, top + plotH),
                strokeWidth = hair,
                pathEffect = dash
            )
            val layout = textMeasurer.measure("${i + 1}", labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(x - layout.size.width / 2f, top + plotH + 5.dp.toPx())
            )
        }

        // 坐标轴
        drawLine(axisColor, Offset(left, top), Offset(left, top + plotH), hair)
        drawLine(axisColor, Offset(left, top + plotH), Offset(left + plotW, top + plotH), hair)

        if (n == 0) return@Canvas

        // 折线：每次 ping 一个点，超时（null）处断开
        val points = samples.mapIndexed { i, v -> v?.let { Offset(xAt(i), yAt(it)) } }
        val path = Path()
        var prev: Offset? = null
        var prevIndex = -1
        points.forEachIndexed { i, point ->
            if (point == null) {
                prev = null
                prevIndex = -1
                return@forEachIndexed
            }
            val previous = prev
            if (previous == null) {
                path.moveTo(point.x, point.y)
            } else {
                val before = points.getOrNull(prevIndex - 1) ?: previous
                val after = points.getOrNull(i + 1) ?: point
                val c1x = previous.x + (point.x - before.x) / 6f
                val c1y = previous.y + (point.y - before.y) / 6f
                val c2x = point.x - (after.x - previous.x) / 6f
                val c2y = point.y - (after.y - previous.y) / 6f
                path.cubicTo(c1x, c1y, c2x, c2y, point.x, point.y)
            }
            prev = point
            prevIndex = i
        }

        clipRect(left, top, left + plotW, top + plotH) {
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        points.forEach { point ->
            if (point != null) {
                drawCircle(lineColor, radius = 3.dp.toPx(), center = point)
            }
        }
    }
}

/** 让 Y 轴分成 4 档的“整齐”步长（1/2/5 × 10^n）。 */
private fun nicePingStep(maxValue: Double): Double {
    val raw = (maxValue / 4.0).coerceAtLeast(0.5)
    val exponent = floor(log10(raw))
    val base = 10.0.pow(exponent)
    val normalized = raw / base
    val nice = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * base
}

private fun formatAxis(value: Double): String {
    val rounded = Math.round(value * 100.0) / 100.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
    else String.format(Locale.US, "%.1f", rounded)
}

private fun formatLatency(ms: Double): String =
    if (ms >= 100) String.format(Locale.US, "%.0f ms", ms)
    else String.format(Locale.US, "%.1f ms", ms)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDetailScreen(peer: Peer, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        peer.name.ifBlank { peer.hostName },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            DetailSection("基本") {
                DetailRow("设备名", peer.name)
                DetailRow("主机名", peer.hostName)
                DetailRow("MagicDNS", peer.dnsName)
                DetailRow("系统", peer.os)
                DetailRow("用户", peer.userName)
                DetailRow("标签", peer.tags.joinToString("，"))
            }

            DetailSection("地址") {
                DetailRow("IPv4", peer.ipv4 ?: "")
                DetailRow("IPv6", peer.ipv6 ?: "")
                peer.ips.filter { it != peer.ipv4 && it != peer.ipv6 }.forEach { extra ->
                    DetailRow("其他", extra)
                }
            }

            DetailSection("连接") {
                DetailRow("状态", peerStatusText(peer))
                DetailRow("直连地址", peer.curAddr)
                DetailRow("Peer Relay", peer.peerRelay)
                DetailRow("DERP", peer.relay)
                DetailRow("最后在线", if (peer.online) "" else peer.lastSeen)
            }

            DetailSection("流量") {
                DetailRow("上传", formatBytes(peer.txBytes))
                DetailRow("下载", formatBytes(peer.rxBytes))
            }

            DetailSection("角色") {
                DetailRow("当前出口节点", if (peer.exitNode) "是" else "否")
                DetailRow("可作出口节点", if (peer.exitNodeOption) "是" else "否")
                DetailRow("密钥过期", if (peer.expired) "是" else "否")
            }
        }
    }
}

private fun peerStatusText(peer: Peer): String = when {
    peer.expired -> "密钥已过期"
    peer.online && peer.active -> "在线 · 活跃"
    peer.online -> "在线"
    peer.lastSeen.isNotEmpty() -> "离线（最后在线 ${peer.lastSeen}）"
    else -> "离线"
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            content = content
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}"
    else String.format(Locale.US, "%.1f %s", value, units[unit])
}

@Composable
fun LogLine(
    line: String,
    containerColor: androidx.compose.ui.graphics.Color?,
    onCopyLine: (String) -> Unit,
    haptics: androidx.compose.ui.hapticfeedback.HapticFeedback
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCopyLine(line)
                    }
                )
            }
            .padding(vertical = 2.dp, horizontal = 4.dp)
    ) {
        Text(
            text = line,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = if (containerColor != null)
                MaterialTheme.colorScheme.onErrorContainer
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )
    }
}
@Composable
fun PowerToggle(running: Boolean, onToggle: () -> Unit) {
    val trackColor by animateColorAsState(
        targetValue = if (running) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "trackColor"
    )
    val knobColor by animateColorAsState(
        targetValue = if (running) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.outline,
        label = "knobColor"
    )
    val knobOffset by animateDpAsState(
        targetValue = if (running) 24.dp else 0.dp,
        animationSpec = tween(180),
        label = "knobOffset"
    )

    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(trackColor)
            .toggleable(
                value = running,
                role = Role.Switch,
                onValueChange = { onToggle() }
            )
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset)
                .size(28.dp)
                .clip(CircleShape)
                .background(knobColor)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(ui: UiState, vm: MainViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            ConfigSection(ui, vm)
            SettingsSection()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    tsLogCount: Int,
    appLogCount: Int,
    onOpenTsLog: () -> Unit,
    onOpenAppLog: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                SettingsNavRow(
                    title = "ts日志",
                    subtitle = "$tsLogCount 条",
                    onClick = onOpenTsLog
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
                SettingsNavRow(
                    title = "程序日志",
                    subtitle = "$appLogCount 条",
                    onClick = onOpenAppLog
                )
            }
        }
    }
}

@Composable
private fun SettingsNavRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogScreen(
    title: String,
    raw: String,
    newestFirst: Boolean,
    paused: Boolean,
    onTogglePause: (() -> Unit)?,
    onClear: () -> Unit,
    onBack: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color?
) {
    val ctx = LocalContext.current
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val haptics = LocalHapticFeedback.current

    val lines = remember(raw, newestFirst) {
        if (raw.isBlank()) emptyList()
        else raw.split("\n").filter { it.isNotBlank() }.let { if (newestFirst) it.reversed() else it }
    }
    // 暂停时冻结当前显示内容
    var frozenLines by remember { mutableStateOf<List<String>>(emptyList()) }
    val displayLines = if (paused) frozenLines else lines
    LaunchedEffect(paused, lines) {
        if (!paused) frozenLines = lines
    }

    val textColor = if (containerColor != null) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    fun copyAll() {
        val text = displayLines.joinToString("\n")
        clipboard.setPrimaryClip(ClipData.newPlainText(title, text))
        Toast.makeText(ctx, "已复制 ${displayLines.size} 条日志", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$title (${displayLines.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (onTogglePause != null) {
                        IconButton(onClick = onTogglePause) {
                            Icon(
                                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = if (paused) "继续" else "暂停"
                            )
                        }
                    }
                    IconButton(onClick = { copyAll() }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制全部")
                    }
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Delete, contentDescription = "清除")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (displayLines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (paused) "已暂停" else "无日志",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )
            }
        } else {
            val listState = rememberLazyListState()
            LaunchedEffect(displayLines.size) {
                if (!paused) listState.scrollToItem(0)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp)
            ) {
                items(displayLines) { line ->
                    LogLine(
                        line = line,
                        containerColor = containerColor,
                        onCopyLine = { copied ->
                            clipboard.setPrimaryClip(ClipData.newPlainText("日志", copied))
                            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                        },
                        haptics = haptics
                    )
                }
            }
        }
    }
}

@Composable
fun ConfigSection(ui: UiState, vm: MainViewModel) {
    var socks by remember { mutableStateOf(ui.socksAddr) }
    var hostname by remember { mutableStateOf(ui.hostname) }
    var tsnetDir by remember { mutableStateOf(ui.tsnetDir) }
    var showDirPicker by remember { mutableStateOf(false) }

    LaunchedEffect(ui.socksAddr, ui.hostname, ui.tsnetDir) {
        socks = ui.socksAddr
        hostname = ui.hostname
        tsnetDir = ui.tsnetDir
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "基本配置",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = socks,
                onValueChange = { socks = it },
                label = { Text("SOCKS5 监听地址") },
                placeholder = { Text("127.0.0.1:1080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = hostname,
                onValueChange = { hostname = it },
                label = { Text("Tailscale 设备名") },
                placeholder = { Text("ts-socks5") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            // 数据目录：只读展示 + 目录选择器，无需手动输入路径
            Box {
                OutlinedTextField(
                    value = tsnetDir.ifBlank { "默认（应用私有目录/tsnet）" },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("tsnet 数据目录 (可选)") },
                    singleLine = true,
                    trailingIcon = {
                        Icon(Icons.Filled.Folder, null)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // 覆盖一层可点击区域，点击整行都能打开目录选择器
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { showDirPicker = true }
                )
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { vm.saveConfig(socks, hostname, tsnetDir) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Save, null)
                Spacer(Modifier.width(4.dp))
                Text("保存配置")
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "Clash 配置参考: 将 100.64.0.0/10 和 fd7a:115c:a1e0::/48 路由到 socks5://127.0.0.1:1080",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showDirPicker) {
        DirectoryPickerDialog(
            initialPath = tsnetDir,
            onDismiss = { showDirPicker = false },
            onConfirm = { picked ->
                tsnetDir = picked
                showDirPicker = false
            }
        )
    }
}

@Composable
fun DirectoryPickerDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val ctx = LocalContext.current
    val appRoot = remember(ctx) { ctx.filesDir }
    val externalRoot = remember(ctx) { ctx.getExternalFilesDir(null) }

    var currentDir by remember {
        mutableStateOf(
            File(initialPath.ifBlank { appRoot.absolutePath })
                .takeIf { it.isDirectory && it.canRead() } ?: appRoot
        )
    }
    var reload by remember { mutableStateOf(0) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    val children by produceState(emptyList<File>(), currentDir, reload) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                currentDir.listFiles { f -> f.isDirectory }
                    ?.sortedBy { it.name.lowercase() }
                    ?: emptyList()
            }.getOrElse { emptyList() }
        }
    }
    val writable = remember(currentDir, reload) {
        runCatching { currentDir.canWrite() }.getOrDefault(false)
    }
    val parent = currentDir.parentFile

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择 tsnet 数据目录") },
        text = {
            Column {
                Text(
                    currentDir.absolutePath,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { currentDir = appRoot },
                        label = { Text("应用私有目录") }
                    )
                    if (externalRoot != null) {
                        AssistChip(
                            onClick = { currentDir = externalRoot },
                            label = { Text("外部私有目录") }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                ) {
                    if (parent != null) {
                        item(key = "..") {
                            DirectoryRow(name = "..", onClick = { currentDir = parent })
                        }
                    }
                    if (children.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                "（无子目录）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp)
                            )
                        }
                    }
                    items(children, key = { it.absolutePath }) { dir ->
                        DirectoryRow(name = dir.name, onClick = { currentDir = dir })
                    }
                }

                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showNewFolderDialog = true }) {
                        Icon(Icons.Filled.CreateNewFolder, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建文件夹")
                    }
                    if (!writable) {
                        Text(
                            "目录不可写",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(currentDir.absolutePath) },
                enabled = writable
            ) {
                Text("使用此目录")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onConfirm("") }) {
                    Text("恢复默认")
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("tsnet") }
        val nameInvalid = folderName.isBlank() || folderName.contains('/')
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("文件夹名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !nameInvalid,
                    onClick = {
                        val target = File(currentDir, folderName.trim())
                        val ok = runCatching { target.mkdirs() || target.isDirectory }
                            .getOrDefault(false)
                        if (ok) {
                            currentDir = target
                            reload++
                        }
                        showNewFolderDialog = false
                    }
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun DirectoryRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Folder,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SettingsSection() {
    val ctx = LocalContext.current

    var batteryIgnored by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(ctx.packageName)
            } else {
                true
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "保活设置",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            // Battery optimization
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "电池优化",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (batteryIgnored) "已豁免" else "未豁免，可能被系统杀后台",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (batteryIgnored)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
                if (!batteryIgnored && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${ctx.packageName}")
                                }
                                ctx.startActivity(intent)
                                batteryIgnored = true
                            } catch (_: Exception) {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                ctx.startActivity(intent)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("设置", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    Icon(
                        Icons.Filled.CheckCircle,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Persistent notification
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "常驻通知",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "确保通知不可被系统清除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = {
                        try {
                            val intent = Intent().apply {
                                action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            }
                            ctx.startActivity(intent)
                        } catch (_: Exception) {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${ctx.packageName}")
                            }
                            ctx.startActivity(intent)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("设置", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

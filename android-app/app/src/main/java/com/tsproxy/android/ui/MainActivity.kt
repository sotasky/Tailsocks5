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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tsproxy.android.ShareBus
import com.tsproxy.android.SharePreparer
import com.tsproxy.android.ShareRequest
import com.tsproxy.android.TaildropPrefs
import com.tsproxy.android.service.TsProxyService
import com.tsproxy.android.taildrop.FileTarget
import com.tsproxy.android.taildrop.TAILDROP_DIR_NAME
import com.tsproxy.android.taildrop.TaildropDest
import com.tsproxy.android.taildrop.TaildropEntry
import com.tsproxy.android.taildrop.StoredFile
import com.tsproxy.android.taildrop.Transfers
import com.tsproxy.android.taildrop.WaitingFile
import com.tsproxy.android.taildrop.guessMime
import com.tsproxy.android.taildrop.parseFileTargets
import com.tsproxy.android.taildrop.parseTransfers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tsproxy.Tsproxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIncomingIntent(intent)
        setContent {
            val ui by vm.ui.collectAsStateWithLifecycle()
            TsProxyTheme(ui.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(vm = vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_TAILDROP, false)) {
            ShareBus.openTaildrop.value = true
        }
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = ArrayList<Uri>()
        if (action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val single = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (single != null) uris.add(single)
        } else {
            @Suppress("DEPRECATION")
            val multiple = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (multiple != null) uris.addAll(multiple)
        }
        if (uris.isEmpty()) {
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "没有可发送的文件", Toast.LENGTH_SHORT).show()
            return
        }
        ShareBus.post(ShareRequest(uris, intent.type))
    }

    companion object {
        const val EXTRA_OPEN_TAILDROP = "open_taildrop"
    }
}

/** 从右侧滑入的二级页面。 */
private sealed interface Screen {
    data class PeerDetail(val peer: Peer) : Screen
    data object Socks : Screen
    data object Settings : Screen
    data object Appearance : Screen
    data object Account : Screen
    data object Taildrop : Screen
    data object TaildropDir : Screen
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

    val shareRequest by ShareBus.request.collectAsStateWithLifecycle()
    val openTaildrop by ShareBus.openTaildrop.collectAsStateWithLifecycle()

    var navStack by remember { mutableStateOf<List<Screen>>(emptyList()) }
    fun push(screen: Screen) { navStack = navStack + screen }
    fun pop() { navStack = navStack.dropLast(1) }

    // Taildrop destination pickers / permission prompts.
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers do not offer persistable grants; the URI is
                // still usable for this process.
            }
            if (TaildropDest.canWriteTree(ctx, uri.toString())) {
                vm.setTaildropDest(TaildropPrefs.encodeTree(uri.toString()))
            } else {
                Toast.makeText(ctx, "该文件夹不可写，请重新选择", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.setTaildropDest(TaildropPrefs.DEST_MEDIA)
        } else {
            Toast.makeText(ctx, "未授权存储权限，无法保存到下载目录", Toast.LENGTH_SHORT).show()
        }
    }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun chooseDefaultDir() {
        if (TaildropDest.legacyPermissionNeeded(ctx)) {
            storagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.setTaildropDest(TaildropPrefs.DEST_MEDIA)
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(openTaildrop) {
        if (openTaildrop) {
            ShareBus.openTaildrop.value = false
            vm.refreshTaildrop()
            navStack = listOf(Screen.Taildrop)
        }
    }

    BackHandler(enabled = navStack.isNotEmpty()) { pop() }
    BackHandler(enabled = shareRequest != null) { ShareBus.clear() }

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

                // 两行身份：登录账号 + 所在 Tailnet。
                // 账号被邀请进别人的 tailnet 时，两者会不同。
                Column(Modifier.weight(1f)) {
                    Text(
                        ui.account,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (ui.tailnet.isNotBlank()) {
                        Text(
                            ui.tailnet,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

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

            // 本地代理卡片：显示 SOCKS5 信息，点击进入设置
            Card(
                onClick = { push(Screen.Socks) },
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
                            "本地代理",
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
                    StatusRow("SOCKS5", ui.socksAddr)
                    StatusRow("状态", if (ui.running) "运行中" else "未启动")

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
                LoginCard(
                    url = ui.loginUrl,
                    hostname = ui.hostname,
                    onSaveHostname = { vm.saveHostname(it) }
                )
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
                        Screen.Socks -> SocksScreen(ui = ui, vm = vm, onBack = { pop() })
                        Screen.Settings -> SettingsScreen(
                            ui = ui,
                            vm = vm,
                            tsLogCount = tsLogCount,
                            appLogCount = appLogCount,
                            dirLabel = TaildropDest.describe(ctx, ui.taildropDest),
                            onOpenAccount = { push(Screen.Account) },
                            onOpenAppearance = { push(Screen.Appearance) },
                            onOpenTaildrop = { vm.refreshTaildrop(); push(Screen.Taildrop) },
                            onUseDefaultDir = { chooseDefaultDir() },
                            onPickCustomDir = { treeLauncher.launch(null) },
                            onOpenTsLog = { push(Screen.TsLog) },
                            onOpenAppLog = { push(Screen.AppLog) },
                            onBack = { pop() }
                        )
                        Screen.Account -> AccountScreen(
                            ui = ui,
                            vm = vm,
                            onAdded = { navStack = emptyList() },
                            onBack = { pop() }
                        )
                        Screen.Appearance -> AppearanceScreen(
                            ui = ui,
                            vm = vm,
                            onBack = { pop() }
                        )
                        Screen.Taildrop -> TaildropScreen(
                            ui = ui,
                            vm = vm,
                            onUseDefaultDir = { chooseDefaultDir() },
                            onPickCustomDir = { treeLauncher.launch(null) },
                            onOpenDirectory = {
                                if (!TaildropDest.openDirectory(ctx, ui.taildropDest)) {
                                    push(Screen.TaildropDir)
                                }
                            },
                            onBack = { pop() }
                        )
                        Screen.TaildropDir -> TaildropDirScreen(
                            ui = ui,
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

        // 系统分享面板：选择目标设备并发送
        val share = shareRequest
        if (share != null) {
            ShareScreen(
                request = share,
                vm = vm,
                onFinished = { ShareBus.clear() }
            )
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
    val pinned = peers.filter { it.self || (it.id.isNotBlank() && it.id in pinnedIds) }
        .sortedBy { if (it.self) 0 else 1 }
    val unpinned = peers.filter { !it.self && (it.id.isBlank() || it.id !in pinnedIds) }
    val normal = unpinned.filter { it.tags.isEmpty() }
    val tagged = unpinned.filter { it.tags.isNotEmpty() }
    // Ordinary nodes are grouped by their Tailscale user (display name).
    val normalByUser = normal.groupBy { it.userName.ifBlank { "未知用户" } }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "设备 (在线 $onlineCount/${peers.size})",
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
                        "暂无设备（未启动，或 ACL 未允许看到其他设备）",
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
                    normalByUser.entries
                        .sortedBy { it.key.lowercase() }
                        .forEach { (user, group) ->
                            NodeGroup(
                                title = user,
                                peers = group,
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

        if (!peer.self) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            SheetAction(
                label = if (isPinned) "取消置顶" else "置顶",
                enabled = peer.id.isNotBlank()
            ) {
                onTogglePin()
            }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        peer.name.ifBlank { peer.hostName },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (peer.self) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "此设备",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
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

            // Ping 对自己没有意义，本设备行不显示该按钮
            if (!peer.self) {
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
                DetailRow("设备名", peer.name, copyable = true)
                DetailRow("主机名", peer.hostName, copyable = true)
                DetailRow("MagicDNS", peer.dnsName, copyable = true)
                DetailRow("系统", peer.os, copyable = true)
                DetailRow("用户", peer.userName, copyable = true)
                DetailRow("标签", peer.tags.joinToString("，"), copyable = true)
            }

            DetailSection("地址") {
                DetailRow("IPv4", peer.ipv4 ?: "", copyable = true)
                DetailRow("IPv6", peer.ipv6 ?: "", copyable = true)
                peer.ips.filter { it != peer.ipv4 && it != peer.ipv6 }.forEach { extra ->
                    DetailRow("其他", extra, copyable = true)
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
private fun DetailRow(label: String, value: String, copyable: Boolean = false) {
    if (value.isBlank()) return
    val ctx = LocalContext.current
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (copyable) {
                    Modifier.clickable {
                        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                        Toast.makeText(ctx, "已复制$label", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Modifier
                }
            )
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
        if (copyable) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = "复制",
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
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
private fun SocksScreen(ui: UiState, vm: MainViewModel, onBack: () -> Unit) {
    var socks by remember { mutableStateOf(ui.socksAddr) }
    LaunchedEffect(ui.socksAddr) { socks = ui.socksAddr }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地代理") },
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
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "SOCKS5",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = socks,
                        onValueChange = { socks = it },
                        label = { Text("监听地址") },
                        placeholder = { Text("127.0.0.1:1080") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = { vm.saveSocks(socks) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Save, null)
                        Spacer(Modifier.width(4.dp))
                        Text("保存配置")
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "修改后需要停止再启动连接才会生效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Clash 配置参考: 将 100.64.0.0/10 和 fd7a:115c:a1e0::/48 路由到 socks5://${ui.socksAddr}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    ui: UiState,
    vm: MainViewModel,
    tsLogCount: Int,
    appLogCount: Int,
    dirLabel: String,
    onOpenAccount: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenTaildrop: () -> Unit,
    onUseDefaultDir: () -> Unit,
    onPickCustomDir: () -> Unit,
    onOpenTsLog: () -> Unit,
    onOpenAppLog: () -> Unit,
    onBack: () -> Unit
) {
    val accountEmail = ui.account.ifBlank { ui.activeAccount?.email ?: "" }
    val accountTailnet = ui.tailnet.ifBlank { ui.activeAccount?.tailnet ?: "" }
    val accountLoggedOut = ui.activeAccount?.loggedOut == true
    var showDestDialog by remember { mutableStateOf(false) }

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
            // 当前账号：名字 + 邮箱，点击进入账号页
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAccount)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            accountEmail.ifBlank { "未登录" },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            when {
                                accountTailnet.isNotBlank() -> accountTailnet
                                accountLoggedOut -> "已登出，需要重新认证"
                                accountEmail.isNotBlank() -> "未连接 Tailnet"
                                else -> "点击登录或切换账号"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 外观：点击进入主题设置
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAppearance)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Palette,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "外观",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "界面主题：${ui.themeMode.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "Taildrop",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )

            // Taildrop：接收开关、保存位置、待接收、历史
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "接收文件",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                when {
                                    !ui.running -> "连接后才能接收"
                                    ui.taildropInfo.adminEnabled.not() && ui.running ->
                                        "管理后台未开启 Send Files"
                                    ui.taildropEnabled -> "收到文件后自动保存"
                                    else -> "已关闭自动保存"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = ui.taildropEnabled,
                            onCheckedChange = { vm.setTaildropEnabled(it) }
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    SettingsNavRow(
                        title = "接收文件目录",
                        subtitle = dirLabel,
                        onClick = { showDestDialog = true }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    SettingsNavRow(
                        title = "待接收",
                        subtitle = "${ui.waitingFiles.size} 个",
                        onClick = onOpenTaildrop
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    SettingsNavRow(
                        title = "接收历史",
                        subtitle = "${ui.taildropHistory.size} 条",
                        onClick = onOpenTaildrop
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

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

            // 原「配置」页删除后，保活设置并入设置页
            SettingsSection()
        }
    }

    if (showDestDialog) {
        AlertDialog(
            onDismissRequest = { showDestDialog = false },
            title = { Text("接收文件目录") },
            text = {
                Column {
                    Text(
                        "默认保存在系统下载目录的 $TAILDROP_DIR_NAME 文件夹。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "当前：$dirLabel",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDestDialog = false
                    onUseDefaultDir()
                }) { Text("使用默认") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDestDialog = false
                    onPickCustomDir()
                }) { Text("选择文件夹…") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceScreen(
    ui: UiState,
    vm: MainViewModel,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外观") },
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
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Text(
                        "界面主题",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "选择 App 的显示主题，立即生效",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    ThemeModeSelector(
                        selected = ui.themeMode,
                        onSelect = { vm.setThemeMode(it) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "「跟随系统」会随手机的深色模式自动切换，浅色 / 黑暗则固定使用所选主题。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/** 三选一的主题分段按钮。 */
@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThemeMode.entries.forEach { mode ->
            val active = mode == selected
            val background by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
                label = "themeBg"
            )
            val contentColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "themeContent"
            )
            val borderColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                label = "themeBorder"
            )
            val icon = when (mode) {
                ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
                ThemeMode.LIGHT -> Icons.Filled.LightMode
                ThemeMode.DARK -> Icons.Filled.DarkMode
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(84.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(background)
                    .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                    .selectable(
                        selected = active,
                        role = Role.RadioButton,
                        onClick = { onSelect(mode) }
                    )
                    .padding(horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    mode.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaildropScreen(
    ui: UiState,
    vm: MainViewModel,
    onUseDefaultDir: () -> Unit,
    onPickCustomDir: () -> Unit,
    onOpenDirectory: () -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val dirLabel = TaildropDest.describe(ctx, ui.taildropDest)
    var showDestDialog by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshTaildrop() }

    LaunchedEffect(ui.taildropNotice) {
        if (ui.taildropNotice.isNotBlank()) {
            Toast.makeText(ctx, ui.taildropNotice, Toast.LENGTH_SHORT).show()
            vm.consumeTaildropNotice()
        }
    }

    val needStoragePermission = TaildropDest.legacyPermissionNeeded(ctx)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文件传输") },
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
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("接收文件", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when {
                                    !ui.running -> "连接后才能接收"
                                    !ui.taildropInfo.adminEnabled ->
                                        "管理后台未开启 Send Files"
                                    ui.taildropEnabled -> "自动保存到 $dirLabel"
                                    else -> "仅提示，不自动保存"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = ui.taildropEnabled,
                            onCheckedChange = { vm.setTaildropEnabled(it) }
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    SettingsNavRow(
                        title = "接收文件目录",
                        subtitle = dirLabel,
                        onClick = { showDestDialog = true }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (needStoragePermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "需要存储权限才能保存到下载目录",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "未授权时无法接收文件保存到 下载/$TAILDROP_DIR_NAME。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onUseDefaultDir) { Text("授权") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 待接收（tsnet staging）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "待接收",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${ui.waitingFiles.size} 个",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    if (ui.waitingFiles.isEmpty()) {
                        Text(
                            if (ui.running) "暂无待接收文件" else "连接后才能接收",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        ui.waitingFiles.forEachIndexed { index, file ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                )
                            }
                            WaitingFileRow(
                                file = file,
                                enabled = ui.running,
                                onSave = {
                                    if (needStoragePermission) onUseDefaultDir()
                                    else vm.saveWaitingFile(file)
                                },
                                onDelete = { vm.deleteWaitingFile(file.name) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 接收历史
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "接收历史",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val hasAny = ui.taildropHistory.isNotEmpty() ||
                                TaildropDest.hasFiles(ctx, ui.taildropDest)
                            if (!hasAny) {
                                Toast.makeText(
                                    ctx, "还没有接收文件", Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                onOpenDirectory()
                            }
                        }) { Text("打开目录") }
                        if (ui.taildropHistory.isNotEmpty()) {
                            TextButton(onClick = { confirmClearHistory = true }) {
                                Text("清空")
                            }
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    if (ui.taildropHistory.isEmpty()) {
                        Text(
                            "暂无接收记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        ui.taildropHistory.forEachIndexed { index, entry ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                )
                            }
                            HistoryRow(
                                entry = entry,
                                onClick = {
                                    if (entry.uri.isNotBlank()) {
                                        openSavedFile(ctx, entry.uri, entry.savedName)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Taildrop 需要在 Tailscale 管理后台开启 Send Files，且只能在自己的设备之间传输。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showDestDialog) {
        AlertDialog(
            onDismissRequest = { showDestDialog = false },
            title = { Text("接收文件目录") },
            text = { Text("当前：$dirLabel") },
            confirmButton = {
                TextButton(onClick = {
                    showDestDialog = false
                    onUseDefaultDir()
                }) { Text("使用默认") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDestDialog = false
                    onPickCustomDir()
                }) { Text("选择文件夹…") }
            }
        )
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("清空接收历史") },
            text = {
                Text("是否同时删除这些记录对应的源文件？\n删除后无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearHistory = false
                    vm.clearTaildropHistory(deleteFiles = true)
                }) {
                    Text("删除文件", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmClearHistory = false
                    vm.clearTaildropHistory(deleteFiles = false)
                }) { Text("仅清空记录") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaildropDirScreen(ui: UiState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf<List<StoredFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(ui.taildropDest, ui.taildropHistory.size) {
        loading = true
        files = withContext(Dispatchers.IO) { TaildropDest.listFiles(ctx, ui.taildropDest) }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("接收目录") },
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
            Text(
                TaildropDest.describe(ctx, ui.taildropDest),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            when {
                loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("正在读取目录…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                files.isEmpty() -> {
                    Text(
                        "目录为空",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            files.forEachIndexed { index, file ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (file.uri.startsWith("content://")) {
                                                openSavedFile(ctx, file.uri, file.name)
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            file.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            buildString {
                                                append(formatBytes(file.size))
                                                if (file.modified > 0) {
                                                    append(" · ")
                                                    append(
                                                        SimpleDateFormat(
                                                            "MM-dd HH:mm",
                                                            Locale.getDefault()
                                                        ).format(Date(file.modified))
                                                    )
                                                }
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (file.uri.startsWith("content://")) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = "打开",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WaitingFileRow(
    file: WaitingFile,
    enabled: Boolean,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                formatBytes(file.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onSave, enabled = enabled) { Text("保存") }
        TextButton(onClick = onDelete, enabled = enabled) { Text("删除") }
    }
}

@Composable
private fun HistoryRow(entry: TaildropEntry, onClick: () -> Unit) {
    val canOpen = entry.uri.startsWith("content://")
    val time = remember(entry.savedAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.savedAt))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canOpen, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.savedName.ifBlank { entry.name },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(formatBytes(entry.size))
                    append(" · ")
                    append(time)
                    if (entry.accountEmail.isNotBlank()) {
                        append(" · ")
                        append(entry.accountEmail)
                    }
                    if (entry.status == "failed") {
                        append(" · 失败")
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (entry.status == "failed") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.status == "failed" && entry.error.isNotBlank()) {
                Text(
                    entry.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (canOpen) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "打开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun openSavedFile(ctx: Context, uriString: String, name: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriString), guessMime(name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    } catch (t: Throwable) {
        Toast.makeText(ctx, "无法打开：${t.message}", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareScreen(
    request: ShareRequest,
    vm: MainViewModel,
    onFinished: () -> Unit
) {
    val ctx = LocalContext.current
    var preparing by remember { mutableStateOf(true) }
    var prepared by remember { mutableStateOf<List<com.tsproxy.android.SharePreparer.Prepared>>(emptyList()) }
    var prepareError by remember { mutableStateOf("") }
    var targets by remember { mutableStateOf<List<FileTarget>>(emptyList()) }
    var targetsError by remember { mutableStateOf("") }
    var transfers by remember { mutableStateOf(Transfers()) }
    var sending by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(request) {
        try {
            prepared = SharePreparer.copyToCache(ctx, request)
        } catch (t: Throwable) {
            prepareError = t.message ?: "无法读取分享的文件"
        }
        preparing = false
    }

    LaunchedEffect(preparing) {
        if (preparing) return@LaunchedEffect
        while (true) {
            val (list, error) = withContext(Dispatchers.IO) {
                parseFileTargets(Tsproxy.getFileTargets() ?: "{}")
            }
            targets = list
            targetsError = if (list.isEmpty()) error else ""
            delay(2000)
        }
    }

    LaunchedEffect(sending) {
        if (!sending) return@LaunchedEffect
        var idle = 0
        while (true) {
            val t = withContext(Dispatchers.IO) { parseTransfers(Tsproxy.getTransfers() ?: "{}") }
            transfers = t
            if (t.active) {
                idle = 0
            } else if (t.jobs.isEmpty()) {
                idle++
                if (idle >= 8) {
                    prepareError = "发送未开始，请确认连接和 Send Files 开关"
                    sending = false
                    break
                }
            } else {
                sending = false
                done = true
                SharePreparer.cleanup(ctx)
                break
            }
            delay(600)
        }
    }

    fun finish() {
        // While a transfer is running the service still reads these files;
        // it deletes them itself when the batch ends.
        if (!sending) SharePreparer.cleanup(ctx)
        onFinished()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("发送文件") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭")
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
                Text(
                    "${request.uris.size} 个文件",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))

                when {
                    preparing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("正在准备文件…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    prepareError.isNotBlank() -> {
                        Text(prepareError, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { finish() }) { Text("关闭") }
                    }
                    done -> {
                        Text("发送完成", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        val failed = transfers.jobs.filter { it.error.isNotBlank() }
                        if (failed.isEmpty()) {
                            Text(
                                "${transfers.jobs.size} 个文件已发送",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            failed.forEach {
                                Text(
                                    "${it.name}：${it.error}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { finish() }) { Text("完成") }
                    }
                    sending -> {
                        Text("正在发送…", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        val job = transfers.jobs.lastOrNull()
                        transfers.jobs.forEach { t ->
                            Text(t.name, style = MaterialTheme.typography.bodyMedium)
                            val pct = if (t.total > 0) (t.sent * 100 / t.total).toInt() else 0
                            LinearProgressIndicator(
                                progress = if (t.total > 0) t.sent.toFloat() / t.total else 0f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            )
                            Text(
                                if (t.error.isNotBlank()) t.error else "$pct%",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (t.error.isNotBlank()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        if (job == null) {
                            Text("等待开始…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> {
                        if (targetsError.isNotBlank()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("无法发送", style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(4.dp))
                                    Text(targetsError, style = MaterialTheme.typography.bodySmall)
                                    if (targetsError.contains("未启动") || targetsError.contains("未连接")) {
                                        Spacer(Modifier.height(8.dp))
                                        Button(onClick = { vm.startProxy() }) { Text("启动连接") }
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        Text(
                            "选择接收设备",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        if (targets.isEmpty()) {
                            Text(
                                "没有可用设备",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    targets.forEachIndexed { index, target ->
                                        if (index > 0) {
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                            )
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = target.online) {
                                                    if (prepared.isEmpty()) return@clickable
                                                    scope.launch {
                                                        val running = withContext(Dispatchers.IO) {
                                                            Tsproxy.isRunning()
                                                        }
                                                        if (!running) {
                                                            Toast.makeText(
                                                                ctx, "请先启动连接",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                            return@launch
                                                        }
                                                        val current = withContext(Dispatchers.IO) {
                                                            parseTransfers(Tsproxy.getTransfers() ?: "{}")
                                                        }
                                                        if (current.active) {
                                                            Toast.makeText(
                                                                ctx, "已有发送任务在进行",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                            return@launch
                                                        }
                                                        done = false
                                                        sending = true
                                                        TsProxyService.sendFiles(
                                                            ctx,
                                                            target.id,
                                                            prepared.map { it.file.absolutePath }
                                                        )
                                                    }
                                                }
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(8.dp)
                                                    .background(
                                                        if (target.online) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.outline,
                                                        CircleShape
                                                    )
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    target.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = if (target.online)
                                                        MaterialTheme.colorScheme.onSurface
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    if (target.online) target.os.ifBlank { "在线" }
                                                    else "设备离线",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (target.online) {
                                                Icon(
                                                    Icons.Filled.ChevronRight,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountScreen(
    ui: UiState,
    vm: MainViewModel,
    onAdded: () -> Unit,
    onBack: () -> Unit
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var deleteTsData by remember { mutableStateOf(false) }
    var deleteTaildropFiles by remember { mutableStateOf(false) }
    val active = ui.activeAccount

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账号") },
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
            // 2.1 已登录账号列表
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(
                        "账号",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    if (ui.accounts.isEmpty()) {
                        Text(
                            "暂无账号，点击下方“新增账号”登录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    } else {
                        ReorderableAccountList(
                            accounts = ui.accounts,
                            onMove = { from, to -> vm.moveAccount(from, to) },
                            row = { account, isLast ->
                                AccountRow(
                                    account = account,
                                    selected = account.id == ui.activeAccountId,
                                    running = ui.running,
                                    onClick = { vm.selectAccount(account) }
                                )
                                if (!isLast) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                    )
                                }
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 2.2 操作
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "操作",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    AccountActionRow(
                        title = "新增账号",
                        subtitle = "退出当前连接，用新的身份重新登录",
                        onClick = {
                            vm.addAccount()
                            // 回到主页，展示新的登录链接
                            onAdded()
                        }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    AccountActionRow(
                        title = "重新认证",
                        subtitle = "为当前账号重新获取登录链接",
                        enabled = active != null,
                        onClick = { vm.reauthenticate() }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    AccountActionRow(
                        title = "登出",
                        subtitle = "可选择是否删除本地数据和 Taildrop 文件",
                        enabled = active != null,
                        destructive = true,
                        onClick = {
                            deleteTsData = false
                            deleteTaildropFiles = false
                            confirmLogout = true
                        }
                    )
                }
            }

            // 认证链接：新增账号、重新认证或登出后重新连接时出现
            if (ui.statusText.isNotBlank() && ui.statusText != "Running" && ui.statusText != "Stopped") {
                Spacer(Modifier.height(8.dp))
                Text(
                    ui.statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (ui.loginUrl.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                LoginCard(
                    url = ui.loginUrl,
                    hostname = ui.hostname,
                    onSaveHostname = { vm.saveHostname(it) }
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "每个账号使用独立的 Tailscale 数据目录；切换账号会先断开当前连接再启动所选账号。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("登出") },
            text = {
                Column {
                    Text(
                        "不删除 tsnet 数据时，账号会保留在列表，下次连接需要重新认证。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                deleteTsData = !deleteTsData
                                if (!deleteTsData) deleteTaildropFiles = false
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = deleteTsData,
                            onCheckedChange = {
                                deleteTsData = it
                                if (!it) deleteTaildropFiles = false
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text("删除 tsnet 数据", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "设备身份、登录状态；删除后该账号会从列表移除",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Only meaningful together with the tsnet data; keeping the
                    // account keeps its Taildrop files too.
                    val taildropEnabled = deleteTsData
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = taildropEnabled) {
                                if (deleteTsData) deleteTaildropFiles = !deleteTaildropFiles
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = taildropEnabled && deleteTaildropFiles,
                            enabled = taildropEnabled,
                            onCheckedChange = {
                                if (deleteTsData) deleteTaildropFiles = it
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "删除 Taildrop 文件",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (taildropEnabled)
                                    MaterialTheme.colorScheme.onSurface
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                            Text(
                                if (taildropEnabled) "该账号的接收历史和已保存的文件"
                                else "需先勾选“删除 tsnet 数据”",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = if (taildropEnabled) 1f else 0.38f
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    vm.logout(deleteTsData, deleteTaildropFiles)
                }) {
                    Text("登出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun ReorderableAccountList(
    accounts: List<Account>,
    onMove: (Int, Int) -> Unit,
    row: @Composable (Account, Boolean) -> Unit
) {
    val heights = remember { mutableStateMapOf<String, Int>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    var startTop by remember { mutableStateOf(0f) }
    var targetIndex by remember { mutableStateOf(-1) }
    val currentAccounts by rememberUpdatedState(accounts)
    val currentOnMove by rememberUpdatedState(onMove)

    fun topOf(index: Int): Float {
        var y = 0f
        val n = index.coerceIn(0, currentAccounts.size)
        for (i in 0 until n) {
            y += heights[currentAccounts[i].id] ?: 0
        }
        return y
    }

    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            accounts.forEachIndexed { index, account ->
                val isDragging = account.id == draggingId
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { heights[account.id] = it.size.height }
                        .alpha(if (isDragging) 0.25f else 1f)
                        .pointerInput(account.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    val from = currentAccounts.indexOfFirst { it.id == account.id }
                                    if (from >= 0) {
                                        draggingId = account.id
                                        dragOffset = 0f
                                        startTop = topOf(from)
                                        targetIndex = from
                                    }
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val from = currentAccounts.indexOfFirst { it.id == account.id }
                                    val height = heights[account.id] ?: 0
                                    if (from >= 0 && height > 0) {
                                        targetIndex = (from + (dragOffset / height).roundToInt())
                                            .coerceIn(0, currentAccounts.lastIndex)
                                    }
                                },
                                onDragEnd = {
                                    val from = currentAccounts.indexOfFirst { it.id == account.id }
                                    if (from >= 0 && targetIndex >= 0 && targetIndex != from) {
                                        currentOnMove(from, targetIndex)
                                    }
                                    draggingId = null
                                    dragOffset = 0f
                                    targetIndex = -1
                                },
                                onDragCancel = {
                                    draggingId = null
                                    dragOffset = 0f
                                    targetIndex = -1
                                }
                            )
                        }
                ) {
                    row(account, index == accounts.lastIndex)
                }
            }
        }

        // Insertion marker while dragging (the list only reorders on drop).
        if (draggingId != null && targetIndex >= 0) {
            Box(
                Modifier
                    .offset { IntOffset(0, topOf(targetIndex).roundToInt()) }
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        // Floating copy under the finger so several rows can be crossed at once.
        val dragged = accounts.firstOrNull { it.id == draggingId }
        if (dragged != null) {
            Surface(
                modifier = Modifier
                    .offset { IntOffset(0, (startTop + dragOffset).roundToInt()) }
                    .fillMaxWidth(),
                shadowElevation = 8.dp,
                tonalElevation = 2.dp
            ) {
                row(dragged, true)
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    selected: Boolean,
    running: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                account.email.ifBlank { "未登录" },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                account.tailnet.ifBlank {
                    if (account.loggedOut) "已登出" else "未连接 Tailnet"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val tag = when {
            selected && running -> "使用中"
            account.loggedOut -> "已登出"
            selected -> "当前"
            else -> ""
        }
        if (tag.isNotEmpty()) {
            Text(
                tag,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    account.loggedOut -> MaterialTheme.colorScheme.error
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = "长按拖动排序",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AccountActionRow(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    destructive -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginCard(
    url: String,
    hostname: String,
    onSaveHostname: (String) -> Unit
) {
    val ctx = LocalContext.current
    var editingHostname by remember { mutableStateOf(false) }
    val displayName = hostname.ifBlank { "ts-socks5" }

    Card(
        onClick = { editingHostname = true },
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
            Spacer(Modifier.height(4.dp))
            Text(
                "设备名：$displayName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                "点击本卡片可修改设备名",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }) {
                Icon(Icons.Filled.OpenInBrowser, null)
                Spacer(Modifier.width(4.dp))
                Text("打开登录链接")
            }
        }
    }

    if (editingHostname) {
        HostnameDialog(
            initial = displayName,
            onDismiss = { editingHostname = false },
            onConfirm = { name ->
                onSaveHostname(name)
                editingHostname = false
            }
        )
    }
}

@Composable
private fun HostnameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    val name = value.trim()
    val valid = name.isNotEmpty() &&
        name.length <= 63 &&
        name.matches(Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$"))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备名") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Tailscale 设备名") },
                    placeholder = { Text("ts-socks5") },
                    singleLine = true,
                    isError = value.isNotBlank() && !valid,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "只能包含字母、数字和连字符，不能以连字符开头或结尾。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = valid) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
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

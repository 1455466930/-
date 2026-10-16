package com.sfzsgybb.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sfzsgybb.ai.data.model.LogGroup
import com.sfzsgybb.ai.data.model.LogRecord
import com.sfzsgybb.ai.data.model.ValueMode
import com.sfzsgybb.ai.ui.components.AideTopBar
import com.sfzsgybb.ai.ui.components.Chip
import com.sfzsgybb.ai.ui.components.EmptyState
import com.sfzsgybb.ai.ui.components.MonoBlock
import com.sfzsgybb.ai.ui.theme.CardCorner
import com.sfzsgybb.ai.ui.theme.GroupColors
import com.sfzsgybb.ai.ui.theme.ScreenPadding
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton

/**
 * 日志页。
 *
 * 【两态设计】
 *  - packageFilter == null → 显示「应用卡片列表」
 *    每个卡片：图标 + 软件名 + 包名 + 最近时间 + 数据量
 *    点进去看该应用的日志
 *  - packageFilter != null → 显示该应用的日志列表
 *    顶部有返回按钮 + 分组筛选
 *
 * 这样打开日志页第一眼就是「哪些应用产生了日志」，
 * 不用在一大堆混在一起的日志里翻。
 */
@Composable
fun LogListScreen(
    onOpenLog: (Long) -> Unit,
    vm: LogListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 进入页面时开启自动刷新，离开时停止，避免后台空转
    DisposableEffect(Unit) {
        vm.startAutoRefresh()
        onDispose { vm.stopAutoRefresh() }
    }

    if (state.packageFilter == null) {
        AppLogList(
            state = state,
            onOpenApp = { vm.setPackage(it) },
            onRefresh = { vm.loadAppOptions(); vm.refresh() },
            onSearch = { vm.setAppQuery(it) },
            onDeleteApp = { vm.deleteAppLogs(it) },
        )
    } else {
        AppLogDetail(
            state = state,
            onBack = { vm.setPackage(null) },
            onOpenLog = onOpenLog,
            onGroup = { vm.setGroup(it) },
            onRefresh = { vm.refresh() },
            onSearch = { vm.search(it) },
            onLoadMore = { vm.loadMore() },
            vm = vm,
        )
    }
}

/**
 * 第一态：应用卡片列表。
 *
 * 顶部是搜索框（可按软件名或包名过滤），
 * 长按卡片弹出「是否删除该应用日志」的确认框。
 */
@Composable
private fun AppLogList(
    state: LogListUiState,
    onOpenApp: (String) -> Unit,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onDeleteApp: (String) -> Unit,
) {
    // 待删除的应用（长按后设置，用户确认才真删）
    var pendingDelete by remember { mutableStateOf<LogAppOption?>(null) }
    val apps = state.filteredApps

    Column(Modifier.fillMaxSize()) {
        AideTopBar(
            title = "日志",
            actions = {
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "刷新") }
            },
        )

        // ---------- 搜索框（替代原来的删除按钮）----------
        OutlinedTextField(
            value = state.appQuery,
            onValueChange = onSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 6.dp),
            placeholder = { Text("搜索应用名称或包名") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.appQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearch("") }) {
                        Icon(Icons.Filled.Clear, "清空")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
        )

        if (apps.isEmpty()) {
            EmptyState(
                title = when {
                    state.loading -> "加载中…"
                    state.appQuery.isNotBlank() -> "没有匹配的应用"
                    else -> "暂无日志"
                },
                hint = if (state.appQuery.isNotBlank()) "换个关键词试试"
                else "在应用页给目标应用打开开关，再重启该应用即可捕获",
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = ScreenPadding, end = ScreenPadding, top = 6.dp, bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(apps, key = { it.packageName }) { opt ->
                AppLogCard(
                    opt = opt,
                    onClick = { onOpenApp(opt.packageName) },
                    // 长按 → 弹确认框（不用顶部按钮，避免误删）
                    onLongClick = { pendingDelete = opt },
                )
            }
        }
    }

    // ---------- 删除确认对话框 ----------
    pendingDelete?.let { opt ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除该应用的日志？") },
            text = {
                Text(
                    "将删除「${opt.label}」的 ${opt.count} 条日志，此操作不可撤销。",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteApp(opt.packageName)
                    pendingDelete = null
                }) {
                    Text("确定删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 应用日志卡片：图标 + 软件名 + 包名 + 时间 + 数据量。
 *
 * 点击进入该应用的日志列表；长按弹出删除确认框。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AppLogCard(
    opt: LogAppOption,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = RoundedCornerShape(CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 应用图标
            AppIcon(opt.icon)

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    opt.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    opt.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    formatTime(opt.lastTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Text(
                    formatBytes(opt.totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** 应用图标：取不到时用占位方块。 */
@Composable
private fun AppIcon(icon: android.graphics.drawable.Drawable?) {
    val size = 42.dp
    if (icon == null) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("?", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val bmp = remember(icon) { icon.toBitmap().asImageBitmap() }
    androidx.compose.foundation.Image(
        bitmap = bmp,
        contentDescription = null,
        modifier = Modifier.size(size).clip(RoundedCornerShape(9.dp)),
    )
}

/** Drawable → Bitmap。 */
private fun android.graphics.drawable.Drawable.toBitmap(): android.graphics.Bitmap {
    if (this is android.graphics.drawable.BitmapDrawable && bitmap != null) return bitmap
    val w = if (intrinsicWidth > 0) intrinsicWidth else 96
    val h = if (intrinsicHeight > 0) intrinsicHeight else 96
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}

/**
 * 第二态：某个应用的日志列表。
 */
@Composable
private fun AppLogDetail(
    state: LogListUiState,
    onBack: () -> Unit,
    onOpenLog: (Long) -> Unit,
    onGroup: (Int) -> Unit,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    vm: LogListViewModel,
) {
    val listState = rememberLazyListState()
    val appLabel = state.appOptions.firstOrNull {
        it.packageName == state.packageFilter
    }?.label ?: state.packageFilter.orEmpty()

    // 触底自动加载下一页
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            state.logs.isNotEmpty() && last >= state.logs.size - 5
        }
    }
    LaunchedEffect(shouldLoadMore, state.query) {
        if (shouldLoadMore && state.query.isBlank()) onLoadMore()
    }

    Column(Modifier.fillMaxSize()) {
        AideTopBar(
            title = appLabel,
            onBack = onBack,
            actions = {
                Text(
                    state.total.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "刷新") }
            },
        )

        OutlinedTextField(
            value = state.query,
            onValueChange = onSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 6.dp),
            placeholder = { Text("搜索日志名 / 类名 / 内容") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onSearch("") }) { Icon(Icons.Filled.Clear, "清空") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = state.groupFilter < 0,
                onClick = { onGroup(-1) },
                label = { Text("全部", fontSize = 12.sp) },
            )
            for (g in 0..5) {
                FilterChip(
                    selected = state.groupFilter == g,
                    onClick = { onGroup(g) },
                    label = { Text(LogGroup.label(g), fontSize = 12.sp) },
                )
            }
        }

        if (state.logs.isEmpty()) {
            EmptyState(
                title = if (state.loading) "加载中…" else "该应用暂无日志",
                hint = "重启该应用后操作几下，日志会自动上报",
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 6.dp, bottom = 40.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.logs, key = { it.id }) { log ->
                    LogRow(log = log, onClick = { onOpenLog(log.id) })
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
                if (state.hasMore) {
                    item {
                        Text(
                            "加载更多…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 人类可读的字节数。 */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0B"
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> String.format("%.2fMB", bytes / 1024.0 / 1024.0)
}

@Composable
private fun LogRow(
    log: LogRecord,
    onClick: () -> Unit,
    /** 已按应用筛选时为 true，此时不再重复显示应用名 */
    showApp: Boolean = false,
    /** 应用显示名（未知时为 null） */
    appLabel: String? = null,
) {
    val groupColor = GroupColors.of(log.group)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ScreenPadding, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip(LogGroup.label(log.group), groupColor)
            // 文件读写方向：绿=读，橙=写，红=删。
            // 列表里全是「File…」时分不清方向，加个彩色标签一眼看明白。
            fileOpTag(log.logName)?.let { (tag, color) ->
                Chip(tag, color)
            }
            Text(
                text = log.logName.ifBlank { log.className.substringAfterLast('.') },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (!log.isRead) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(groupColor).padding(3.dp))
            }
            Text(
                formatTime(log.time),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        // 应用名：看「全部应用」时能一眼分辨日志来源
        if (!showApp && !log.packageName.isNullOrBlank()) {
            Text(
                text = appLabel ?: log.packageName.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        if (log.payloadBrief.isNotBlank()) {
            Text(
                text = log.payloadBrief,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 操作细节：比如「⚠ 覆盖写入（会清空原内容）」
        if (log.paramDesc.isNotBlank()) {
            Text(
                text = log.paramDesc,
                fontSize = 11.sp,
                maxLines = 1,
                color = if (log.paramDesc.startsWith("⚠")) OpWrite else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (log.resultBrief.isNotBlank()) {
            Text(
                text = "→ " + log.resultBrief,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = 2,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ---------- 文件操作方向配色 ----------
private val OpRead = Color(0xFF2E7D32)
private val OpWrite = Color(0xFFEF6C00)
private val OpDelete = Color(0xFFC62828)

/**
 * 把「文件读取 / 文件写入 / 文件删除」映射成带颜色的短标签。
 *
 * 列表里一堆文件操作长得都一样，光看路径分不出方向，
 * 加个彩色标签比读文字快得多。
 *
 * @return (标签文本, 颜色)；不是文件操作返回 null
 */
private fun fileOpTag(logName: String): Pair<String, Color>? {
    if (!logName.startsWith("文件")) return null
    return when {
        "读取" in logName -> "读" to OpRead
        "写入" in logName -> "写" to OpWrite
        "删除" in logName -> "删" to OpDelete
        else -> null
    }
}

/**
 * 日志详情页。
 *
 * 【字段排列对齐原版】
 * 原版把加密调用按类别拆开显示，而不是一堆裸字节：
 *
 *   时间 / 日志名称 / 日志分组 / 应用
 *   算法名称            ← algoName
 *   参数 / 参数类型      ← paramDesc
 *   加密密钥 / 密钥类型   ← keyData / keyType
 *   加密内容            ← payload
 *   结果（加密结果等）    ← results
 *   调用堆栈            ← callStack
 *
 * 每块内容都可长按复制。
 */
/**
 * 日志详情页。
 *
 * 【字段排列对齐原版】
 *   时间 / 日志名称 / 日志分组 / 应用包名 / 类名 / 对象
 *   算法名称            ← algoName
 *   参数                ← paramDesc
 *   加密密钥 + 类型标签  ← keyData / keyType
 *   加密内容            ← payload
 *   结果（摘要/加密/解密/MAC）← results
 *   调用堆栈            ← callStack
 *
 * 【每块内容独立控制】
 * 内容区有独立的渲染模式（text / hex / base64）与折叠开关 ——
 * 密钥看 hex 方便，正文看 text 方便，不用整页统一切换。
 */
@Composable
fun LogDetailScreen(
    logId: Long,
    onBack: () -> Unit,
    vm: LogDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(logId) { vm.load(logId) }

    // 统一的复制 + 提示
    fun doCopy(text: String, what: String) {
        copyToClipboard(ctx, text)
        scope.launch { snackbar.showSnackbar("已复制$what") }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            val log = state.log
            AideTopBar(
                title = log?.logName?.takeIf { it.isNotBlank() } ?: "日志详情",
                onBack = onBack,
                actions = {
                    if (log != null) {
                        IconButton(onClick = { doCopy(vm.buildFullText(), "整条日志") }) {
                            Icon(Icons.Filled.ContentCopy, "复制整条日志")
                        }
                    }
                },
            )

            if (log == null) {
                EmptyState(if (state.loading) "加载中…" else "记录不存在")
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ---------- 基本信息 ----------
                item {
                    DetailCard {
                        MetaLine("时间", formatTime(log.time))
                        MetaLine("日志名称", log.logName)
                        MetaLine("日志分组", LogGroup.label(log.group))
                        if (!log.packageName.isNullOrBlank()) MetaLine("应用包名", log.packageName)
                        MetaLine("类名", log.className)
                        MetaLine("对象", log.objName)
                    }
                }

                // ---------- 算法名称 ----------
                if (log.algoName.isNotBlank()) {
                    item { TextField("算法名称", log.algoName) { doCopy(log.algoName, "算法名称") } }
                }

                // ---------- 参数 ----------
                if (log.paramDesc.isNotBlank()) {
                    item { TextField("参数", log.paramDesc) { doCopy(log.paramDesc, "参数") } }
                }

                // ---------- 加密密钥 ----------
                if (log.keyData.isNotEmpty()) {
                    item {
                        BlobField(
                            title = "加密密钥",
                            badge = log.keyType.ifBlank { null },
                            bytes = log.keyData,
                            onCopy = { doCopy(it, "密钥") },
                        )
                    }
                }

                // ---------- 加密内容 ----------
                item {
                    BlobField(
                        title = if (log.keyData.isNotEmpty()) "加密内容" else "输入 / 载荷",
                        bytes = log.payload,
                        onCopy = { doCopy(it, "内容") },
                    )
                }

                // ---------- 结果 ----------
                items(log.results.size) { idx ->
                    val kind = log.resultKind.ifBlank {
                        if (log.results.size == 1) "结果" else "结果 #${idx + 1}"
                    }
                    BlobField(
                        title = kind,
                        bytes = log.results[idx],
                        onCopy = { doCopy(it, kind) },
                    )
                }

                // ---------- 调用堆栈 ----------
                if (log.callStack.isNotBlank()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FieldHeader("调用堆栈") { doCopy(log.callStack, "调用堆栈") }
                            MonoBlock(log.callStack, maxLines = 80)
                        }
                    }
                }
            }
        }
    }
}

/** 字段头：标题 + 右侧复制按钮。 */
@Composable
private fun FieldHeader(title: String, badge: String? = null, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DetailLabel(title)
        if (badge != null) {
            Spacer(Modifier.size(6.dp))
            Chip(badge, MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.weight(1f))
        Text(
            "复制",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onCopy),
        )
    }
}

/** 纯文本字段。 */
@Composable
private fun TextField(label: String, value: String, onCopy: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        FieldHeader(label, onCopy = onCopy)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

/**
 * 二进制内容字段：自带渲染模式切换与展开/收起。
 *
 * 每块独立控制 —— 密钥习惯看 hex、正文习惯看 text，
 * 不用为了看密钥把整页切成 hex 再切回来。
 */
@Composable
private fun BlobField(
    title: String,
    bytes: ByteArray,
    badge: String? = null,
    onCopy: (String) -> Unit,
) {
    var mode by remember { mutableStateOf(ValueMode.TEXT) }
    var expanded by remember { mutableStateOf(false) }

    val rendered = remember(bytes, mode) { renderBytes(bytes, mode) }
    val lines = rendered.lineSequence().count()
    val shown = if (expanded) rendered else rendered.lineSequence().take(8).joinToString("\n")

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 头：标题 + 大小 + 复制
        Row(verticalAlignment = Alignment.CenterVertically) {
            DetailLabel(title)
            if (badge != null) {
                Spacer(Modifier.size(6.dp))
                Chip(badge, MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${bytes.size} B",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "复制",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onCopy(rendered) },
            )
        }

        // 渲染模式切换（每块独立）
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ValueMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { mode = m },
                    label = { Text(m.name.lowercase(), fontSize = 11.sp) },
                )
            }
        }

        MonoBlock(shown.ifBlank { "(空)" })

        // 折叠开关（内容多时才显示）
        if (lines > 8) {
            Text(
                if (expanded) "收起" else "展开全部（$lines 行）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                modifier = Modifier.clickable { expanded = !expanded },
            )
        }
    }
}

/** 按模式渲染字节。 */
private fun renderBytes(bytes: ByteArray, mode: ValueMode): String = when (mode) {
    ValueMode.TEXT -> runCatching { String(bytes, Charsets.UTF_8) }
        .getOrDefault("").ifBlank { toHexStr(bytes) }
    ValueMode.HEX -> toHexStr(bytes)
    ValueMode.BASE64 -> android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}

private fun toHexStr(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

/** 详情卡片的统一容器。 */
@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** 复制到剪贴板。 */
private fun copyToClipboard(ctx: android.content.Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("aidepro", text))
    }
}

@Composable
private fun MetaLine(k: String, v: String) {
    if (v.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            k,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.28f),
        )
        Text(v, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.72f))
    }
}

@Composable
private fun DetailLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

internal fun formatTime(t: Long): String {
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(t))
}

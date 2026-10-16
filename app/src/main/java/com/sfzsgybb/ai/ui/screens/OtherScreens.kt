package com.sfzsgybb.ai.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sfzsgybb.ai.hook.config.GlobalSwitch
import com.sfzsgybb.ai.hook.config.HookRule
import com.sfzsgybb.ai.hook.config.HookSwitch
import com.sfzsgybb.ai.ui.components.AideTopBar
import com.sfzsgybb.ai.ui.components.EmptyState
import com.sfzsgybb.ai.ui.components.MonoBlock
import com.sfzsgybb.ai.ui.components.SectionHeader
import com.sfzsgybb.ai.ui.components.StatCard
import com.sfzsgybb.ai.ui.components.StatusDot
import com.sfzsgybb.ai.ui.theme.CardCorner
import com.sfzsgybb.ai.ui.theme.ScreenPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.OutlinedButton

// ---------------- 设置 ----------------

@Composable
fun SettingsScreen(
    onOpenMcp: () -> Unit,
    onOpenAbout: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        AideTopBar(title = "设置")

        LazyColumn(
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { SectionHeader("日志与展示") }
            val displayItems = listOf(
                GlobalSwitch.BASE64, GlobalSwitch.HEX, GlobalSwitch.STACK,
                GlobalSwitch.DISCARD_SYS_STACK, GlobalSwitch.FOLD_DUPLICATE_LOGS,
                GlobalSwitch.LOG_DETAIL_VIEW, GlobalSwitch.TOAST,
            )
            items(displayItems, key = { it.key }) { sw ->
                SwitchRow(sw.title, sw.desc, state.global[sw.key] ?: sw.default) { vm.setGlobal(sw, it) }
            }

            item { SectionHeader("抓包") }
            val captureItems = listOf(GlobalSwitch.CAPTURE_FILTER_LOCAL, GlobalSwitch.CAPTURE_FLOATING_WINDOW)
            items(captureItems, key = { it.key }) { sw ->
                SwitchRow(sw.title, sw.desc, state.global[sw.key] ?: sw.default) { vm.setGlobal(sw, it) }
            }

            item { SectionHeader("MCP 服务") }
            item {
                SettingsNavRow(
                    title = "MCP 服务",
                    subtitle = if (state.mcpRunning) "运行中 · 127.0.0.1:${state.mcpPort}" else "未启动 · 端口 ${state.mcpPort}",
                    active = state.mcpRunning,
                    onClick = onOpenMcp,
                )
            }
            item {
                SwitchRow(
                    GlobalSwitch.MCP_HTTP_ENABLED.title,
                    GlobalSwitch.MCP_HTTP_ENABLED.desc,
                    state.global[GlobalSwitch.MCP_HTTP_ENABLED.key] ?: false,
                ) { on ->
                    vm.setGlobal(GlobalSwitch.MCP_HTTP_ENABLED, on)
                    vm.toggleMcp(on)
                }
            }

            item { SectionHeader("其他") }
            item { SettingsNavRow("关于", "版本 ${state.versionName}", false, onOpenAbout) }
        }
    }
}

@Composable
private fun SettingsNavRow(title: String, subtitle: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (active) StatusDot(true)
    }
}

// ---------------- MCP 控制台 ----------------

@Composable
fun McpConsoleScreen(
    onBack: () -> Unit,
    vm: McpConsoleViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // 定时刷新连接数/请求数
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3000)
            vm.refreshStats()
        }
    }

    Column(Modifier.fillMaxSize()) {
        AideTopBar(title = "MCP 服务", onBack = onBack)

        LazyColumn(
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------- 状态卡片 ----------
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(CardCorner),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusDot(state.running)
                            Text(
                                if (state.running) "运行中 · 端口 ${state.port}" else "已停止",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }

                        if (state.running) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatCard("连接", state.connections.toString(), Modifier.weight(1f))
                                StatCard("请求", state.requests.toString(), Modifier.weight(1f),
                                    accent = MaterialTheme.colorScheme.secondary)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.startServer() }, modifier = Modifier.weight(1f)) {
                                Text("启动")
                            }
                            Button(onClick = { vm.stopServer() }, modifier = Modifier.weight(1f)) {
                                Text("停止")
                            }
                        }
                    }
                }
            }

            // ---------- 连接地址 ----------
            // 地址可编辑（有些人用固定的局域网 IP、或有端口映射），
            // 编辑后点「复制」就能直接贴到 AI 客户端里。
            item { SectionHeader("连接地址") }

            item {
                var editing by remember { mutableStateOf(false) }
                var draft by remember(state.activeUrl) { mutableStateOf(state.activeUrl) }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = if (editing) draft else state.activeUrl,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = !editing,
                        singleLine = true,
                        label = { Text("MCP 地址") },
                        shape = RoundedCornerShape(10.dp),
                        trailingIcon = {
                            IconButton(onClick = {
                                if (editing) {
                                    // 保存：写回输入内容（下次同步时保留）
                                    editing = false
                                } else {
                                    draft = state.activeUrl
                                    editing = true
                                }
                            }) {
                                Icon(
                                    if (editing) Icons.Filled.Check else Icons.Filled.Edit,
                                    contentDescription = if (editing) "完成" else "编辑",
                                )
                            }
                        },
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(
                                    androidx.compose.ui.text.AnnotatedString(
                                        if (editing) draft else state.activeUrl
                                    )
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Text("  复制地址")
                        }
                        if (editing) {
                            OutlinedButton(
                                onClick = { draft = state.activeUrl; editing = false },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("还原")
                            }
                        }
                    }
                }
            }

            // ---------- 局域网 ----------
            item { SectionHeader("局域网访问") }
            item {
                SwitchRow(
                    title = "允许局域网访问",
                    desc = "开启后同一 WiFi 下的电脑可直接连接，无需 adb forward。" +
                        "绑定 0.0.0.0，不校验令牌，开箱即用。",
                    checked = state.lanEnabled,
                    onToggle = vm::setLanEnabled,
                )
            }
            if (state.lanEnabled && state.lanUrl.isNullOrBlank()) {
                item {
                    Text(
                        "未获取到局域网 IP，请确认已连接 WiFi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                    )
                }
            }

            // ---------- 保活 ----------
            item { SectionHeader("稳定性") }
            item {
                SwitchRow(
                    title = "常驻保活",
                    desc = "前台服务保持 MCP 在线，避免 AI 客户端长时间空闲后断连。" +
                        "会显示一条低优先级通知。",
                    checked = state.keepAlive,
                    onToggle = vm::setKeepAlive,
                )
            }

            // ---------- 客户端配置 ----------
            item { SectionHeader("客户端配置") }
            item {
                val cfg = buildString {
                    append("{\n")
                    append("  \"mcpServers\": {\n")
                    append("    \"algorithm-aide-pro\": {\n")
                    append("      \"url\": \"").append(state.activeUrl).append("\"\n")
                    append("    }\n")
                    append("  }\n")
                    append("}")
                }
                CopyableBlock(text = cfg, onCopy = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(cfg))
                })
            }
            if (!state.lanEnabled) {
                item {
                    Text(
                        "本机地址需要先执行：adb forward tcp:${state.port} tcp:${state.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---------- 工具清单 ----------
            item { SectionHeader("可用工具（${state.tools.size}）") }
            items(state.tools) { t ->
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        t.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        t.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            }
        }
    }
}

/** 可点击复制的等宽文本块。 */
@Composable
private fun CopyableBlock(text: String, onCopy: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onCopy)) {
        MonoBlock(text)
    }
}

// ---------------- Hook 规则 ----------------

@Composable
fun HookListScreen(
    packageName: String,
    onBack: () -> Unit,
    vm: HookListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    LaunchedEffect(packageName) { vm.load(packageName) }

    Column(Modifier.fillMaxSize()) {
        AideTopBar(
            title = "Hook 规则",
            onBack = onBack,
            actions = {
                IconButton(onClick = { showDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "添加")
                }
            },
        )

        if (state.rules.isEmpty()) {
            EmptyState("还没有自定义规则", "点右上角加号添加类名与方法名")
        } else {
            LazyColumn(contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 32.dp)) {
                items(state.rules, key = { it.id }) { rule ->
                    RuleRow(rule, onDelete = { vm.remove(rule.id) })
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                }
            }
        }
    }

    if (showDialog) {
        AddRuleDialog(
            onDismiss = { showDialog = false },
            onConfirm = { rule ->
                vm.add(rule)
                showDialog = false
            },
        )
    }
}

@Composable
private fun RuleRow(rule: HookRule, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                rule.className,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            Text(
                buildString {
                    append(rule.methodName)
                    if (rule.parameterSign.isNotBlank()) append(rule.parameterSign)
                    if (rule.isConstructor) append("  [构造]")
                    if (rule.intercept) append("  [拦截]")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
    }
}

@Composable
private fun AddRuleDialog(onDismiss: () -> Unit, onConfirm: (HookRule) -> Unit) {
    var className by remember { mutableStateOf("") }
    var methodName by remember { mutableStateOf("") }
    var paramSign by remember { mutableStateOf("") }
    var intercept by remember { mutableStateOf(false) }
    var replaceValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 Hook 规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = className, onValueChange = { className = it },
                    label = { Text("类名") }, placeholder = { Text("com.example.Foo") }, singleLine = true,
                )
                OutlinedTextField(
                    value = methodName, onValueChange = { methodName = it },
                    label = { Text("方法名") }, placeholder = { Text("encrypt") }, singleLine = true,
                )
                OutlinedTextField(
                    value = paramSign, onValueChange = { paramSign = it },
                    label = { Text("参数签名（可空）") },
                    placeholder = { Text("Ljava.lang.String;I") }, singleLine = true,
                )
                SwitchRow("拦截并替换返回值", "", intercept) { intercept = it }
                if (intercept) {
                    OutlinedTextField(
                        value = replaceValue, onValueChange = { replaceValue = it },
                        label = { Text("替换值") }, placeholder = { Text("true 或 {\"ok\":1}") }, singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = className.isNotBlank() && methodName.isNotBlank(),
                onClick = {
                    onConfirm(
                        HookRule(
                            className = className.trim(),
                            methodName = methodName.trim(),
                            parameterSign = paramSign.trim(),
                            isConstructor = methodName.trim() == "<init>",
                            intercept = intercept,
                            replaceResult = replaceValue.trim(),
                        )
                    )
                },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------- Frida ----------------

@Composable
fun FridaEditorScreen(
    packageName: String,
    onBack: () -> Unit,
    vm: FridaViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(packageName) { vm.load(packageName) }

    Column(Modifier.fillMaxSize()) {
        AideTopBar(
            title = "Frida 脚本",
            onBack = onBack,
            actions = { TextButton(onClick = { vm.save() }) { Text("保存") } },
        )
        Text(
            text = packageName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        OutlinedTextField(
            value = state.script,
            onValueChange = vm::setScript,
            modifier = Modifier.fillMaxSize().padding(ScreenPadding),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text("Java.perform(function () {\n  // 在这里编写脚本\n});") },
        )
    }
}

@Composable
fun FridaLogScreen(
    packageName: String,
    onBack: () -> Unit,
    vm: FridaLogViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(packageName) { vm.load(packageName) }

    Column(Modifier.fillMaxSize()) {
        AideTopBar(
            title = "Frida 日志",
            onBack = onBack,
            actions = {
                IconButton(onClick = { vm.clear() }) { Icon(Icons.Filled.Delete, contentDescription = "清空") }
            },
        )
        if (state.logs.isEmpty()) {
            EmptyState("暂无脚本日志", "脚本执行后输出会出现在这里")
        } else {
            LazyColumn(contentPadding = PaddingValues(ScreenPadding)) {
                items(state.logs) { l ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            "${formatTime(l.time)}  ${l.level}  ${l.scriptName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                        Text(l.message, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ---------------- 关于 ----------------

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    feedbackOnly: Boolean = false,
    vm: AboutViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        AideTopBar(title = if (feedbackOnly) "反馈" else "关于", onBack = onBack)

        LazyColumn(
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(CardCorner),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("AlgorithmAidePro", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "版本 ${state.versionName} (${state.versionCode})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Xposed 模块 / 抓包工具 / 算法日志分析，内建 MCP 服务供 AI 调用。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { SectionHeader("运行环境") }
            item {
                // 直接展示 ModuleStatusChecker 的结论，而不是二元的「已激活/未检测到」：
                // 「无法确认」和「未激活」是两回事，混在一起会误导用户。
                val m = state.module
                InfoRow(
                    "Xposed 框架",
                    when {
                        m == null -> "检测中…"
                        m.active -> "${m.framework ?: "Xposed"} · 已启用"
                        m.state == com.sfzsgybb.ai.util.ModuleStatusChecker.State.NO_FRAMEWORK ->
                            "未检测到"
                        m.state == com.sfzsgybb.ai.util.ModuleStatusChecker.State.UNKNOWN ->
                            "${m.framework ?: "Xposed"} · 无法确认"
                        else -> "${m.framework ?: "Xposed"} · 未启用"
                    },
                )
            }
            item { InfoRow("Root", if (state.rooted) "已获取" else "未获取") }
            // 作用域：勾了哪些应用（这是最容易漏的一步）
            state.module?.let { m ->
                if (m.scopes.isNotEmpty()) {
                    item { InfoRow("Hook 作用域", "${m.scopes.size} 个应用") }
                    item {
                        Text(
                            m.scopes.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                }
            }

            item { SectionHeader("能力统计") }
            item { InfoRow("Hook 开关", "${HookSwitch.count} 项") }
            item { InfoRow("全局选项", "${GlobalSwitch.entries.size} 项") }
            item { InfoRow("MCP 工具", "${state.toolCount} 个") }

            item {
                Text(
                    "本工具用于自有设备上的应用调试与协议分析。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            v,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

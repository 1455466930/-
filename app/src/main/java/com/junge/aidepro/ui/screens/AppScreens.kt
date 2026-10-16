package com.junge.aidepro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junge.aidepro.data.model.LogGroup
import com.junge.aidepro.hook.config.HookSwitch
import com.junge.aidepro.mcp.tools.AppEntry
import com.junge.aidepro.ui.components.AideTopBar
import com.junge.aidepro.ui.components.Chip
import com.junge.aidepro.ui.components.EmptyState
import com.junge.aidepro.ui.components.StatCard
import com.junge.aidepro.ui.components.StatusDot
import com.junge.aidepro.ui.theme.CardCorner
import com.junge.aidepro.ui.theme.ScreenPadding

/** 分组标题映射，避免中文散落在多处。 */
private val GROUP_TITLES = linkedMapOf(
    LogGroup.CRYPTO to "加密算法",
    LogGroup.NETWORK to "网络与证书",
    LogGroup.STORAGE to "存储访问",
    LogGroup.UI to "界面交互",
    LogGroup.SYSTEM to "系统与环境",
)

@Composable
fun AppListScreen(
    onOpenApp: (String) -> Unit,
    vm: AppListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        AideTopBar(
            title = "应用",
            actions = {
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatCard("已安装", state.totalCount.toString(), Modifier.weight(1f))
            StatCard(
                "已启用", state.enabledCount.toString(), Modifier.weight(1f),
                accent = MaterialTheme.colorScheme.secondary,
            )
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 10.dp),
            placeholder = { Text("搜索应用名或包名") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "清空")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
        )

        // 已启用的应用排在最前面（见 DeviceAppListProvider 的排序），
        // 这里只做个说明，不再提供「仅显示已启用」开关 —— 那个开关没什么用，
        // 而且开了之后反而找不到想新启用的应用。
        Row(
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "共 ${state.totalCount} 个应用，已启用 ${state.enabledCount} 个（已启用的排在前面）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }

        if (state.apps.isEmpty() && !state.loading) {
            EmptyState("没有匹配的应用", "换个关键词试试")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        onClick = { onOpenApp(app.packageName) },
                        onToggle = { on -> vm.toggleEnabled(app.packageName, on) },
                    )
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = ScreenPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = app.label.take(1).ifBlank { "?" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (app.isSystem) Chip("系统", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusDot(app.hookEnabled)
            Switch(checked = app.hookEnabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
fun AppDetailScreen(
    packageName: String,
    onBack: () -> Unit,
    onOpenHooks: () -> Unit,
    onOpenFrida: () -> Unit,
    onOpenFridaLog: () -> Unit,
    vm: AppDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(packageName) { vm.load(packageName) }

    // 操作结果提示（启动失败 / 无 root 等）
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AideTopBar(title = packageName.substringAfterLast('.'), onBack = onBack)

        LazyColumn(
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(CardCorner),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("启用 Hook", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "关闭后该应用不加载任何模块",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = state.enabled, onCheckedChange = vm::setAppEnabled)
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppActionCard("自定义规则", "${state.hooks} 条", Modifier.weight(1f), onOpenHooks)
                    AppActionCard("Frida 脚本", "编辑", Modifier.weight(1f), onOpenFrida)
                    AppActionCard("Frida 日志", "查看", Modifier.weight(1f), onOpenFridaLog)
                }
            }

            // 启动 / 重启：配置改动后必须让目标应用重新走一遍 Application.onCreate，
            // Hook 才会重新读取配置并安装模块。
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(CardCorner),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusDot(state.targetRunning)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (state.targetRunning) "${state.appLabel} 正在运行" else "${state.appLabel} 未运行",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "改动开关后需重启目标应用才会生效",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.refreshRunning() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新状态")
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { vm.restartApp() },
                                modifier = Modifier.weight(1f),
                                enabled = state.enabled,
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("  强停并重启")
                            }
                            OutlinedButton(
                                onClick = { vm.launchApp() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("  启动应用")
                            }
                        }

                        if (!state.hasRoot) {
                            Text(
                                "设备无 root，「强停并重启」会跳转到系统设置页手动停止",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(
                            onClick = { vm.openSystemSettings() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("打开系统应用设置")
                        }
                    }
                }
            }

            GROUP_TITLES.forEach { (group, title) ->
                val switches = HookSwitch.byGroup(group)
                if (switches.isEmpty()) return@forEach

                item(key = "header_$group") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "全部开启",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { vm.setGroup(group, true) },
                            )
                            Text(
                                "全部关闭",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { vm.setGroup(group, false) },
                            )
                        }
                    }
                }

                items(switches, key = { it.key }) { sw ->
                    SwitchRow(
                        title = sw.title,
                        desc = if (sw.risky) sw.desc + "（高风险）" else sw.desc,
                        checked = state.switches[sw.key] ?: false,
                        enabled = state.enabled,
                        onToggle = { vm.setSwitch(sw, it) },
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun AppActionCard(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(CardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    desc: String = "",
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (desc.isNotBlank()) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
    }
}

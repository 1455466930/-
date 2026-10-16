package com.junge.aidepro.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junge.aidepro.ui.components.AideTopBar
import com.junge.aidepro.ui.components.SectionHeader
import com.junge.aidepro.ui.theme.CardCorner
import com.junge.aidepro.ui.theme.ScreenPadding
import com.junge.aidepro.util.ModuleStatusChecker

/**
 * 社区入口链接。
 *
 * 指向腾讯文档的社区页（比直接跳 QQ 客户端更通用：
 * 手机没装 QQ 时也能用浏览器打开）。
 */
private const val COMMUNITY_URL = "https://docs.qq.com/doc/DUHZkb1hLWmhQQXN5"

private val ActiveGreen = Color(0xFF2E7D32)
private val ActiveGreenBg = Color(0xFF1B3A1D)
private val InactiveRed = Color(0xFFC62828)
private val InactiveRedBg = Color(0xFF3A1B1B)
private val WarnAmber = Color(0xFFEF6C00)
private val WarnAmberBg = Color(0xFF3A2E1B)
/** 「状态无法确认」用的中性蓝：不吓唬用户，也不谎报成功。 */
private val UnknownBlue = Color(0xFF1976D2)
private val UnknownBlueBg = Color(0xFF16283A)

/**
 * 首页：模块状态总览。
 *
 * 打开应用第一眼就能看到「Hook 模块到底激活了没有」，
 * 没激活时直接给出可操作的激活步骤 —— 这是最容易卡住用户的地方。
 */
@Composable
fun HomeScreen(
    onOpenMcp: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        AideTopBar(
            title = "算法助手公益版",
            actions = {
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Filled.Refresh, "重新检测")
                }
            },
        )

        LazyColumn(
            contentPadding = PaddingValues(
                start = ScreenPadding, end = ScreenPadding, top = 6.dp, bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- 模块状态大卡片 ----------
            item {
                ModuleStatusCard(
                    status = state.module,
                    loading = state.loading,
                    onClick = {
                        if (state.module?.active != true) vm.showHelp()
                    },
                )
            }

            // ---------- 运行信息 ----------
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(CardCorner),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        InfoBlock("激活框架", state.module?.framework ?: "未检测到")
                        InfoBlock("算法助手公益版版本", "v${state.versionName}(${state.versionCode})")
                        InfoBlock(
                            "启用 Hook 的应用",
                            if (state.enabledApps > 0) "${state.enabledApps} 个" else "还没有启用任何应用",
                        )
                        InfoBlock(
                            "MCP / HTTP",
                            if (state.mcpRunning) "端口 ${state.mcpPort} · ${state.mcpTools} 个工具"
                            else "未启动",
                            onClick = onOpenMcp,
                        )
                        InfoBlock(
                            "已捕获数据",
                            "日志 ${state.logCount} 条 · 请求 ${state.requestCount} 条",
                        )
                    }
                }
            }

            // ---------- 社区与关于 ----------
            item { SectionHeader("社区与关于") }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(CardCorner),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        OutlinedButton(
                            onClick = { openCommunity(ctx) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Filled.Group,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("  加入社区")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "遇到问题或想交流逆向技巧，欢迎加入社区。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }

    // ---------- 激活帮助对话框 ----------
    if (state.showActivationHelp) {
        val hint = state.module?.hint ?: "正在检测模块状态…"
        // 「无法确认」时标题不能用「如何激活」—— 会让用户以为功能坏了
        val isUnknown = state.module?.state == ModuleStatusChecker.State.UNKNOWN
        AlertDialog(
            onDismissRequest = { vm.dismissHelp() },
            title = { Text(if (isUnknown) "关于模块状态" else "如何激活模块") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 20.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.refresh(); vm.dismissHelp() }) {
                    Text("重新检测")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissHelp() }) { Text("关闭") }
            },
        )
    }
}

/** 模块状态大卡片：绿色=已激活，红色=未激活，橙色=已装未启用。 */
@Composable
private fun ModuleStatusCard(
    status: ModuleStatusChecker.Status?,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val (bg, fg, icon, title, subtitle) = when {
        status == null -> Quad(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Refresh,
            if (loading) "正在检测…" else "状态未知",
            "点击重新检测",
        )
        status.active -> Quad(
            ActiveGreenBg, ActiveGreen, Icons.Filled.CheckCircle,
            "模块已启用", status.summary,
        )
        status.state == ModuleStatusChecker.State.NO_FRAMEWORK -> Quad(
            InactiveRedBg, InactiveRed, Icons.Filled.ErrorOutline,
            "模块未激活", "未检测到 Xposed 框架，点此查看安装步骤",
        )
        // 【重要】查不到 ≠ 没激活。
        // 检测受限（没 root / 框架路径不同）时不能吓唬用户说「未生效」，
        // 用中性提示 + 说明功能可能是好的。
        status.state == ModuleStatusChecker.State.UNKNOWN -> Quad(
            UnknownBlueBg, UnknownBlue, Icons.Filled.HelpOutline,
            "状态无法确认", status.summary + "，点此查看说明",
        )
        else -> Quad(
            WarnAmberBg, WarnAmber, Icons.Filled.Warning,
            "模块未生效", status.summary + "，点此查看激活步骤",
        )
    }

    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(CardCorner),
        colors = CardDefaults.cardColors(containerColor = bg),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = fg.copy(alpha = 0.8f),
                )
            }
            Icon(
                icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}

/** 信息块：标题 + 内容。 */
@Composable
private fun InfoBlock(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** 四元组辅助（避免嵌套 Pair）。 */
private data class Quad<A, B, C, D, E>(
    val a: A, val b: B, val c: C, val d: D, val e: E,
)

/**
 * 打开社区页面（腾讯文档）。
 *
 * 优先交给系统处理：装了 QQ/浏览器就由它们打开，
 * 都没有时才提示用户。
 */
private fun openCommunity(ctx: android.content.Context) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(COMMUNITY_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }.onFailure {
        android.widget.Toast.makeText(ctx, "没有可打开链接的应用", android.widget.Toast.LENGTH_SHORT).show()
    }
}

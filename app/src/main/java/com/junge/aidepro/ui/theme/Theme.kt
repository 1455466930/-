package com.junge.aidepro.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 主题。
 *
 * 原版是 Material Components + XML 主题（Theme.blue），
 * 且因为用了 AppCompat 而无法做动态取色。
 * 这里用 Compose Material3，支持 Android 12+ 动态取色。
 */

private val BluePrimary = Color(0xFF2563EB)
private val BluePrimaryDark = Color(0xFF60A5FA)
private val TealSecondary = Color(0xFF0D9488)
private val AmberTertiary = Color(0xFFF59E0B)

private val LightColors = lightColorScheme(
    primary = BluePrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = TealSecondary,
    onSecondary = Color.White,
    tertiary = AmberTertiary,
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = BluePrimaryDark,
    onPrimary = Color(0xFF0B1220),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF042F2E),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1F2937),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
)

/** 日志/报文详情用的等宽字体样式。 */
val MonoTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)

val CodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun AideProTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
            val scheme = if (darkTheme) {
                android.graphics.Color.parseColor("#0B1220")
            } else {
                android.graphics.Color.parseColor("#F8FAFC")
            }
            if (darkTheme) DarkColors else LightColors
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    // 状态栏/导航栏跟随主题（原版未处理，导致深色模式下状态栏图标不可见）
    val view = (context as? Activity)?.window
    if (view != null) {
        SideEffect {
            view.statusBarColor = colors.background.toArgb()
            view.navigationBarColor = colors.surface.toArgb()
            WindowCompat.getInsetsController(view, view.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}

/** 分组配色，用于日志分组标签。 */
object GroupColors {
    val colors = listOf(
        Color(0xFF64748B), // 默认 灰
        Color(0xFF7C3AED), // 加密 紫
        Color(0xFF0EA5E9), // 网络 蓝
        Color(0xFF059669), // 存储 绿
        Color(0xFFF59E0B), // 界面 橙
        Color(0xFFDC2626), // 系统 红
    )

    fun of(group: Int): Color = colors.getOrElse(group) { colors[0] }
}

/** 状态码配色。 */
fun statusColor(code: Int): Color = when {
    code == 0 -> Color(0xFF64748B)
    code in 200..299 -> Color(0xFF16A34A)
    code in 300..399 -> Color(0xFF0EA5E9)
    code in 400..499 -> Color(0xFFF59E0B)
    code >= 500 -> Color(0xFFDC2626)
    else -> Color(0xFF64748B)
}

val ScreenPadding = 16.dp
val CardCorner = 12.dp

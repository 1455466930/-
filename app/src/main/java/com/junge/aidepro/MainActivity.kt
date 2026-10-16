package com.junge.aidepro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.junge.aidepro.ui.nav.BottomTab
import com.junge.aidepro.ui.nav.Dest
import com.junge.aidepro.ui.screens.AboutScreen
import com.junge.aidepro.ui.screens.AppDetailScreen
import com.junge.aidepro.ui.screens.AppListScreen
import com.junge.aidepro.ui.screens.CaptureHistoryScreen
import com.junge.aidepro.ui.screens.CaptureScreen
import com.junge.aidepro.ui.screens.FridaEditorScreen
import com.junge.aidepro.ui.screens.FridaLogScreen
import com.junge.aidepro.ui.screens.HookListScreen
import com.junge.aidepro.ui.screens.HttpDetailScreen
import com.junge.aidepro.ui.screens.LogDetailScreen
import com.junge.aidepro.ui.screens.LogListScreen
import com.junge.aidepro.ui.screens.McpConsoleScreen
import com.junge.aidepro.ui.screens.SessionDetailScreen
import com.junge.aidepro.ui.screens.SettingsScreen
import com.junge.aidepro.ui.screens.TcpDetailScreen
import com.junge.aidepro.ui.theme.AideProTheme
import dagger.hilt.android.AndroidEntryPoint
import com.junge.aidepro.ui.screens.HomeScreen

/**
 * 单 Activity + Compose Navigation。
 *
 * 原版是 19 个 Activity + Fragment + ViewPager 的混合结构，
 * 页面跳转与状态恢复都要靠 Intent 和 onSaveInstanceState 手工维护。
 * 这里统一由 Navigation 管理，列表滚动位置等状态跟随 ViewModel 存活。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AideProTheme {
                AideProRoot()
            }
        }
    }
}

@Composable
private fun AideProRoot() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val isMainTab = BottomTab.entries.any { it.dest.route == currentRoute }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (isMainTab) {
                NavigationBar {
                    BottomTab.entries.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == tab.dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.dest.title) },
                            label = { Text(tab.dest.title) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Home.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Home.route) {
                HomeScreen(onOpenMcp = { navController.navigate(Dest.McpConsole.route) })
            }
            composable(Dest.Apps.route) {
                AppListScreen(onOpenApp = { navController.navigate(Dest.AppDetail.of(it)) })
            }
            composable(Dest.Logs.route) {
                LogListScreen(onOpenLog = { navController.navigate(Dest.LogDetail.of(it)) })
            }
            composable(Dest.Capture.route) {
                CaptureScreen(
                    onOpenHistory = { navController.navigate(Dest.CaptureHistory.route) },
                    onOpenSession = { navController.navigate(Dest.SessionDetail.of(it)) },
                    onOpenHttp = { navController.navigate(Dest.HttpDetail.of(it)) },
                )
            }
            composable(Dest.Settings.route) {
                SettingsScreen(
                    onOpenMcp = { navController.navigate(Dest.McpConsole.route) },
                    onOpenAbout = { navController.navigate(Dest.About.route) },
                )
            }

            composable(
                Dest.AppDetail.route,
                arguments = listOf(navArgument(Dest.PACKAGE_ARG) { type = NavType.StringType }),
            ) { entry ->
                val pkg = entry.arguments?.getString(Dest.PACKAGE_ARG).orEmpty()
                AppDetailScreen(
                    packageName = pkg,
                    onBack = { navController.popBackStack() },
                    onOpenHooks = { navController.navigate(Dest.HookList.of(pkg)) },
                    onOpenFrida = { navController.navigate(Dest.FridaEditor.of(pkg)) },
                    onOpenFridaLog = { navController.navigate(Dest.FridaLog.of(pkg)) },
                )
            }

            composable(
                Dest.LogDetail.route,
                arguments = listOf(navArgument(Dest.ID_ARG) { type = NavType.LongType }),
            ) { entry ->
                LogDetailScreen(
                    logId = entry.arguments?.getLong(Dest.ID_ARG) ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Dest.CaptureHistory.route) {
                CaptureHistoryScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSession = { navController.navigate(Dest.SessionDetail.of(it)) },
                )
            }

            composable(
                Dest.SessionDetail.route,
                arguments = listOf(navArgument(Dest.SESSION_ARG) { type = NavType.StringType }),
            ) { entry ->
                SessionDetailScreen(
                    sessionId = entry.arguments?.getString(Dest.SESSION_ARG).orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenHttp = { navController.navigate(Dest.HttpDetail.of(it)) },
                    onOpenTcp = { navController.navigate(Dest.TcpDetail.of(it)) },
                )
            }

            composable(
                Dest.HttpDetail.route,
                arguments = listOf(navArgument(Dest.ID_ARG) { type = NavType.LongType }),
            ) { entry ->
                HttpDetailScreen(
                    recordId = entry.arguments?.getLong(Dest.ID_ARG) ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                Dest.TcpDetail.route,
                arguments = listOf(navArgument(Dest.ID_ARG) { type = NavType.LongType }),
            ) { entry ->
                TcpDetailScreen(
                    recordId = entry.arguments?.getLong(Dest.ID_ARG) ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                Dest.HookList.route,
                arguments = listOf(navArgument(Dest.PACKAGE_ARG) { type = NavType.StringType }),
            ) { entry ->
                HookListScreen(
                    packageName = entry.arguments?.getString(Dest.PACKAGE_ARG).orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                Dest.FridaEditor.route,
                arguments = listOf(navArgument(Dest.PACKAGE_ARG) { type = NavType.StringType }),
            ) { entry ->
                FridaEditorScreen(
                    packageName = entry.arguments?.getString(Dest.PACKAGE_ARG).orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                Dest.FridaLog.route,
                arguments = listOf(navArgument(Dest.PACKAGE_ARG) { type = NavType.StringType }),
            ) { entry ->
                FridaLogScreen(
                    packageName = entry.arguments?.getString(Dest.PACKAGE_ARG).orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Dest.McpConsole.route) {
                McpConsoleScreen(onBack = { navController.popBackStack() })
            }
            composable(Dest.About.route) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
            composable(Dest.Feedback.route) {
                AboutScreen(onBack = { navController.popBackStack() }, feedbackOnly = true)
            }
        }
    }
}

package com.ktwo.cts.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.ktwo.cts.data.Prefs
import dev.chrisbanes.haze.HazeState

private data class TopDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

// CPU 控制作为第 2 个 tab
private val TABS = listOf(
    TopDest("home",     "首页", CtsIcons.Speed),
    TopDest("cpu",      "CPU", Icons.Outlined.Memory),
    TopDest("apps",     "应用", CtsIcons.Apps),
    TopDest("logs",     "日志", CtsIcons.Logs),
    TopDest("settings", "设置", CtsIcons.Settings),
)

/**
 * 顶层骨架 - SukiSU-Ultra 风格 + 真·液态玻璃:
 *  - 底层整屏壁纸(WallpaperBackground)作为 Haze 模糊源
 *  - 内容透明叠在壁纸上;底部悬浮玻璃胶囊导航栏真实模糊穿透
 */
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val curRoute = entry?.destination?.hierarchy
    val hazeState = remember { HazeState() }
    val ctx = LocalContext.current
    val view = LocalView.current
    val wallpaperUri by Prefs.wallpaperUriFlow(ctx).collectAsState(initial = "")

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            // 1) 壁纸模糊源(最底层)
            WallpaperBackground(hazeState, wallpaperUri = wallpaperUri)

            // 2) 内容层(透明,叠在壁纸上)
            val selectedIndex = TABS.indexOfFirst { d ->
                curRoute?.any { it.route == d.route } == true
            }.coerceAtLeast(0)
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    FloatingGlassNav(
                        items = TABS.map { it.label to it.icon },
                        selectedIndex = selectedIndex,
                    ) { idx ->
                        val d = TABS[idx]
                        if (d.route != entry?.destination?.route) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        }
                        nav.navigate(d.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            ) { _ ->
                // 轻量快速淡入淡出过渡(去掉整页缩放,避免缩放时反复重算 Haze 模糊导致卡顿)
                NavHost(
                    navController = nav,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { fadeIn(tween(140)) },
                    exitTransition = { fadeOut(tween(140)) },
                    popEnterTransition = { fadeIn(tween(140)) },
                    popExitTransition = { fadeOut(tween(140)) },
                ) {
                    composable("home")     { HomeScreen() }
                    composable("cpu")      { CpuControlScreen() }
                    composable("apps")     { AppsScreen() }
                    composable("logs")     { LogScreen() }
                    composable("settings") { SettingsScreen() }
                }
            }
        }
    }
}

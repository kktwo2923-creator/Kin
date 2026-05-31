package com.ktwo.cts.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import dev.chrisbanes.haze.HazeState

private data class TopDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TABS = listOf(
    TopDest("home",     "首页", CtsIcons.Speed),
    TopDest("apps",     "应用", CtsIcons.Apps),
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

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(Modifier.fillMaxSize()) {
            // 1) 壁纸模糊源(最底层)
            WallpaperBackground(hazeState)

            // 2) 内容层(透明,叠在壁纸上)
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    FloatingGlassNav(
                        items = TABS.map { d ->
                            Triple(d.label, d.icon, curRoute?.any { it.route == d.route } == true)
                        }
                    ) { idx ->
                        val d = TABS[idx]
                        nav.navigate(d.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            ) { _ ->
                NavHost(
                    navController = nav,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable("home")     { HomeScreen() }
                    composable("apps")     { AppsScreen() }
                    composable("settings") { SettingsScreen() }
                }
            }
        }
    }
}

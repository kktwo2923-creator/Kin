package com.ktwo.cts.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

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
 * 顶层骨架 - SukiSU-Ultra 风格 + 液态玻璃底栏:
 *  - 内容层标 hazeSource,底部 GlassNavBar 用 Haze 真实模糊其后方内容
 *  - 各页面自带可折叠 LargeTopAppBar
 */
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val curRoute = entry?.destination?.hierarchy
    val hazeState = remember { HazeState() }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Scaffold(
            bottomBar = {
                GlassNavBar {
                    TABS.forEach { d ->
                        val selected = curRoute?.any { it.route == d.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(d.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label) }
                        )
                    }
                }
            }
        ) { _ ->
            // 内容画到底栏后方并标记为模糊源; 各页面自行处理状态栏/底栏 inset
            NavHost(
                navController = nav,
                startDestination = "home",
                modifier = Modifier.fillMaxSize().hazeSource(hazeState)
            ) {
                composable("home")     { HomeScreen() }
                composable("apps")     { AppsScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    }
}

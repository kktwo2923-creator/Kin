package com.ktwo.cts.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*

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
 * 顶层骨架 - SukiSU-Ultra 风格:
 *  - 仅持有底部 NavigationBar,各页面自带可折叠 LargeTopAppBar
 *  - NavigationBarItem 选中态用实心图标
 */
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val curRoute = entry?.destination?.hierarchy

    Scaffold(
        bottomBar = {
            NavigationBar {
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
    ) { padding ->
        // 各页面自带顶栏并消费状态栏 inset,这里只下传底部导航栏高度
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
        ) {
            composable("home")     { HomeScreen() }
            composable("apps")     { AppsScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}

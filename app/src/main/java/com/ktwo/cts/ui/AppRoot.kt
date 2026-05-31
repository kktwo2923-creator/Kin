package com.ktwo.cts.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*

private data class TopDest(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    TopDest("home",     "首页", Icons.Outlined.Speed),
    TopDest("apps",     "应用", Icons.Outlined.Apps),
    TopDest("settings", "设置", Icons.Outlined.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("OnePlus V Pro") })
        },
        bottomBar = {
            NavigationBar {
                TABS.forEach { d ->
                    NavigationBarItem(
                        selected = entry?.destination?.hierarchy?.any { it.route == d.route } == true,
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
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home")     { HomeScreen() }
            composable("apps")     { AppsScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}

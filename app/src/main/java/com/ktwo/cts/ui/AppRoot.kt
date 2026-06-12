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
import com.ktwo.cts.ui.theme.LocalEnableBlur
import com.ktwo.cts.ui.theme.LocalEnableFloatingBottomBar
import com.ktwo.cts.ui.theme.LocalEnableFloatingBottomBarBlur
import dev.chrisbanes.haze.HazeState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

private data class TopDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TABS = listOf(
    TopDest("home",     "首页", CtsIcons.Speed),
    TopDest("cpu",      "CPU", Icons.Outlined.Memory),
    TopDest("apps",     "应用", CtsIcons.Apps),
    TopDest("logs",     "日志", CtsIcons.Logs),
    TopDest("settings", "设置", CtsIcons.Settings),
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val curRoute = entry?.destination?.hierarchy
    val hazeState = remember { HazeState() }
    val ctx = LocalContext.current
    val view = LocalView.current
    val wallpaperUri by Prefs.wallpaperUriFlow(ctx).collectAsState(initial = "")

    val enableBlur = LocalEnableBlur.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    // 同 SukiSU MainScreen 双采样源: blurBackdrop 供非悬浮底栏的模糊,
    // backdrop 供悬浮底栏液态玻璃折射(整页内容+壁纸)
    val blurBackdrop = rememberBlurBackdrop(enableBlur)
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        val selectedIndex = TABS.indexOfFirst { d ->
            curRoute?.any { it.route == d.route } == true
        }.coerceAtLeast(0)
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                BottomBar(
                    blurBackdrop = blurBackdrop,
                    backdrop = backdrop,
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
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop)
                        else Modifier
                    )
                    .then(
                        if (enableFloatingBottomBar && enableFloatingBottomBarBlur)
                            Modifier.layerBackdrop(backdrop)
                        else Modifier
                    )
            ) {
                WallpaperBackground(hazeState, wallpaperUri = wallpaperUri)

                // 淡入淡出过渡;整页缩放会反复重算模糊导致卡顿
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

package com.ktwo.cts.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.runtime.*
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.ktwo.cts.data.Prefs
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

// 关闭智能调度时的常规 tab(含 CPU / 应用)
private val TABS_NORMAL = listOf(
    TopDest("home",     "首页", CtsIcons.Speed),
    TopDest("cpu",      "CPU", Icons.Outlined.Memory),
    TopDest("apps",     "应用", CtsIcons.Apps),
    TopDest("logs",     "日志", CtsIcons.Logs),
    TopDest("settings", "设置", CtsIcons.Settings),
)

// 开启智能调度时:隐藏 CPU / 应用,改为「监控」面板(手动档无意义)
private val TABS_SMART = listOf(
    TopDest("home",     "首页", CtsIcons.Speed),
    TopDest("monitor",  "监控", Icons.Outlined.Insights),
    TopDest("records",  "记录", Icons.Outlined.Timeline),
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

    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val surfaceColor = MaterialTheme.colorScheme.surface
    // 同 SukiSU MainScreen: backdrop 供悬浮底栏液态玻璃折射(整页内容+壁纸)
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    // 智能调度是否开启:开启时换成监控面板的 tab 集(隐藏 CPU/应用)。每 3s 轮询一次。
    // null = 首次状态尚未读到(此期间不做任何跳转, 避免启动瞬间误踢页面)。
    var smartOn by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            smartOn = withContext(Dispatchers.IO) { RootShell.isSmartOn() }
            delay(3000)
        }
    }
    val smart = smartOn == true
    val tabs = if (smart) TABS_SMART else TABS_NORMAL

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        val selectedIndex = tabs.indexOfFirst { d ->
            curRoute?.any { it.route == d.route } == true
        }.coerceAtLeast(0)
        val curDestRoute = entry?.destination?.route
        // 当前 tab 集(随智能调度切换)允许的路由 + 两个详情页。
        val validRoutes = remember(tabs) {
            (tabs.map { it.route } + listOf("about", "automode")).toSet()
        }
        // 切换智能调度后, 若正停在【当前 tab 集里已不存在】的页面(如智能开后停在"应用",
        // 或智能关后停在"记录"), 统一回首页。只在状态已确定(smartOn!=null)且路由确实越界时跳,
        // 不再无条件踢到"监控" —— 之前那版会因 smartOn 抖动把"记录/应用"也弹回监控。
        LaunchedEffect(smartOn, curDestRoute) {
            if (smartOn == null) return@LaunchedEffect
            val r = curDestRoute ?: return@LaunchedEffect
            if (r !in validRoutes) {
                nav.navigate("home") {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = false }
                    launchSingleTop = true
                }
            }
        }
        val hideBottomBar = curDestRoute == "about" || curDestRoute == "automode"
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (!hideBottomBar) BottomBar(
                    backdrop = backdrop,
                    items = tabs.map { it.label to it.icon },
                    selectedIndex = selectedIndex,
                ) { idx ->
                    val d = tabs[idx]
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
                    composable("monitor")  { AutoMonitorScreen() }
                    composable("records")  { RecordsScreen() }
                    composable("logs")     { LogScreen() }
                    composable("settings") {
                        SettingsScreen(
                            onOpenAbout = { nav.navigate("about") },
                            onOpenAutoMode = { nav.navigate("automode") },
                        )
                    }
                    composable("about") {
                        AboutScreen(onBack = { nav.popBackStack() })
                    }
                    composable("automode") {
                        AutoModeScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}

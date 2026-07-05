package com.ktwo.cts.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.license.Pro
import com.ktwo.cts.ui.fpsrecord.FpsRecordDetailScreen
import com.ktwo.cts.ui.fpsrecord.FpsRecordListScreen
import com.ktwo.cts.ui.process.ProcessScreen
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

// 固定四个底栏 tab: 首页 / 功能 / 日志 / 设置。
// 「功能」是一个聚合入口(hub), 把 监控 / 记录 / 应用 / 配置 / 智能调度高级设置 收进去。
private val TABS = listOf(
    TopDest("home",     "首页", CtsIcons.Speed),
    TopDest("features", "功能", Icons.Outlined.GridView),
    TopDest("logs",     "日志", CtsIcons.Logs),
    TopDest("settings", "设置", CtsIcons.Settings),
)

// 「功能」hub 下属的子页面: 选中它们时底栏仍高亮「功能」tab。
private val FEATURE_SUBROUTES = setOf("monitor", "records", "apps", "cpu", "automode", "perfmon")

// 自带返回/沉浸的二级页, 隐藏底栏。
private val FULLSCREEN_ROUTES =
    setOf("about", "automode", "pro", "perfmon", "fpsrecord", "fpsrecord_detail/{id}", "process")

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

    // 付费解锁态: App 启动时从 License 同步一次(root/网络在 IO 线程), 之后 UI 秒读 Prefs 缓存。
    val proUnlocked by Prefs.proUnlockedFlow(ctx).collectAsState(initial = false)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { Pro.sync(ctx) } }
    }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        val curDestRoute = entry?.destination?.route
        // 选中项: 子页面(监控/记录/应用/配置/智能高级)一律高亮「功能」tab。
        val selectedIndex = run {
            val onFeatureSub = curDestRoute != null &&
                (curDestRoute in FEATURE_SUBROUTES)
            if (onFeatureSub) TABS.indexOfFirst { it.route == "features" }
            else TABS.indexOfFirst { d -> curRoute?.any { it.route == d.route } == true }
                .coerceAtLeast(0)
        }
        // 二级页(自带返回/沉浸)隐藏底栏; 功能 hub 的其余子页面(监控/记录/应用/配置)保留底栏。
        val hideBottomBar = curDestRoute in FULLSCREEN_ROUTES
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (!hideBottomBar) BottomBar(
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
                    composable("features") {
                        FeaturesScreen(
                            onOpenPerfMon  = { nav.navigate("perfmon") },
                            onOpenMonitor  = { nav.navigate("monitor") },
                            onOpenRecords  = { nav.navigate("records") },
                            onOpenApps     = { nav.navigate("apps") },
                            onOpenCpu      = { nav.navigate("cpu") },
                            onOpenAutoMode = { nav.navigate("automode") },
                            onOpenFpsRecord = { nav.navigate("fpsrecord") },
                            onOpenProcess = { nav.navigate("process") },
                        )
                    }
                    composable("cpu")      { CpuControlScreen() }
                    composable("apps")     { AppsScreen() }
                    composable("monitor")  { AutoMonitorScreen() }
                    composable("records")  { RecordsScreen() }
                    composable("logs")     { LogScreen() }
                    composable("settings") {
                        SettingsScreen(
                            onOpenAbout = { nav.navigate("about") },
                            onOpenPro = { nav.navigate("pro") },
                        )
                    }
                    composable("about") {
                        AboutScreen(onBack = { nav.popBackStack() })
                    }
                    composable("automode") {
                        AutoModeScreen(
                            onBack = { nav.popBackStack() },
                            proUnlocked = proUnlocked,
                            onNeedPro = { nav.navigate("pro") },
                        )
                    }
                    composable("perfmon") {
                        MonitorOverlayScreen(onBack = { nav.popBackStack() })
                    }
                    composable("process") {
                        ProcessScreen(onBack = { nav.popBackStack() })
                    }
                    composable("pro") {
                        ProUnlockScreen(onBack = { nav.popBackStack() })
                    }
                    composable("fpsrecord") {
                        FpsRecordListScreen(
                            onBack = { nav.popBackStack() },
                            onOpenDetail = { id -> nav.navigate("fpsrecord_detail/$id") },
                        )
                    }
                    composable(
                        "fpsrecord_detail/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { backEntry ->
                        FpsRecordDetailScreen(
                            id = backEntry.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}

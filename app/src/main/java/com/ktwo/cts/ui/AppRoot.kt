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

    // 智能调度是否开启 → 决定底栏 tab 集(开:监控/记录;关:CPU/应用)。
    // 先用上次落盘的 Prefs 值即时显示(避免冷启动空窗), 后台持续探测 root 状态,
    // 【连续两次读到一致的新值时才更新】—— 单次 shell 偶发失败(冷启动 root 未就绪/读空)
    // 不会改变结果, 既防抖动乱切 tab, 又能在 root 就绪后可靠收敛到真实状态。
    val persistedSmart by Prefs.smartEnabledFlow(ctx).collectAsState(initial = null)
    var liveSmart by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        var prev: Boolean? = null
        while (true) {
            // null = 这次没读到(root 未就绪/文件不可读): 直接忽略, 不污染判定, 下次再试。
            val now = withContext(Dispatchers.IO) { RootShell.probeSmartOn() }
            if (now != null) {
                // 首次判定(还没有任何已落盘的值): 直接采纳, tab 集尽快到位;
                // 之后的【变化】必须连续两次一致才认账 —— 单次误读不改结果, 防抖动乱切 tab。
                val firstEver = liveSmart == null && persistedSmart == null
                val accept = if (firstEver) true else (now == prev)
                if (accept && now != liveSmart) {
                    liveSmart = now
                    Prefs.setSmartEnabled(ctx, now)
                }
                prev = now
            }
            delay(1500)
        }
    }
    val smartOn: Boolean? = liveSmart ?: persistedSmart
    val smart = smartOn == true
    val tabs = if (smart) TABS_SMART else TABS_NORMAL

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        val selectedIndex = tabs.indexOfFirst { d ->
            curRoute?.any { it.route == d.route } == true
        }.coerceAtLeast(0)
        val curDestRoute = entry?.destination?.route
        // 切换智能调度后, 若正停在【另一套 tab 才有】的页面(智能开却停在 CPU/应用,
        // 或智能关却停在 监控/记录), 回首页。其余一律不动 ——
        // 显式只针对这 4 个"跨集"路由, 直接读 smartOn(本效应的 key, 不会取到旧值),
        // 所以智能开时点"监控/记录"永远不会被它误踢(它们不在被踢名单里)。
        LaunchedEffect(smartOn, curDestRoute) {
            val s = smartOn ?: return@LaunchedEffect          // 状态未定前不跳转
            val r = curDestRoute ?: return@LaunchedEffect
            val stranded = if (s) (r == "cpu" || r == "apps")
                           else   (r == "monitor" || r == "records")
            if (stranded) {
                nav.navigate("home") {
                    popUpTo(nav.graph.findStartDestination().id)
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

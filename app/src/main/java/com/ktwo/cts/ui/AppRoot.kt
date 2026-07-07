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
import kotlinx.coroutines.launch
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
import com.ktwo.cts.ui.coloros.ColorOsScreen
import com.ktwo.cts.ui.docs.DocsScreen
import com.ktwo.cts.ui.fpsrecord.FpsRecordDetailScreen
import com.ktwo.cts.ui.fpsrecord.FpsRecordListScreen
import com.ktwo.cts.ui.fpsrecord.ThreadLoadScreen
import com.ktwo.cts.ui.governor.GovernorParamsScreen
import com.ktwo.cts.ui.memory.MemoryDetailScreen
import com.ktwo.cts.ui.process.ProcessScreen
import com.ktwo.cts.ui.threadalloc.ManualAllocScreen
import com.ktwo.cts.ui.threadalloc.ThreadRulesScreen
import com.ktwo.cts.ui.threadalloc.ThreadAllocScreen
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
    setOf(
        "about", "automode", "pro", "perfmon", "fpsrecord",
        "fpsrecord_detail/{id}", "fpsrecord_thread/{id}", "process", "threadalloc",
        "threadalloc_manual", "threadalloc_rules", "memory", "governor", "docs", "coloros",
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

    // 付费解锁态: App 启动时从 License 同步一次(root/网络在 IO 线程), 之后 UI 秒读 Prefs 缓存。
    val proUnlocked by Prefs.proUnlockedFlow(ctx).collectAsState(initial = false)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { Pro.sync(ctx) } }
    }

    // 首启引导: docs_seen=false 时弹一次「先看文档」。initial=true 防止 DataStore 加载前误闪弹窗;
    // 无论选「查看文档」还是「稍后」都置 true, 只打扰这一次。
    val scope = rememberCoroutineScope()
    val docsSeen by Prefs.docsSeenFlow(ctx).collectAsState(initial = true)

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
                            onOpenThreadAlloc = { nav.navigate("threadalloc") },
                            onOpenMemory = { nav.navigate("memory") },
                            onOpenGovernor = { nav.navigate("governor") },
                            onOpenColorOs = { nav.navigate("coloros") },
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
                            onOpenDocs = { nav.navigate("docs") },
                        )
                    }
                    composable("about") {
                        AboutScreen(onBack = { nav.popBackStack() })
                    }
                    composable("docs") {
                        DocsScreen(onBack = { nav.popBackStack() })
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
                    composable("memory") {
                        MemoryDetailScreen(onBack = { nav.popBackStack() })
                    }
                    composable("governor") {
                        GovernorParamsScreen(onBack = { nav.popBackStack() })
                    }
                    composable("coloros") {
                        ColorOsScreen(onBack = { nav.popBackStack() })
                    }
                    composable("threadalloc") {
                        ThreadAllocScreen(
                            onBack = { nav.popBackStack() },
                            proUnlocked = proUnlocked,
                            onNeedPro = { nav.navigate("pro") },
                            onOpenManual = { nav.navigate("threadalloc_manual") },
                            onOpenRules = { nav.navigate("threadalloc_rules") },
                        )
                    }
                    composable("threadalloc_rules") {
                        ThreadRulesScreen(onBack = { nav.popBackStack() })
                    }
                    composable("threadalloc_manual") {
                        ManualAllocScreen(
                            onBack = { nav.popBackStack() },
                            proUnlocked = proUnlocked,
                            onNeedPro = { nav.navigate("pro") },
                        )
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
                            onOpenThreads = { id -> nav.navigate("fpsrecord_thread/$id") },
                        )
                    }
                    composable(
                        "fpsrecord_thread/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { backEntry ->
                        ThreadLoadScreen(
                            id = backEntry.arguments?.getString("id").orEmpty(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                }

                // 首次使用引导弹窗(只弹一次, 不阻塞主流程)
                if (!docsSeen) {
                    AlertDialog(
                        onDismissRequest = {
                            scope.launch { Prefs.setDocsSeen(ctx, true) }
                        },
                        title = { Text("欢迎使用 CTS") },
                        text = {
                            Text(
                                "首次使用建议先阅读使用文档, 了解各功能的作用与所需权限。" +
                                    "之后也可以随时在「设置 → 使用文档」中查看。"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch { Prefs.setDocsSeen(ctx, true) }
                                nav.navigate("docs")
                            }) { Text("查看文档") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                scope.launch { Prefs.setDocsSeen(ctx, true) }
                            }) { Text("稍后") }
                        },
                    )
                }
            }
        }
    }
}

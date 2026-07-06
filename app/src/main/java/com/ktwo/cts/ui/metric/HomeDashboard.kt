package com.ktwo.cts.ui.metric

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ktwo.cts.data.MetricReader
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.ProcSampler
import com.ktwo.cts.data.SysfsReader
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Metric 浅色仪表盘主页(仅 METRIC 风格插入 HomeScreen):
 * 内存卡 / GPU 卡 / 进程+CPU 双卡 / 每簇频率网格卡。
 * 自带 1s 采样(STARTED 生命周期内), 不依赖任何外部 ViewModel。
 */
@Composable
fun HomeDashboard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cellCount by Prefs.cellCountFlow(ctx).collectAsState(initial = 1)

    var snap by remember { mutableStateOf<MetricReader.Snapshot?>(null) }
    var cpuHist by remember { mutableStateOf(listOf<Float>()) }
    var clusterHist by remember { mutableStateOf(mapOf<Int, List<Float>>()) }
    var procs by remember { mutableStateOf(listOf<ProcSampler.Proc>()) }
    var rooted by remember { mutableStateOf(true) }
    var minMax by remember { mutableStateOf(listOf<Pair<Int, Int>>()) }
    var coreCounts by remember { mutableStateOf(listOf<Int>()) }
    var gpuModel by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            @Suppress("DEPRECATION")
            runCatching {
                MetricReader.fallbackHz =
                    (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                        .defaultDisplay.refreshRate.toInt()
            }
            // 静态信息只取一次: 每簇 min/max 频率、核数分布、GPU 型号
            if (minMax.isEmpty()) {
                val static = withContext(Dispatchers.IO) {
                    runCatching {
                        val pols = SysfsReader.discoverPolicies()
                        Triple(
                            pols.map { SysfsReader.minMaxFreqMhz(it) },
                            pols.map { SysfsReader.relatedCpus(it).size },
                            runCatching {
                                File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim()
                            }.getOrDefault(""),
                        )
                    }.getOrNull()
                }
                if (static != null) {
                    minMax = static.first
                    coreCounts = static.second
                    gpuModel = static.third
                }
            }
            while (isActive) {
                val s = withContext(Dispatchers.IO) {
                    runCatching { MetricReader.snapshot(cellCount) }.getOrNull()
                }
                if (s != null) {
                    snap = s
                    if (s.cpuLoadPct >= 0) {
                        cpuHist = (cpuHist + s.cpuLoadPct / 100f).takeLast(30)
                    }
                    val m = clusterHist.toMutableMap()
                    s.clusters.forEachIndexed { i, c ->
                        val mx = minMax.getOrNull(i)?.second ?: 0
                        val v = if (mx > 0 && c.mhz > 0) (c.mhz.toFloat() / mx).coerceIn(0f, 1f) else 0f
                        m[i] = ((m[i] ?: emptyList()) + v).takeLast(30)
                    }
                    clusterHist = m
                }
                val list = withContext(Dispatchers.IO) {
                    runCatching {
                        rooted = RootShell.isRooted()
                        if (rooted) ProcSampler.topProcesses(5) else emptyList()
                    }.getOrDefault(emptyList())
                }
                procs = list
                delay(1000)
            }
        }
    }

    // GPU 静态信息三行: Vulkan / GPU 型号 / OpenGL ES(取不到用占位)
    val gpuInfoLines = remember(gpuModel) {
        val gles = runCatching {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.deviceConfigurationInfo?.glEsVersion
        }.getOrNull().orEmpty()
        val vulkan = runCatching {
            ctx.packageManager.systemAvailableFeatures
                .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
                ?.version
        }.getOrNull()?.let { v -> "${v shr 22}.${(v shr 12) and 0x3FF}.${v and 0xFFF}" } ?: "1.1"
        listOf(
            "Vulkan API: $vulkan",
            gpuModel.ifBlank { "Adreno / Mali GPU" },
            "OpenGL ES ${gles.ifBlank { "3.2" }}",
        )
    }

    // 芯片名: SOC_MODEL(minSdk33 直接用), unknown 时回退 HARDWARE; 附核数分布(如 6+2)
    val chipLine = remember(coreCounts) {
        val soc = Build.SOC_MODEL.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
            ?: Build.HARDWARE
        if (coreCounts.isNotEmpty() && coreCounts.all { it > 0 })
            "$soc (${coreCounts.joinToString("+")})"
        else soc
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MemoryCard(snap, Modifier.fillMaxWidth())
        GpuCard(snap, gpuInfoLines, Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProcessListCard(procs, rooted, Modifier.weight(1f).fillMaxHeight())
            CpuCard(snap, cpuHist, chipLine, Modifier.weight(1f).fillMaxHeight())
        }
        ClusterGridCard(snap, minMax, clusterHist, Modifier.fillMaxWidth())
    }
}

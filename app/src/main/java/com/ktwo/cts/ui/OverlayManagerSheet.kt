// 悬浮窗管理器(仿 Metric): 底部弹窗, 六个独立监视器各自开关。
package com.ktwo.cts.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeviceThermostat
import androidx.compose.material.icons.outlined.PanoramaFishEye
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ktwo.cts.data.OverlayType
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.overlay.FloatWindowService
import com.ktwo.cts.ui.theme.isInDarkTheme
import kotlinx.coroutines.launch

private data class OvlSpec(val type: OverlayType, val icon: ImageVector, val tint: Color, val container: Color)

private val OVL_SPECS = listOf(
    OvlSpec(OverlayType.LOAD,    Icons.Outlined.Speed,               Color(0xFF0FA5A5), Color(0xFFDFF4F2)),
    OvlSpec(OverlayType.PROCESS, Icons.AutoMirrored.Outlined.ListAlt, Color(0xFF34A853), Color(0xFFE4F4E8)),
    OvlSpec(OverlayType.THREAD,  Icons.Outlined.AccountTree,         Color(0xFF8B5CF6), Color(0xFFF0EAFD)),
    OvlSpec(OverlayType.MINI,    Icons.Outlined.PanoramaFishEye,     Color(0xFFFF8A34), Color(0xFFFFF0E2)),
    OvlSpec(OverlayType.FPS,     Icons.AutoMirrored.Outlined.ShowChart, Color(0xFFEC4899), Color(0xFFFDE9F3)),
    OvlSpec(OverlayType.TEMP,    Icons.Outlined.DeviceThermostat,    Color(0xFFF97316), Color(0xFFFFEDE3)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayManagerSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = isInDarkTheme()
    var pendingType by remember { mutableStateOf<OverlayType?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val t = pendingType
        pendingType = null
        if (t != null && Settings.canDrawOverlays(ctx)) scope.launch {
            Prefs.setOvlEnabled(ctx, t, true)
            FloatWindowService.show(ctx, t)
        }
    }

    fun toggle(type: OverlayType, on: Boolean) {
        if (on && !Settings.canDrawOverlays(ctx)) {
            pendingType = type
            permLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            )
            return
        }
        scope.launch {
            Prefs.setOvlEnabled(ctx, type, on)
            if (on) FloatWindowService.show(ctx, type) else FloatWindowService.hide(ctx, type)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("悬浮窗管理器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("选择要显示的悬浮窗", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledIconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OVL_SPECS.forEach { spec ->
                    val on by Prefs.ovlEnabledFlow(ctx, spec.type).collectAsState(initial = false)
                    OverlayRow(spec, on, dark) { toggle(spec.type, it) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OverlayRow(spec: OvlSpec, checked: Boolean, dark: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable { onToggle(!checked) },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (dark) spec.tint.copy(alpha = 0.16f) else spec.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(spec.icon, contentDescription = null, tint = spec.tint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(spec.type.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(spec.type.desc, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Switch(
                checked = checked,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF2A6DF4)),
            )
        }
    }
}

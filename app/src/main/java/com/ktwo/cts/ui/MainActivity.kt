package com.ktwo.cts.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.service.NotifyService
import com.ktwo.cts.ui.theme.AppTheme
import com.ktwo.cts.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            if (runBlocking { Prefs.notifyEnabled(this@MainActivity) }) {
                NotifyService.start(this)
            }
        }
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val themeKey by Prefs.themeModeFlow(ctx).collectAsState(initial = "system")
            AppTheme(mode = ThemeMode.fromKey(themeKey)) {
                PermissionBootstrap()
                AppRoot()
            }
        }
    }
}

/**
 * 进入 App 时一次性索取所需权限:
 *  1) 通知权限(Android 13+,运行时弹窗)
 *  2) 悬浮窗权限(跳系统设置页)
 * Root 由 libsu 在首次执行命令时弹 su 授权。已授予的不会重复请求。
 */
@Composable
private fun PermissionBootstrap() {
    val ctx = LocalContext.current
    var done by rememberSaveable { mutableStateOf(false) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 返回后无需处理 */ }

    fun maybeRequestOverlay() {
        if (!Settings.canDrawOverlays(ctx)) {
            runCatching {
                overlayLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
                    )
                )
            }
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { maybeRequestOverlay() }

    LaunchedEffect(Unit) {
        if (done) return@LaunchedEffect
        done = true
        val needNotif = Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needNotif) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else maybeRequestOverlay()
    }
}

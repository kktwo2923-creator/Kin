package com.ktwo.cts.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.service.NotifyService
import com.ktwo.cts.ui.theme.AppTheme
import com.ktwo.cts.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 若开启了常驻通知,启动时确保服务在跑
        runCatching {
            if (runBlocking { Prefs.notifyEnabled(this@MainActivity) }) NotifyService.start(this)
        }
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val themeKey by Prefs.themeModeFlow(ctx).collectAsState(initial = "system")
            AppTheme(mode = ThemeMode.fromKey(themeKey)) {
                AppRoot()
            }
        }
    }
}

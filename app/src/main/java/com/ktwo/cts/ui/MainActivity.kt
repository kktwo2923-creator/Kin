package com.ktwo.cts.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.service.NotifyService
import com.ktwo.cts.ui.theme.AppTheme
import com.ktwo.cts.ui.theme.LocalEnableBlur
import com.ktwo.cts.ui.theme.LocalEnableFloatingBottomBar
import com.ktwo.cts.ui.theme.LocalEnableFloatingBottomBarBlur
import com.ktwo.cts.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            if (runBlocking { Prefs.notifyEnabled(this@MainActivity) }) NotifyService.start(this)
        }
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val themeKey by Prefs.themeModeFlow(ctx).collectAsState(initial = "dynamic")
            val enableBlur by Prefs.enableBlurFlow(ctx).collectAsState(initial = true)
            val enableFloatingBar by Prefs.enableFloatingBottomBarFlow(ctx)
                .collectAsState(initial = true)
            val enableFloatingBarBlur by Prefs.enableFloatingBottomBarBlurFlow(ctx)
                .collectAsState(initial = true)
            AppTheme(mode = ThemeMode.fromKey(themeKey)) {
                CompositionLocalProvider(
                    LocalEnableBlur provides enableBlur,
                    LocalEnableFloatingBottomBar provides enableFloatingBar,
                    LocalEnableFloatingBottomBarBlur provides enableFloatingBarBlur,
                ) {
                    AppRoot()
                }
            }
        }
    }
}

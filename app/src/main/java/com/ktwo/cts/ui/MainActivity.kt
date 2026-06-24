package com.ktwo.cts.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.ktwo.cts.BuildConfig
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.license.License
import com.ktwo.cts.service.NotifyService
import com.ktwo.cts.ui.theme.AppTheme
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
        // 捐赠版:打开即验(LicenseGate 负责)+ 联网恢复立刻验(下面登记);离线不锁,被吊销则红屏锁定并停守护。
        // 公益版(NO_LICENSE):彻底跳过,不登记自动复验。
        if (!BuildConfig.NO_LICENSE) {
            runCatching { License.installAutoRevalidation(this) }
        }
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val themeKey by Prefs.themeModeFlow(ctx).collectAsState(initial = "system")
            val enableFloatingBar by Prefs.enableFloatingBottomBarFlow(ctx)
                .collectAsState(initial = true)
            val enableFloatingBarBlur by Prefs.enableFloatingBottomBarBlurFlow(ctx)
                .collectAsState(initial = true)
            AppTheme(mode = ThemeMode.fromKey(themeKey)) {
                CompositionLocalProvider(
                    LocalEnableFloatingBottomBar provides enableFloatingBar,
                    LocalEnableFloatingBottomBarBlur provides enableFloatingBarBlur,
                ) {
                    // 捐赠版:授权门(未激活→激活页);公益版:直接进入主界面
                    if (BuildConfig.NO_LICENSE) {
                        AppRoot()
                    } else {
                        LicenseGate { AppRoot() }
                    }
                }
            }
        }
    }
}

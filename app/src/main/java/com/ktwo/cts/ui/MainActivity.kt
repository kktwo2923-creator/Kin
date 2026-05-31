package com.ktwo.cts.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.ui.theme.AppTheme
import com.ktwo.cts.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

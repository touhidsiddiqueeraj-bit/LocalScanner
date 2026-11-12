package com.localscanner.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.localscanner.app.di.AppContainer
import com.localscanner.app.ui.nav.AppNavGraph
import com.localscanner.app.ui.theme.LocalScannerTheme
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LocalScannerApp).container
        setContent {
            val themeMode by container.settings.settings
                .map { it.themeMode }
                .collectAsState(initial = "system")
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            LocalScannerTheme(darkTheme = darkTheme, dynamicColor = false) {
                AppNavGraph(container)
            }
        }
    }

    companion object {
        fun containerOf(activity: ComponentActivity): AppContainer =
            (activity.application as LocalScannerApp).container
    }
}

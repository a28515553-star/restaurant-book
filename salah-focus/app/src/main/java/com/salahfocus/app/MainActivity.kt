package com.salahfocus.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.salahfocus.app.core.Notifier
import com.salahfocus.app.core.Prefs
import com.salahfocus.app.ui.AppsScreen
import com.salahfocus.app.ui.HomeScreen
import com.salahfocus.app.ui.SalahTheme
import com.salahfocus.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        Notifier.reschedule(this)
        setContent {
            SalahTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                // Bumped whenever a setting changes so every screen re-reads the prefs.
                var rev by remember { mutableIntStateOf(0) }
                val changed = {
                    rev++
                    Notifier.reschedule(this@MainActivity)
                }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("الرئيسية") })
                            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Check, null) }, label = { Text("التطبيقات") })
                            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text("الإعدادات") })
                        }
                    },
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> HomeScreen(prefs, rev)
                            1 -> AppsScreen(prefs, changed)
                            else -> SettingsScreen(prefs, rev, changed)
                        }
                    }
                }
            }
        }
    }
}

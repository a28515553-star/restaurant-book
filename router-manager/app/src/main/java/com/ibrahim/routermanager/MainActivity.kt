package com.ibrahim.routermanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ibrahim.routermanager.ui.DevicesScreen
import com.ibrahim.routermanager.ui.MainViewModel
import com.ibrahim.routermanager.ui.RouterTheme
import com.ibrahim.routermanager.ui.RoutersScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RouterTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: MainViewModel = viewModel()) {
    val routers by vm.routers.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val names by vm.names.collectAsStateWithLifecycle()
    val known by vm.known.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(if (tab == 0) "الأجهزة المتصلة" else "الراوترات") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Person, null) }, label = { Text("الأجهزة") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Settings, null) }, label = { Text("الراوترات") })
            }
        },
    ) { pad ->
        if (tab == 0) {
            DevicesScreen(routers, states, vm::refresh, vm::perform, names, known, vm::rename, vm::acceptAll, Modifier.padding(pad))
        } else {
            RoutersScreen(routers, vm::newId, vm::saveRouter, vm::deleteRouter, Modifier.padding(pad))
        }
    }
}

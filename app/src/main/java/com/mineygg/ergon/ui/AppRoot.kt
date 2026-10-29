package com.mineygg.ergon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mineygg.ergon.MainViewModel

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Activity("Activity", Icons.Default.Info),
    Settings("Settings", Icons.Default.Settings)
}

@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var loginStep by rememberSaveable { mutableIntStateOf(1) }

    var showLicense by rememberSaveable { mutableStateOf(false) }
    var showExchange by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showLogin) { showLogin = false }
    BackHandler(enabled = showLicense) { showLicense = false }
    BackHandler(enabled = showExchange) { showExchange = false }

    val openLogin: (Int) -> Unit = { step ->
        loginStep = step
        showLogin = true
    }

    if (showLicense) {
        LicenseScreen(onClose = { showLicense = false })
        return
    }

    if (showExchange) {
        ExchangeScreen(vm, onClose = { showExchange = false })
        return
    }

    if (showLogin) {
        LoginScreen(vm, startStep = loginStep, onClose = { showLogin = false })
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    ) { pad ->
        when (tab) {
            Tab.Home -> HomeScreen(vm, pad, openLogin)
            Tab.Activity -> ActivityScreen(vm, pad)
            Tab.Settings -> SettingsScreen(vm, pad, openLogin, onLicense = { showLicense = true }, onExchange = { showExchange = true })
        }
    }
}

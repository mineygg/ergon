package com.mineygg.ergon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mineygg.ergon.AppInfo
import com.mineygg.ergon.MainViewModel
import com.mineygg.ergon.R
import com.mineygg.ergon.core.Game
import com.mineygg.ergon.core.Time
import com.mineygg.ergon.core.Updater
import com.mineygg.ergon.data.ExchangeMode
import com.mineygg.ergon.data.ThemeMode

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    pad: PaddingValues,
    onLogin: (Int) -> Unit,
    onLicense: () -> Unit,
    onExchange: () -> Unit
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val creds by vm.creds.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    var confirmLogout by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = pad.calculateTopPadding() + 12.dp,
            bottom = pad.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }

        // ── Appearance ──
        item {
            AppCard {
                SectionTitle("Appearance")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light", ThemeMode.SYSTEM to "System").forEach { (mode, label) ->
                        FilterChip(
                            selected = s.theme == mode,
                            onClick = { vm.updateSettings { it.copy(theme = mode) } },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        // ── Schedule ──
        item {
            AppCard {
                SectionTitle("Schedule")
                ToggleRow("Run automatically", "Daily check-in and hourly code watch, in the background", s.autoRun) { on ->
                    vm.updateSettings { it.copy(autoRun = on) }
                }
                HorizontalDivider(color = cs.outlineVariant)
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text("Check-in at ${Time.checkinLocalLabel()}", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    VSpace(2)
                    Text("Gift codes hourly", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    VSpace(2)
                    Text("Workshop and auto exchange around each game reset", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                HorizontalDivider(color = cs.outlineVariant)
                ToggleRow("Notifications", "Daily summary and session alerts", s.notify) { on ->
                    vm.updateSettings { it.copy(notify = on) }
                }
            }
        }

        // ── Tasks ──
        item {
            AppCard {
                SectionTitle("Tasks")
                ToggleRow("Daily check-in", null, s.checkin) { on -> vm.updateSettings { it.copy(checkin = on) } }
                HorizontalDivider(color = cs.outlineVariant)
                ToggleRow("Auto-redeem gift codes", "Genshin, Star Rail and Zenless", s.autoRedeem) { on ->
                    vm.updateSettings { it.copy(autoRedeem = on) }
                }
                HorizontalDivider(color = cs.outlineVariant)
                ToggleRow("Machine Workshop", "Genshin web event", s.workshop) { on ->
                    vm.updateSettings { it.copy(workshop = on) }
                }
                HorizontalDivider(color = cs.outlineVariant)
                ToggleRow("Auto exchange", "Spend workshop points on rewards", s.autoExchange) { on ->
                    vm.updateSettings { it.copy(autoExchange = on) }
                }
                if (s.autoExchange) {
                    VSpace(4)
                    OutlinedButton(
                        onClick = onExchange,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Exchange priority") }
                    VSpace(6)
                    Text(
                        exchangeSummary(s.exchangePriority.size, s.exchangeMode),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }
        }

        // ── Games ──
        item {
            AppCard {
                SectionTitle("Games")
                Game.entries.forEachIndexed { i, g ->
                    if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                    ToggleRow(g.title, null, g.id in s.enabledGames, dot = Color(g.color)) { on ->
                        vm.updateSettings {
                            it.copy(enabledGames = if (on) it.enabledGames + g.id else it.enabledGames - g.id)
                        }
                    }
                }
            }
        }

        // ── Updates ──
        item {
            AppCard {
                SectionTitle("Updates")
                ToggleRow("Check for updates", "Installed: ${Updater.currentVersion(context)}", s.checkUpdates) { on ->
                    vm.updateSettings { it.copy(checkUpdates = on) }
                }
                VSpace(4)
                UpdateControls(update, onCheck = { vm.checkUpdates() }, onUpdate = vm::startUpdate)
            }
        }

        // ── Open source ──
        item {
            AppCard {
                SectionTitle("Open source")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_github),
                        contentDescription = null,
                        tint = cs.onSurface,
                        modifier = Modifier.size(30.dp)
                    )
                    HSpace(14)
                    Column(Modifier.weight(1f)) {
                        Text("Ergon on GitHub", style = MaterialTheme.typography.bodyLarge)
                        Text("Source code, issues and releases", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                VSpace(12)
                OutlinedButton(
                    onClick = { uriHandler.openUri(AppInfo.REPO_URL) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Open repository") }
                VSpace(4)
                HorizontalDivider(color = cs.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onLicense).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("License", style = MaterialTheme.typography.bodyLarge)
                        Text(AppInfo.LICENSE_NAME, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = cs.onSurfaceVariant)
                }
            }
        }

        // ── Account ──
        item {
            AppCard {
                SectionTitle("Account")
                Text(
                    if (creds.hasHoyolab) "Signed in as UID ${creds.ltuid}" else "Not signed in",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
                VSpace(12)
                OutlinedButton(
                    onClick = { onLogin(1) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(if (creds.hasHoyolab) "Sign in again" else "Connect HoYoLAB") }
                if (creds.hasHoyolab) {
                    TextButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Sign out & delete tokens", color = cs.error)
                    }
                }
            }
        }

        item {
            Text(
                "Ergon ${Updater.currentVersion(context)} · tokens never leave this device",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out?") },
            text = { Text("Your saved tokens will be deleted. Automatic runs will stop working until you sign in again.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.logout()
                    confirmLogout = false
                }) { Text("Sign out", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } }
        )
    }
}

private fun exchangeSummary(count: Int, mode: ExchangeMode): String {
    val list = if (count == 0) "No priority set, only free rewards are claimed" else "$count in the priority list"
    return list + " · " + if (mode == ExchangeMode.WAIT) "waits for points" else "claims what it can afford"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
    VSpace(6)
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    dot: Color? = null,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            Dot(dot)
            HSpace(12)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

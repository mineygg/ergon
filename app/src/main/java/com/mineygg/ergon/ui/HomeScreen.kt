package com.mineygg.ergon.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mineygg.ergon.MainViewModel
import com.mineygg.ergon.core.CheckinStatus
import com.mineygg.ergon.core.Game
import com.mineygg.ergon.core.Live
import com.mineygg.ergon.core.Task
import com.mineygg.ergon.core.Time
import com.mineygg.ergon.data.Credentials
import com.mineygg.ergon.data.RunReport
import com.mineygg.ergon.data.Settings
import com.mineygg.ergon.data.WorkshopInfo
import java.time.Instant
import java.time.LocalDate

@Composable
fun HomeScreen(vm: MainViewModel, pad: PaddingValues, onLogin: (Int) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val creds by vm.creds.collectAsStateWithLifecycle()
    val checkin by vm.checkinReport.collectAsStateWithLifecycle()
    val redeem by vm.redeemReport.collectAsStateWithLifecycle()
    val lastCheck by vm.lastCodeCheck.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val workshop by vm.workshopInfo.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = pad.calculateTopPadding() + 12.dp,
            bottom = pad.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (settings.checkUpdates) item { UpdateBanner(update, onUpdate = vm::startUpdate) }
        item { Hero(settings, creds, checkin, active, live, onLogin) }
        item { CheckinCard(settings, creds, checkin, active, live, onRun = vm::runCheckin, onStop = vm::stop) }
        item { CodesCard(settings, creds, redeem, lastCheck, active, live, onRun = vm::runRedeem, onStop = vm::stop) }
        item { WorkshopCard(settings, creds, workshop, active, status, onRun = vm::runWorkshop, onStop = vm::stop) }
    }
}

private fun isToday(ms: Long): Boolean =
    Instant.ofEpochMilli(ms).atZone(Time.CST).toLocalDate() == LocalDate.now(Time.CST)

private fun doneToday(settings: Settings, report: RunReport?): Pair<Int, Int> {
    val games = Game.entries.filter { it.id in settings.enabledGames }
    val fresh = report?.takeIf { isToday(it.timeMillis) }
    val done = games.count { g ->
        val st = fresh?.checkins?.get(g.id)
        st == CheckinStatus.SUCCESS.name || st == CheckinStatus.ALREADY.name
    }
    return done to games.size
}

private fun liveDone(settings: Settings, live: Live): Pair<Int, Int> {
    val games = Game.entries.filter { it.id in settings.enabledGames }
    val done = games.count { g ->
        val st = live.statuses[g.id]
        st == CheckinStatus.SUCCESS.name || st == CheckinStatus.ALREADY.name
    }
    return done to games.size
}

private fun nextCheckinText(): String {
    val n = Time.nextCheckinAt().withZoneSameInstant(java.time.ZoneId.systemDefault())
    val day = if (n.toLocalDate() == LocalDate.now()) "today" else "tomorrow"
    return "$day · ${Time.checkinLocalLabel()}"
}

@Composable
private fun Hero(
    settings: Settings,
    creds: Credentials,
    report: RunReport?,
    active: Task?,
    live: Live,
    onLogin: (Int) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val extra = LocalExtra.current
    val (done, total) = if (active == Task.CHECKIN) liveDone(settings, live) else doneToday(settings, report)
    val expired = creds.hoyolabExpired || creds.loginExpired
    val allDone = total > 0 && done == total

    val headline = when {
        !creds.hasHoyolab -> "Connect your account"
        expired -> "Session expired"
        allDone -> "All claimed today"
        else -> "$done of $total claimed today"
    }
    val sub = when {
        !creds.hasHoyolab -> "Sign in once and it runs on its own."
        expired -> "Log in again to keep claiming."
        else -> "Next check-in ${nextCheckinText()}"
    }
    val onHero = cs.onPrimaryContainer
    val brush = Brush.linearGradient(
        listOf(cs.primaryContainer, cs.secondary.copy(alpha = 0.32f).compositeOver(cs.surface))
    )

    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(brush).padding(20.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "ERGON",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = onHero.copy(alpha = 0.65f)
                    )
                    VSpace(6)
                    Text(headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = onHero)
                    VSpace(4)
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = onHero.copy(alpha = 0.8f))
                }
                HSpace(12)
                when {
                    creds.hasHoyolab && !expired && total > 0 ->
                        ProgressRing(done.toFloat() / total, "$done/$total", onHero.copy(alpha = 0.16f), extra.success)
                    expired -> IconBadge(Icons.Default.Warning, extra.warning)
                    else -> IconBadge(Icons.Default.Lock, cs.primary)
                }
            }

            if (creds.hasHoyolab) {
                VSpace(14)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(
                        if (creds.hoyolabExpired) "Check-in · expired" else "Check-in · ready",
                        if (creds.hoyolabExpired) extra.warning else extra.success
                    )
                    Pill(
                        when {
                            !creds.hasLogin -> "Codes · setup needed"
                            creds.loginExpired -> "Codes · expired"
                            else -> "Codes · ready"
                        },
                        if (!creds.hasLogin || creds.loginExpired) extra.warning else extra.success
                    )
                }
            }

            if (!creds.hasHoyolab || expired || !creds.hasLogin) {
                VSpace(16)
                Button(
                    onClick = { onLogin(if (!creds.hasHoyolab || creds.hoyolabExpired) 1 else 2) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        when {
                            !creds.hasHoyolab -> "Connect HoYoLAB"
                            expired -> "Log in again"
                            else -> "Finish setup"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CardHeader(title: String, subtitle: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = tint)
        }
        HSpace(12)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Run on the left, Stop on the right (only active while something is running). */
@Composable
private fun RunButton(label: String, running: Boolean, enabled: Boolean, onClick: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                HSpace(10)
                Text("Running…", fontWeight = FontWeight.SemiBold)
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                HSpace(8)
                Text(label, fontWeight = FontWeight.SemiBold)
            }
        }
        OutlinedButton(
            onClick = onStop,
            enabled = running,
            modifier = Modifier.height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Box(Modifier.size(12.dp).background(LocalContentColor.current, RoundedCornerShape(2.dp)))
            HSpace(8)
            Text("Stop", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun statusStyle(st: CheckinStatus?, enabled: Boolean, waiting: Boolean): Pair<String, Color> {
    val cs = MaterialTheme.colorScheme
    val extra = LocalExtra.current
    return when {
        !enabled -> "Off" to cs.onSurfaceVariant
        st == null -> (if (waiting) "Waiting" else "Pending") to cs.onSurfaceVariant
        st == CheckinStatus.SUCCESS -> "Claimed" to extra.success
        st == CheckinStatus.ALREADY -> "Done today" to cs.primary
        st == CheckinStatus.CAPTCHA -> "Captcha" to extra.warning
        st == CheckinStatus.TOKEN_EXPIRED -> "Login needed" to extra.warning
        else -> "Failed" to cs.error
    }
}

@Composable
private fun CheckinCard(
    settings: Settings,
    creds: Credentials,
    report: RunReport?,
    active: Task?,
    live: Live,
    onRun: () -> Unit,
    onStop: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val fresh = report?.takeIf { isToday(it.timeMillis) }
    val running = active == Task.CHECKIN

    AppCard {
        CardHeader("Daily check-in", "Automatic · ${Time.checkinLocalLabel()}", cs.primary)
        VSpace(10)
        Game.entries.forEach { g ->
            val enabled = settings.checkin && g.id in settings.enabledGames
            val name = if (running) live.statuses[g.id] else fresh?.checkins?.get(g.id)
            val st = name?.let { runCatching { CheckinStatus.valueOf(it) }.getOrNull() }
            val current = running && live.game == g.id
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(Color(g.color))
                HSpace(12)
                Text(g.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (current) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    val (label, color) = statusStyle(st, enabled, waiting = running && enabled)
                    Pill(label, color)
                }
            }
        }
        VSpace(10)
        val todo = Game.entries.filter { settings.checkin && it.id in settings.enabledGames }
        val allDone = todo.isNotEmpty() &&
            todo.all { g ->
                val st = fresh?.checkins?.get(g.id)
                st == CheckinStatus.SUCCESS.name || st == CheckinStatus.ALREADY.name
            }
        RunButton(
            if (allDone) "All done for today" else "Check in now",
            running,
            active == null && creds.hasHoyolab && !allDone,
            onRun,
            onStop
        )
    }
}

@Composable
private fun CodesCard(
    settings: Settings,
    creds: Credentials,
    report: RunReport?,
    lastCheck: Long,
    active: Task?,
    live: Live,
    onRun: () -> Unit,
    onStop: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val extra = LocalExtra.current
    val running = active == Task.REDEEM

    AppCard {
        CardHeader("Gift codes", "Checked hourly", cs.secondary)
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${if (running) live.claimed else report?.claimed ?: 0}", "Redeemed", extra.success, Modifier.weight(1f))
            StatTile("${if (running) live.skipped else report?.skipped ?: 0}", "Skipped", cs.primary, Modifier.weight(1f))
            StatTile("${if (running) live.failed else report?.failed ?: 0}", "Failed", cs.error, Modifier.weight(1f))
        }
        VSpace(12)
        if (running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                HSpace(10)
                Text(
                    live.detail.ifEmpty { "Working…" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            val claimed = report?.entries?.filter { it.outcome == "CLAIMED" }.orEmpty()
            if (claimed.isNotEmpty()) {
                claimed.take(8).forEach { e ->
                    val g = Game.entries.firstOrNull { it.id == e.game }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Dot(Color(g?.color ?: 0xFF8AB4FF), size = 8)
                        HSpace(12)
                        Column {
                            Text(e.code, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                            if (e.rewards.isNotEmpty()) {
                                Text(e.rewards, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else {
                Text(
                    if (report == null) "Nothing redeemed yet." else "No new codes in the last pass.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
        VSpace(12)
        RunButton("Check codes now", running, active == null && creds.hasLogin && settings.autoRedeem, onRun, onStop)
        VSpace(8)
        Text(
            if (lastCheck > 0) {
                "Last checked " + DateUtils.getRelativeTimeSpanString(lastCheck, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            } else {
                "Not checked yet"
            },
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant
        )
    }
}

@Composable
private fun WorkshopCard(
    settings: Settings,
    creds: Credentials,
    info: WorkshopInfo?,
    active: Task?,
    status: String,
    onRun: () -> Unit,
    onStop: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val extra = LocalExtra.current
    val running = active == Task.WORKSHOP
    val daysLeft = info?.let { ((it.endTime * 1000 - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0) }

    AppCard {
        CardHeader("Machine Workshop", "Genshin web event", cs.tertiary)
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(info?.points?.toString() ?: "—", "Points", extra.success, Modifier.weight(1f))
            StatTile(daysLeft?.let { "${it}d" } ?: "—", "Event left", cs.primary, Modifier.weight(1f))
        }
        if (info != null && (info.lottery || info.exchange)) {
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info.lottery) Pill("Prize draw open", cs.secondary)
                if (info.exchange) Pill("Exchange open", cs.secondary)
            }
        }
        if (settings.autoExchange) {
            VSpace(8)
            Pill("Auto exchange on", extra.success)
        }
        if (running) {
            VSpace(12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                HSpace(10)
                Text(
                    status.ifEmpty { "Working…" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        VSpace(12)
        RunButton("Collect now", running, active == null && creds.hasHoyolab && settings.workshop, onRun, onStop)
    }
}

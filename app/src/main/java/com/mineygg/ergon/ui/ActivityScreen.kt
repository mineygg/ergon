package com.mineygg.ergon.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mineygg.ergon.MainViewModel
import com.mineygg.ergon.core.Game
import com.mineygg.ergon.core.Task
import com.mineygg.ergon.data.ItemState
import com.mineygg.ergon.data.RecordItem
import com.mineygg.ergon.data.RunRecord

@Composable
fun ActivityScreen(vm: MainViewModel, pad: PaddingValues) {
    val history by vm.history.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    var showTech by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = pad.calculateTopPadding() + 12.dp,
            bottom = pad.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Activity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "What the app did for you",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        active?.let { task -> item { LiveCard(task, status) } }

        if (history.isEmpty() && active == null) {
            item { EmptyState() }
        }

        items(history) { record -> RecordCard(record) }

        item { TechnicalCard(showTech, onToggle = { showTech = !showTech }, log = log) }
    }
}

@Composable
private fun LiveCard(task: Task, status: String) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            HSpace(14)
            Column(Modifier.weight(1f)) {
                Text("${task.label} is running", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    status.ifEmpty { "Working…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
        VSpace(12)
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
    }
}

@Composable
private fun EmptyState() {
    AppCard {
        Text("Nothing yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        VSpace(4)
        Text(
            "Check-ins and redeemed codes will show up here, with what was claimed and when.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecordCard(r: RunRecord) {
    val cs = MaterialTheme.colorScheme
    val extra = LocalExtra.current
    val tone = if (r.ok) extra.success else extra.warning
    val title = when (r.task) {
        "CHECKIN" -> "Daily check-in"
        "REDEEM" -> "Gift codes"
        else -> "Machine Workshop"
    }
    val shown = r.items.take(8)

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (r.ok) Icons.Default.CheckCircle else Icons.Default.Warning, tone)
            HSpace(12)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(r.summary, style = MaterialTheme.typography.bodyMedium, color = tone)
            }
            Column(horizontalAlignment = Alignment.End) {
                Pill(if (r.trigger == "scheduled") "Auto" else "Manual", cs.onSurfaceVariant)
                VSpace(4)
                Text(
                    DateUtils.getRelativeTimeSpanString(r.timeMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
        if (shown.isNotEmpty()) {
            VSpace(10)
            HorizontalDivider(color = cs.outlineVariant)
            shown.forEach { ItemRow(it) }
            if (r.items.size > shown.size) {
                Text(
                    "+ ${r.items.size - shown.size} more",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ItemRow(item: RecordItem) {
    val cs = MaterialTheme.colorScheme
    val extra = LocalExtra.current
    val game = Game.entries.firstOrNull { it.id == item.gameId }
    val (mark, color) = when (item.state) {
        ItemState.OK -> "✓" to extra.success
        ItemState.INFO -> "•" to cs.onSurfaceVariant
        ItemState.WARN -> "!" to extra.warning
        ItemState.ERROR -> "✕" to cs.error
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp)) { Dot(if (game != null) Color(game.color) else cs.outline, size = 8) }
        HSpace(12)
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontFamily = if (item.mono) FontFamily.Monospace else FontFamily.Default
            )
            if (item.detail.isNotEmpty()) {
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        HSpace(8)
        Text(mark, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TechnicalCard(expanded: Boolean, onToggle: () -> Unit, log: List<String>) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val extra = LocalExtra.current

    AppCard {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Technical details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Raw log, for troubleshooting", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Text(if (expanded) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = cs.primary)
        }
        if (expanded) {
            VSpace(8)
            if (log.isEmpty()) {
                Text("No log yet.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cs.surfaceVariant)
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        log.takeLast(120).forEach { line ->
                            val c = when {
                                "✖" in line -> cs.error
                                "⚠" in line -> extra.warning
                                "✔" in line -> extra.success
                                else -> cs.onSurface
                            }
                            Text(line, color = c, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                    }
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) { Text("Copy log") }
            }
        }
    }
}

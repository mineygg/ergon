package com.mineygg.ergon.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mineygg.ergon.MainViewModel
import com.mineygg.ergon.ShopState
import com.mineygg.ergon.core.ExchangeAward
import com.mineygg.ergon.core.WorkshopGame
import com.mineygg.ergon.data.ExchangeMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Exchange shop of one game's event (Machine Workshop or Traveling Mimo): the order here is the priority
 * auto-exchange follows, plus the mode that decides what happens when the top reward is too expensive.
 */
@Composable
fun ExchangeScreen(vm: MainViewModel, game: WorkshopGame, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val s by vm.settings.collectAsStateWithLifecycle()
    val shop by vm.shop.collectAsStateWithLifecycle()
    val prefs = s.exchangePrefs(game)

    LaunchedEffect(game) { vm.loadShop(game) }

    Surface(Modifier.fillMaxSize(), color = cs.background, contentColor = cs.onBackground) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                Column(Modifier.weight(1f)) {
                    Text("Exchange priority", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val points = (shop as? ShopState.Ready)?.points
                    Text(
                        if (points != null) "${game.game.title} · you have $points points" else "${game.game.title} · ${game.eventName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    AppCard {
                        Text("WHAT TO DO WITH MY POINTS", style = MaterialTheme.typography.labelMedium, color = cs.primary, fontWeight = FontWeight.Bold)
                        VSpace(6)
                        ModeRow(
                            "Wait for points",
                            "Keep saving until the top reward can be claimed. Lower ones are not touched.",
                            prefs.mode == ExchangeMode.WAIT
                        ) { vm.updateSettings { it.withExchange(game) { p -> p.copy(mode = ExchangeMode.WAIT) } } }
                        HorizontalDivider(color = cs.outlineVariant)
                        ModeRow(
                            "Claim what I can afford",
                            "Skip rewards that cost more than I have and claim the next affordable one in the list.",
                            prefs.mode == ExchangeMode.AFFORDABLE
                        ) { vm.updateSettings { it.withExchange(game) { p -> p.copy(mode = ExchangeMode.AFFORDABLE) } } }
                        if (game.canDraw) {
                            HorizontalDivider(color = cs.outlineVariant)
                            ModeRow(
                                "Prize draw only",
                                "Don't claim any reward. Spend the points on prize draws instead.",
                                prefs.mode == ExchangeMode.DRAW
                            ) { vm.updateSettings { it.withExchange(game) { p -> p.copy(mode = ExchangeMode.DRAW) } } }
                            HorizontalDivider(color = cs.outlineVariant)
                            DrawToggleRow(
                                "Draw when nothing is left to claim",
                                if (prefs.mode == ExchangeMode.DRAW) {
                                    "Not needed: prize draw only always draws."
                                } else {
                                    "Once no reward in the list is left to claim, spend spare points on prize draws. Not while saving for a reward."
                                },
                                prefs.drawWhenIdle,
                                enabled = prefs.mode != ExchangeMode.DRAW
                            ) { on -> vm.updateSettings { it.withExchange(game) { p -> p.copy(drawWhenIdle = on) } } }
                        }
                    }
                }

                when (val st = shop) {
                    ShopState.Loading -> item {
                        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        }
                    }

                    is ShopState.Failed -> item {
                        AppCard {
                            Text(st.message, style = MaterialTheme.typography.bodyMedium)
                            VSpace(12)
                            OutlinedButton(onClick = { vm.loadShop(game) }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                        }
                    }

                    is ShopState.Ready -> {
                        // Rewards already claimed (status 3) are never shown.
                        val open = st.awards.filter { it.status != ExchangeAward.STATUS_CLAIMED }
                        val free = open.filter { it.cost == 0 }
                        val paid = open.filter { it.cost > 0 }
                        val byId = paid.associateBy { it.id }
                        val ranked = prefs.priority.mapNotNull { byId[it] }
                        val rankedIds = ranked.map { it.id }
                        val rest = paid.filter { it.id !in rankedIds }

                        item {
                            AppCard {
                                SectionLabel("Priority order")
                                if (ranked.isEmpty()) {
                                    Text(
                                        "Nothing here yet. Add the rewards you want below, most wanted first.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = cs.onSurfaceVariant
                                    )
                                }
                                ranked.forEachIndexed { i, a ->
                                    if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                                    AwardRow(a, st.points, rank = i + 1) {
                                        MoveButton(up = true, enabled = i > 0) {
                                            vm.updateSettings { it.withExchange(game) { p -> p.copy(priority = move(p.priority, rankedIds, a.id, -1)) } }
                                        }
                                        MoveButton(up = false, enabled = i < ranked.size - 1) {
                                            vm.updateSettings { it.withExchange(game) { p -> p.copy(priority = move(p.priority, rankedIds, a.id, 1)) } }
                                        }
                                        IconButton(
                                            onClick = { vm.updateSettings { it.withExchange(game) { p -> p.copy(priority = p.priority - a.id) } } },
                                            modifier = Modifier.size(36.dp)
                                        ) { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(20.dp)) }
                                    }
                                }
                            }
                        }

                        if (rest.isNotEmpty()) {
                            item {
                                AppCard {
                                    SectionLabel("Not in the list")
                                    rest.forEachIndexed { i, a ->
                                        if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                                        AwardRow(a, st.points, rank = null) {
                                            TextButton(
                                                onClick = { vm.updateSettings { it.withExchange(game) { p -> p.copy(priority = p.priority - a.id + a.id) } } }
                                            ) { Text("Add") }
                                        }
                                    }
                                }
                            }
                        }

                        if (free.isNotEmpty()) {
                            item {
                                AppCard {
                                    SectionLabel("Free, always claimed")
                                    free.forEachIndexed { i, a ->
                                        if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                                        AwardRow(a, st.points, rank = null) {}
                                    }
                                }
                            }
                        }

                        item {
                            Text(
                                "Rewards you already own are hidden. Timed rewards show when they refresh. Their stock is first come, " +
                                    "first served, so Ergon sets an alarm for the refresh and claims the moment they are back. " +
                                    "Every claim is sent at least 5 seconds apart, across all games.",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Moves [id] one place up (-1) or down (+1) among the rewards that are currently visible, keeping hidden ids in place. */
private fun move(order: List<Int>, visible: List<Int>, id: Int, delta: Int): List<Int> {
    val other = visible.getOrNull(visible.indexOf(id) + delta) ?: return order
    val next = order.toMutableList()
    val a = next.indexOf(id)
    val b = next.indexOf(other)
    if (a < 0 || b < 0) return order
    next[a] = other
    next[b] = id
    return next
}

/** Up/down button with a thin chevron (drawn, so it needs no icon library). */
@Composable
private fun MoveButton(up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.3f)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Canvas(Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val y1 = if (up) h * 0.65f else h * 0.35f
            val y2 = if (up) h * 0.35f else h * 0.65f
            val path = Path().apply {
                moveTo(w * 0.15f, y1)
                lineTo(w * 0.5f, y2)
                lineTo(w * 0.85f, y1)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
    VSpace(6)
}

@Composable
private fun ModeRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        HSpace(6)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DrawToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dim = if (enabled) 1f else 0.4f
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface.copy(alpha = dim))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant.copy(alpha = dim))
        }
        HSpace(12)
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun AwardRow(a: ExchangeAward, points: Int, rank: Int?, actions: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val extra = LocalExtra.current
    val note = availability(a)
    val cost = if (a.cost == 0) "Free" else "${a.cost} points"
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (rank != null) {
            Text("$rank", style = MaterialTheme.typography.titleMedium, color = cs.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                note?.let { "$cost · $it" } ?: cost,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    note != null -> extra.warning
                    a.cost > points -> cs.onSurfaceVariant
                    else -> extra.success
                }
            )
        }
        actions()
    }
}

/** Why a reward can't be claimed right now, or null if it can. */
private fun availability(a: ExchangeAward): String? = when {
    a.timed -> "Refreshes " + refreshLabel(a.nextRefresh)
    a.status == ExchangeAward.STATUS_SOLD_OUT -> "Out of stock"
    a.status != ExchangeAward.STATUS_AVAILABLE -> "Not available yet"
    else -> null
}

private fun refreshLabel(t: Long): String {
    val at = if (t > 100_000_000_000L) Instant.ofEpochMilli(t) else Instant.ofEpochSecond(t)
    return at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
}

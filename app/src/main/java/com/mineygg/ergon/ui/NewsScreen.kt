package com.mineygg.ergon.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mineygg.ergon.MainViewModel
import com.mineygg.ergon.core.Game
import com.mineygg.ergon.data.NewsItem

/** Important HoYoLAB events (giveaways, web events, prizes) of the games that have News turned on. */
@Composable
fun NewsScreen(vm: MainViewModel, pad: PaddingValues) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val all by vm.news.collectAsStateWithLifecycle()
    val busy by vm.newsBusy.collectAsStateWithLifecycle()
    val status by vm.newsStatus.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }

    val games = Game.entries.filter { it.id in settings.newsGames }
    // A game that was switched off can't stay selected.
    val active = filter?.takeIf { it in settings.newsGames }
    val posts = all.filter { it.game in settings.newsGames && (active == null || it.game == active) }

    if (showInfo) NewsInfoDialog { showInfo = false }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("News", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Important HoYoLAB events for your games",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showInfo = true }) {
                    Icon(Icons.Default.Info, contentDescription = "How News works", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.checkNews() }, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Check now")
                }
            }
        }

        if (games.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = active == null, onClick = { filter = null }, label = { Text("All") }) }
                    items(games, key = { it.id }) { g ->
                        FilterChip(selected = active == g.id, onClick = { filter = g.id }, label = { Text(g.title) })
                    }
                }
            }
        }

        if (status.isNotEmpty()) {
            item {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        items(posts, key = { it.id }) { post ->
            NewsCard(post) { url -> runCatching { uri.openUri(url) } }
        }
    }
}

@Composable
private fun NewsInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("How News works") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoPart(
                    "What it does",
                    "Every 6 hours Ergon reads the newest 15 events of each game that has News turned on, from HoYoLAB's " +
                        "public event list (no sign-in needed), and keeps the important ones."
                )
                InfoPart(
                    "What counts as important",
                    "Events that give a premium currency or pass (Primogems, Genesis Crystals, Fates, Welkin Moon, " +
                        "Stellar Jade, Oneiric Shards, Special Passes, Polychrome, Monochrome, Master Tapes, S-Chips), " +
                        "web events, raffles, figures, and hardware prizes like phones, laptops or a PlayStation. Top-up " +
                        "and share events, creator contests, Wonderland events, Travel Mimo, birthdays and fan art are left out."
                )
                InfoPart(
                    "How long it stays",
                    "An event stays here while it is running and for 30 days after it ended, up to 100 in total. It also " +
                        "stays when it drops out of HoYoLAB's newest 15."
                )
                InfoPart(
                    "Notifications",
                    "A new event gives you a notification, and tapping it opens the article on HoYoLAB. The first check of " +
                        "a game only fills this list, without notifications."
                )
                InfoPart(
                    "When to turn it on",
                    "Switch on HoYoLAB news in a game's Configure screen when you want to hear about its events and " +
                        "giveaways and don't check HoYoLAB every day. Leave it off for games you don't play. With no game " +
                        "switched on, Ergon doesn't look at all."
                )
            }
        }
    )
}

@Composable
private fun InfoPart(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NewsCard(n: NewsItem, open: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val game = Game.entries.firstOrNull { it.id == n.game }
    val now = System.currentTimeMillis()
    val live = n.isLive(now / 1000)
    val state = when {
        n.end <= 0 -> ""
        live -> "Ends " + DateUtils.getRelativeTimeSpanString(n.end * 1000, now, DateUtils.MINUTE_IN_MILLIS)
        else -> "Ended " + DateUtils.getRelativeTimeSpanString(n.end * 1000, now, DateUtils.MINUTE_IN_MILLIS)
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (game != null) Pill(game.title, Color(game.color))
            Spacer(Modifier.weight(1f))
            if (state.isNotEmpty()) {
                Text(
                    state,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (live) cs.primary else cs.onSurfaceVariant
                )
            }
        }
        VSpace(8)
        Text(
            n.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (n.text.isNotBlank()) {
            VSpace(4)
            Text(
                n.text,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (n.url.isNotEmpty()) {
            VSpace(10)
            OutlinedButton(
                onClick = { open(n.url) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Open article") }
        }
    }
}

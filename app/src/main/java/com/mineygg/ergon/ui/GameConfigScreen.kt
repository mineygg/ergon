package com.mineygg.ergon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mineygg.ergon.MainViewModel
import com.mineygg.ergon.core.Game
import com.mineygg.ergon.core.WorkshopGame
import com.mineygg.ergon.data.ExchangeMode
import com.mineygg.ergon.data.ExchangePrefs

/**
 * What Ergon does for one game: daily check-in, gift codes and the web event (Machine Workshop for Genshin,
 * Traveling Mimo for Star Rail and Zenless), including that event's auto exchange. Only the tasks the game
 * supports are shown.
 */
@Composable
fun GameConfigScreen(vm: MainViewModel, game: Game, onExchange: (WorkshopGame) -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val s by vm.settings.collectAsStateWithLifecycle()
    val wg = WorkshopGame.entries.firstOrNull { it.game == game }

    Surface(Modifier.fillMaxSize(), color = cs.background, contentColor = cs.onBackground) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                Column(Modifier.weight(1f)) {
                    Text(game.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Choose what Ergon does for this game", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    AppCard {
                        ToggleRow("Daily check-in", "Claim the HoYoLAB daily reward", game.id in s.checkinGames) { on ->
                            vm.updateSettings { it.copy(checkinGames = it.checkinGames.toggled(game.id, on)) }
                        }
                        if (game.redeem != null) {
                            HorizontalDivider(color = cs.outlineVariant)
                            ToggleRow("Gift codes", "Redeem new codes automatically", game.id in s.redeemGames) { on ->
                                vm.updateSettings { it.copy(redeemGames = it.redeemGames.toggled(game.id, on)) }
                            }
                        }
                        if (wg != null) {
                            HorizontalDivider(color = cs.outlineVariant)
                            ToggleRow(wg.eventName, "Web event: collect points and rewards", wg.id in s.workshopGames) { on ->
                                vm.updateSettings { it.copy(workshopGames = it.workshopGames.toggled(wg.id, on)) }
                            }
                        }
                    }
                }

                if (wg != null && wg.id in s.workshopGames) {
                    item {
                        val p = s.exchangePrefs(wg)
                        AppCard {
                            ToggleRow("Auto exchange", "Spend ${wg.eventName} points on rewards", p.auto) { on ->
                                vm.updateSettings { it.withExchange(wg) { x -> x.copy(auto = on) } }
                            }
                            if (p.auto) {
                                VSpace(4)
                                OutlinedButton(
                                    onClick = { onExchange(wg) },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp)
                                ) { Text("Exchange priority") }
                                VSpace(6)
                                Text(exchangeSummary(p), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }

                if (game.redeem == null) {
                    item {
                        Text(
                            "Only the daily check-in is available for this game.",
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

private fun Set<String>.toggled(id: String, on: Boolean): Set<String> = if (on) this + id else this - id

private fun exchangeSummary(p: ExchangePrefs): String {
    if (p.mode == ExchangeMode.DRAW) return "Prize draw only, no rewards are claimed"
    val count = p.priority.size
    val list = if (count == 0) "No priority set, only free rewards are claimed" else "$count in the priority list"
    val draw = if (p.drawWhenIdle) " · draws when nothing is left to claim" else ""
    return list + " · " + (if (p.mode == ExchangeMode.WAIT) "waits for points" else "claims what it can afford") + draw
}

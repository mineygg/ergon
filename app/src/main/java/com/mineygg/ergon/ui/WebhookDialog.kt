package com.mineygg.ergon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mineygg.ergon.core.Game

/** The games whose codes can be posted to Discord: the ones Ergon redeems codes for. */
private val WEBHOOK_GAMES = listOf(Game.GENSHIN, Game.HSR, Game.ZZZ)

/** What a saved webhook looks like. The URL itself is a secret and is never shown again. */
private const val MASK = "••••••••••••••••"

/**
 * One box per game for a Discord webhook URL. A saved URL is never shown again (it is a secret): the box shows dots,
 * "Replace" opens an empty box for a new URL, and the bin removes it.
 */
@Composable
fun WebhookDialog(
    saved: Set<String>,
    onSave: (Map<String, String>, (Map<String, String>) -> Unit) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val texts = remember { mutableStateMapOf<String, String>() }
    // Games whose saved webhook is being replaced: their box is open for a new URL.
    val replacing = remember { mutableStateMapOf<String, Boolean>() }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Discord webhook") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Ergon posts new gift codes to a Discord channel, in batches, and slows down when Discord asks it to. " +
                        "Only codes Ergon hasn't seen before are posted, and saving sends a short test message.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "To get a URL: in Discord open Server Settings, Integrations, Webhooks, New Webhook, pick the channel and " +
                        "copy the webhook URL. Keep it private: anyone who has it can post in that channel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                for (game in WEBHOOK_GAMES) {
                    if (game.id in saved && replacing[game.id] != true) {
                        OutlinedTextField(
                            value = MASK,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            enabled = !busy,
                            label = { Text(game.title) },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { replacing[game.id] = true }) { Text("Replace") }
                                    IconButton(onClick = { onRemove(game.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove the ${game.title} webhook")
                                    }
                                }
                            },
                            supportingText = { Text("Saved") }
                        )
                    } else {
                        OutlinedTextField(
                            value = texts[game.id].orEmpty(),
                            onValueChange = {
                                texts[game.id] = it
                                errors = errors - game.id
                            },
                            label = { Text(game.title) },
                            placeholder = { Text("https://discord.com/api/webhooks/…") },
                            singleLine = true,
                            enabled = !busy,
                            isError = errors[game.id] != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            trailingIcon = {
                                if (game.id in saved) {
                                    TextButton(
                                        onClick = {
                                            replacing[game.id] = false
                                            texts[game.id] = ""
                                            errors = errors - game.id
                                        }
                                    ) { Text("Cancel") }
                                }
                            },
                            supportingText = {
                                Text(errors[game.id] ?: if (game.id in saved) "Paste the new URL" else "Not set")
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && texts.values.any { it.isNotBlank() },
                onClick = {
                    busy = true
                    onSave(texts.toMap()) { problems ->
                        busy = false
                        errors = problems
                        if (problems.isEmpty()) onDismiss()
                    }
                }
            ) { Text(if (busy) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") } }
    )
}

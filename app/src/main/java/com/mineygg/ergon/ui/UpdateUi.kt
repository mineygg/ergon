package com.mineygg.ergon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mineygg.ergon.core.UpdateState

/** Status line and button for the Settings "Updates" card. */
@Composable
fun UpdateControls(state: UpdateState, onCheck: () -> Unit, onUpdate: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val line = when (state) {
        is UpdateState.UpToDate -> "Up to date"
        is UpdateState.Available -> "Version ${state.info.version} available"
        is UpdateState.NeedsPermission -> "Allow installs from Ergon, then tap Update"
        is UpdateState.Downloading -> "Downloading ${state.percent}%"
        is UpdateState.Error -> state.message
        else -> null
    }
    if (line != null) {
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state is UpdateState.Error) cs.error else cs.onSurfaceVariant
        )
        VSpace(8)
    }
    if (state is UpdateState.Downloading) {
        LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
        VSpace(8)
    }
    val canUpdate = state is UpdateState.Available || state is UpdateState.NeedsPermission
    if (canUpdate) {
        Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
            Text("Update", fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            onClick = onCheck,
            enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                when (state) {
                    is UpdateState.Checking -> "Checking…"
                    is UpdateState.Downloading -> "Downloading…"
                    else -> "Check for updates"
                }
            )
        }
    }
}

/** Slim banner on the Home screen, shown only when an update is waiting. */
@Composable
fun UpdateBanner(state: UpdateState, onUpdate: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val version = when (state) {
        is UpdateState.Available -> state.info.version
        is UpdateState.NeedsPermission -> state.info.version
        is UpdateState.Downloading -> state.info.version
        else -> return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cs.secondaryContainer)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text("Update $version", fontWeight = FontWeight.SemiBold, color = cs.onSecondaryContainer)
            if (state is UpdateState.Downloading) {
                Text("Downloading ${state.percent}%", style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
            }
        }
        Button(onClick = onUpdate, enabled = state !is UpdateState.Downloading, shape = RoundedCornerShape(14.dp)) {
            Text("Update")
        }
    }
}

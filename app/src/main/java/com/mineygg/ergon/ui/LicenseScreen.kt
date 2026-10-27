package com.mineygg.ergon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineygg.ergon.AppInfo
import com.mineygg.ergon.R

/** Shows the GPL text and third-party notices that are bundled inside the app (res/raw). */
@Composable
fun LicenseScreen(onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val texts = remember {
        fun read(id: Int) = ctx.resources.openRawResource(id).bufferedReader().use { it.readText() }
        listOf(read(R.raw.license_gpl3), read(R.raw.notices))
    }
    val paragraphs = remember(tab) { texts[tab].split("\n\n").map { it.trim() }.filter { it.isNotEmpty() } }

    Surface(Modifier.fillMaxSize(), color = cs.background, contentColor = cs.onBackground) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                Column(Modifier.weight(1f)) {
                    Text("License", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(AppInfo.LICENSE_NAME, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("GNU GPL v3") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Notices") })
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(paragraphs) { p ->
                    Text(p, style = MaterialTheme.typography.bodySmall, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurface)
                }
            }
        }
    }
}

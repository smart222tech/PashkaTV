package de.pashkatv.player

import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class TvChannel(val name: String, val streamUrl: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("pashkatv", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PashkaScreen(
                    savedUrl = prefs.getString("playlist_url", "") ?: "",
                    onSaveUrl = { prefs.edit().putString("playlist_url", it).apply() }
                )
            }
        }
    }
}

@Composable
private fun PashkaScreen(savedUrl: String, onSaveUrl: (String) -> Unit) {
    val context = LocalContext.current
    var playlistUrl by remember { mutableStateOf(savedUrl) }
    var channels by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var selected by remember { mutableStateOf<TvChannel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(savedUrl.isBlank()) }

    val player = remember {
        ExoPlayer.Builder(context).build()
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    LaunchedEffect(selected) {
        selected?.let {
            player.setMediaItem(MediaItem.fromUri(it.streamUrl))
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun loadPlaylist() {
        if (playlistUrl.isBlank()) {
            error = "Bitte eine M3U-Playlist-URL eingeben."
            return
        }
        loading = true
        error = null
        onSaveUrl(playlistUrl.trim())
        (context as? ComponentActivity)?.lifecycleScope?.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val connection = URL(playlistUrl.trim()).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.setRequestProperty("User-Agent", "PashkaTV/1.0")
                    try {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        connection.disconnect()
                    }
                }
                channels = parseM3u(result)
                if (channels.isEmpty()) error = "Keine Sender gefunden. Bitte M3U-Link prüfen."
                else selected = channels.first()
            } catch (e: Exception) {
                error = "Playlist konnte nicht geladen werden: ${e.localizedMessage ?: "Unbekannter Fehler"}"
            } finally {
                loading = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF10141D)) {
        Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(0.36f).fillMaxHeight()) {
                Text("PashkaTV", fontSize = 30.sp, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { showUrlDialog = true }) { Text("Playlist") }
                    Spacer(Modifier.width(8.dp))
                    Text("${channels.size} Sender", color = Color.LightGray)
                }
                Spacer(Modifier.height(12.dp))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    itemsIndexed(channels) { _, channel ->
                        val active = selected == channel
                        Text(
                            text = channel.name,
                            color = if (active) Color.White else Color(0xFFD5D9E2),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (active) Color(0xFF2457A6) else Color(0xFF1B2230),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selected = channel }
                                .focusable()
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            fontSize = 16.sp,
                            maxLines = 2
                        )
                    }
                }
            }
            Column(Modifier.weight(0.64f).fillMaxHeight()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .background(Color.Black, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = this@PashkaScreen.player
                                useController = true
                                layoutParams = FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (selected == null) Text("Playlist laden und Sender auswählen", color = Color.White)
                }
                Spacer(Modifier.height(10.dp))
                Text(selected?.name ?: "Kein Sender ausgewählt", color = Color.White, fontSize = 18.sp)
            }
        }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("M3U-Playlist hinzufügen") },
            text = {
                OutlinedTextField(
                    value = playlistUrl,
                    onValueChange = { playlistUrl = it },
                    label = { Text("Playlist-URL") },
                    placeholder = { Text("https://…/playlist.m3u") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { showUrlDialog = false; loadPlaylist() }) { Text("Laden") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("Abbrechen") }
            }
        )
    }
}

private fun parseM3u(content: String): List<TvChannel> {
    val result = mutableListOf<TvChannel>()
    var pendingName: String? = null
    content.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith("#EXTINF", ignoreCase = true) -> {
                val comma = line.lastIndexOf(',')
                pendingName = if (comma >= 0 && comma < line.lastIndex) {
                    line.substring(comma + 1).trim().ifBlank { "Unbenannter Sender" }
                } else {
                    Regex("tvg-name=\"([^\"]+)\"").find(line)?.groupValues?.getOrNull(1)
                        ?: "Unbenannter Sender"
                }
            }
            line.isNotBlank() && !line.startsWith("#") && pendingName != null -> {
                if (line.startsWith("http://", true) || line.startsWith("https://", true)) {
                    result += TvChannel(pendingName!!, line)
                }
                pendingName = null
            }
        }
    }
    return result
}

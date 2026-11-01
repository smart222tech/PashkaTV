package de.pashkatv.player

import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

private val Background = Color(0xFF080F1D)
private val Panel = Color(0xFF111D30)
private val Accent = Color(0xFF079CF2)
private val TextMain = Color(0xFFF4F7FC)

data class TvChannel(val name: String, val streamUrl: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        val prefs = getSharedPreferences("pashkatv", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Panel)) {
                PashkaScreen(
                    savedUrl = prefs.getString("playlist_url", "") ?: "",
                    autoFullscreen = prefs.getBoolean("auto_fullscreen", true),
                    okOpensList = prefs.getBoolean("ok_opens_list", true),
                    leftRightSwitch = prefs.getBoolean("left_right_switch", true),
                    backClosesOverlay = prefs.getBoolean("back_closes_overlay", true),
                    onSaveUrl = { prefs.edit().putString("playlist_url", it).apply() },
                    onSaveSetting = { key, value -> prefs.edit().putBoolean(key, value).apply() }
                )
            }
        }
    }
}

@Composable
private fun PashkaScreen(
    savedUrl: String,
    autoFullscreen: Boolean,
    okOpensList: Boolean,
    leftRightSwitch: Boolean,
    backClosesOverlay: Boolean,
    onSaveUrl: (String) -> Unit,
    onSaveSetting: (String, Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val scope = rememberCoroutineScope()
    val rootFocusRequester = remember { FocusRequester() }
    var playlistUrl by remember { mutableStateOf(savedUrl) }
    var channels by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var selectedIndex by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(savedUrl.isBlank()) }
    var showChannelList by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(true) }
    var currentAutoFullscreen by remember { mutableStateOf(autoFullscreen) }
    var currentOkOpensList by remember { mutableStateOf(okOpensList) }
    var currentLeftRightSwitch by remember { mutableStateOf(leftRightSwitch) }
    var currentBackClosesOverlay by remember { mutableStateOf(backClosesOverlay) }

    val selected = channels.getOrNull(selectedIndex)
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    LaunchedEffect(selected?.streamUrl) {
        selected?.let { channel ->
            exoPlayer.setMediaItem(MediaItem.fromUri(channel.streamUrl))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    fun selectChannel(index: Int) {
        if (channels.isNotEmpty()) {
            rootFocusRequester.requestFocus()
            selectedIndex = (index + channels.size) % channels.size
            showChannelList = !currentAutoFullscreen
            showSettings = false
            showInfo = true
        }
    }

    fun loadPlaylist() {
        val url = playlistUrl.trim()
        if (url.isBlank()) {
            error = "Bitte zuerst eine M3U-Playlist-URL eingeben."
            showUrlDialog = true
            return
        }
        loading = true
        error = null
        onSaveUrl(url)
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 25000
                    connection.setRequestProperty("User-Agent", "PashkaTV/2.0")
                    try {
                        if (connection.responseCode !in 200..299) {
                            throw IllegalStateException("HTTP ${connection.responseCode}")
                        }
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } finally {
                        connection.disconnect()
                    }
                }
                val parsed = parseM3u(text)
                channels = parsed
                if (parsed.isEmpty()) {
                    selectedIndex = -1
                    error = "Keine Sender gefunden. Bitte M3U-Link prüfen."
                } else {
                    selectChannel(0)
                }
            } catch (e: Exception) {
                error = "Playlist konnte nicht geladen werden: ${e.localizedMessage ?: "Unbekannter Fehler"}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        rootFocusRequester.requestFocus()
        if (savedUrl.isNotBlank()) loadPlaylist()
    }

    BackHandler(enabled = showSettings || showChannelList || showUrlDialog || selected != null) {
        when {
            showUrlDialog -> showUrlDialog = false
            showSettings -> showSettings = false
            showChannelList -> showChannelList = false
            currentBackClosesOverlay -> showChannelList = true
            else -> showChannelList = true
        }
    }

    val remoteModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.nativeKeyEvent.keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!showSettings && !showUrlDialog && currentOkOpensList && selected != null) {
                    showChannelList = !showChannelList
                    true
                } else false
            }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!showSettings && !showUrlDialog && !showChannelList && currentLeftRightSwitch && channels.isNotEmpty()) {
                    selectChannel(selectedIndex - 1); true
                } else false
            }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!showSettings && !showUrlDialog && !showChannelList && currentLeftRightSwitch && channels.isNotEmpty()) {
                    selectChannel(selectedIndex + 1); true
                } else false
            }
            AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_SETTINGS -> {
                showSettings = true; showChannelList = false; true
            }
            AndroidKeyEvent.KEYCODE_BACK -> {
                if (showSettings || showChannelList || showUrlDialog) false
                else { showChannelList = true; true }
            }
            else -> false
        }
    }

    Box(Modifier.fillMaxSize().background(Background).focusRequester(rootFocusRequester).then(remoteModifier).focusable()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize()
        )

        if (selected == null) {
            Column(
                Modifier.align(Alignment.Center).background(Panel.copy(alpha = 0.96f), RoundedCornerShape(18.dp)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("PashkaTV", color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(if (loading) "Playlist wird geladen …" else "Playlist hinzufügen, um fernzusehen", color = Color.LightGray)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { showUrlDialog = true }) { Text("Playlist hinzufügen") }
                error?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 8.dp)) }
            }
        }

        if (selected != null && showInfo && !showChannelList && !showSettings) {
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Background.copy(alpha = 0.82f)).padding(horizontal = 28.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(selected.name, color = TextMain, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("← / → Sender wechseln     OK Senderliste     MENU Einstellungen", color = Color.LightGray, fontSize = 13.sp)
                }
                TextButton(onClick = { showChannelList = true }) { Text("Sender", color = TextMain) }
                TextButton(onClick = { showSettings = true }) { Text("⚙ Einstellungen", color = TextMain) }
            }
        }

        if (showChannelList) {
            Row(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f))) {
                Column(
                    Modifier.fillMaxHeight().width(340.dp).background(Panel.copy(alpha = 0.98f)).padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PashkaTV", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showSettings = true; showChannelList = false }) { Text("⚙") }
                    }
                    Text("${channels.size} Sender", color = Color.LightGray, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        itemsIndexed(channels) { index, channel ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (index == selectedIndex) Accent.copy(alpha = 0.85f) else Color(0xFF1B2A40), RoundedCornerShape(8.dp))
                                    .clickable { selectChannel(index) }
                                    .focusable()
                                    .padding(horizontal = 12.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(channel.name, color = TextMain, maxLines = 2, modifier = Modifier.weight(1f))
                                if (index == selectedIndex) Text("▶", color = TextMain)
                            }
                        }
                    }
                    error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showUrlDialog = true }) { Text("Playlist ändern") }
                    TextButton(onClick = { showChannelList = false }) { Text("Zurück zum Vollbild") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showChannelList = false; showInfo = false }, modifier = Modifier.padding(12.dp)) {
                    Text("Schließen", color = TextMain)
                }
            }
        }

        if (showSettings) {
            SettingsPanel(
                playlistUrl = playlistUrl,
                onPlaylist = { showSettings = false; showUrlDialog = true },
                autoFullscreen = currentAutoFullscreen,
                onAutoFullscreen = { currentAutoFullscreen = it; onSaveSetting("auto_fullscreen", it) },
                okOpensList = currentOkOpensList,
                onOkOpensList = { currentOkOpensList = it; onSaveSetting("ok_opens_list", it) },
                leftRightSwitch = currentLeftRightSwitch,
                onLeftRightSwitch = { currentLeftRightSwitch = it; onSaveSetting("left_right_switch", it) },
                backClosesOverlay = currentBackClosesOverlay,
                onBackClosesOverlay = { currentBackClosesOverlay = it; onSaveSetting("back_closes_overlay", it) },
                onReload = { showSettings = false; loadPlaylist() },
                onClose = { showSettings = false; rootFocusRequester.requestFocus() }
            )
        }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("M3U / M3U8 Playlist") },
            text = {
                Column {
                    OutlinedTextField(
                        value = playlistUrl,
                        onValueChange = { playlistUrl = it },
                        label = { Text("Playlist-URL") },
                        placeholder = { Text("https://…/playlist.m3u") },
                        singleLine = true
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUrlDialog = false; loadPlaylist() }) { Text("Speichern & laden") }
            },
            dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun SettingsPanel(
    playlistUrl: String,
    onPlaylist: () -> Unit,
    autoFullscreen: Boolean,
    onAutoFullscreen: (Boolean) -> Unit,
    okOpensList: Boolean,
    onOkOpensList: (Boolean) -> Unit,
    leftRightSwitch: Boolean,
    onLeftRightSwitch: (Boolean) -> Unit,
    backClosesOverlay: Boolean,
    onBackClosesOverlay: (Boolean) -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Background.copy(alpha = 0.98f)).padding(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚙  Einstellungen", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Fertig") }
        }
        Text("PashkaTV · Android TV", color = Color.LightGray)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(230.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Allgemein", "Playlist", "Player", "Fernbedienung", "Über").forEachIndexed { index, label ->
                    Surface(color = if (index == 3) Accent.copy(alpha = 0.25f) else Panel, shape = RoundedCornerShape(9.dp)) {
                        Text(label, color = TextMain, modifier = Modifier.fillMaxWidth().padding(14.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text("Playlist", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (playlistUrl.isBlank()) "Keine Playlist gespeichert" else playlistUrl, color = Color.LightGray, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onPlaylist) { Text("Playlist bearbeiten") }
                    OutlinedButton(onClick = onReload) { Text("Playlist neu laden") }
                }
                Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 12.dp))
                Text("Player", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                SettingSwitch("Nach Senderauswahl Vollbild", autoFullscreen, onAutoFullscreen)
                Divider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 12.dp))
                Text("Fernbedienung", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                SettingSwitch("OK-Taste öffnet / schließt Senderliste", okOpensList, onOkOpensList)
                SettingSwitch("Links / Rechts wechselt Sender", leftRightSwitch, onLeftRightSwitch)
                SettingSwitch("Zurück blendet Menüs / Senderliste aus", backClosesOverlay, onBackClosesOverlay)
                Spacer(Modifier.height(12.dp))
                Text("Tastenbelegung", color = TextMain, fontWeight = FontWeight.SemiBold)
                Text("OK / Enter: Senderliste     ← / →: Sender wechseln", color = Color.LightGray)
                Text("MENU: Einstellungen     Zurück: Overlay schließen", color = Color.LightGray)
                Spacer(Modifier.weight(1f))
                Text("PashkaTV 2.0 · IPTV-Player für eigene M3U/M3U8-Playlists", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextMain, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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

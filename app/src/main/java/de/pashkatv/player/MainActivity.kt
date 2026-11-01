package de.pashkatv.player

import android.os.Bundle
import android.view.View
import android.view.TextureView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val Background = Color(0xFF080F1D)
private val Panel = Color(0xFF111D30)
private val Accent = Color(0xFF079CF2)
private val TextMain = Color(0xFFF4F7FC)

data class TvChannel(val name: String, val streamUrl: String, val groupTitle: String = "Allgemein")

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
                    autoFullscreen = prefs.getBoolean("auto_fullscreen", false),
                    autoStartOnLaunch = prefs.getBoolean("auto_start_on_launch", false),
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
    autoStartOnLaunch: Boolean,
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
    var selectedGroup by remember { mutableStateOf("Alle") }
    var error by remember { mutableStateOf<String?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(savedUrl.isBlank()) }
    var showChannelList by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(true) }
    var currentAutoFullscreen by remember { mutableStateOf(autoFullscreen) }
    var currentAutoStartOnLaunch by remember { mutableStateOf(autoStartOnLaunch) }
    var currentOkOpensList by remember { mutableStateOf(okOpensList) }
    var currentLeftRightSwitch by remember { mutableStateOf(leftRightSwitch) }
    var currentBackClosesOverlay by remember { mutableStateOf(backClosesOverlay) }

    val selected = channels.getOrNull(selectedIndex)
    val groups = remember(channels) { listOf("Alle") + channels.map { it.groupTitle }.distinct().sorted() }
    val visibleChannels = remember(channels, selectedGroup) { if (selectedGroup == "Alle") channels else channels.filter { it.groupTitle == selectedGroup } }
    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderersFactory).build()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(exception: PlaybackException) {
                val detail = exception.cause?.localizedMessage
                    ?: exception.localizedMessage
                    ?: exception.errorCodeName
                playbackError = "Wiedergabefehler: $detail"
                Log.e("PashkaTV-Player", "Playback error: ${exception.errorCodeName}", exception)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d("PashkaTV-Player", "Playback state: $playbackState")
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(selected?.streamUrl) {
        selected?.let { channel ->
            playbackError = null
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
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
                    showChannelList = false
                    error = "Keine Sender gefunden. Bitte M3U-Link prüfen."
                } else {
                    playbackError = null
                    showSettings = false
                    showInfo = false
                    selectedGroup = "Alle"
                    if (currentAutoStartOnLaunch) selectChannel(0)
                    else {
                        selectedIndex = -1
                        showChannelList = true
                    }
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
        when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (!showSettings && !showUrlDialog && currentOkOpensList && selected != null) {
                    showChannelList = !showChannelList
                    true
                } else false
            }
            Key.DirectionLeft -> {
                if (!showSettings && !showUrlDialog && !showChannelList && currentLeftRightSwitch && channels.isNotEmpty()) {
                    selectChannel(selectedIndex - 1); true
                } else false
            }
            Key.DirectionRight -> {
                if (!showSettings && !showUrlDialog && !showChannelList && currentLeftRightSwitch && channels.isNotEmpty()) {
                    selectChannel(selectedIndex + 1); true
                } else false
            }
            Key.Menu -> {
                showSettings = true; showChannelList = false; true
            }
            Key.Escape, Key.Back -> {
                if (showSettings || showChannelList || showUrlDialog) false
                else { showChannelList = true; true }
            }
            else -> false
        }
    }

    Box(Modifier.fillMaxSize().background(Background).focusRequester(rootFocusRequester).then(remoteModifier).focusable()) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    exoPlayer.setVideoTextureView(this)
                }
            },
            update = { exoPlayer.setVideoTextureView(it) },
            modifier = Modifier.fillMaxSize()
        )

        if (playbackError != null && selected != null) {
            Column(
                Modifier.align(Alignment.Center)
                    .background(Panel.copy(alpha = 0.96f), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Sender kann nicht abgespielt werden", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(playbackError ?: "", color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 10.dp))
                Button(
                    onClick = {
                        playbackError = null
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    },
                    modifier = Modifier.padding(top = 12.dp)
                ) { Text("Erneut versuchen") }
            }
        }

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
                    Modifier.fillMaxHeight().width(560.dp).background(Panel.copy(alpha = 0.98f)).padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PashkaTV", color = TextMain, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showSettings = true; showChannelList = false }) { Text("⚙") }
                    }
                    Text("${channels.size} Sender · ${groups.size - 1} Gruppen", color = Color.LightGray, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LazyColumn(Modifier.width(180.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            itemsIndexed(groups) { _, group ->
                                val count = if (group == "Alle") channels.size else channels.count { it.groupTitle == group }
                                var groupFocused by remember(group) { mutableStateOf(false) }
                                Surface(
                                    color = when {
                                        groupFocused -> Color(0xFF15558A)
                                        selectedGroup == group -> Accent.copy(alpha = 0.55f)
                                        else -> Color(0xFF1B2A40)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                        .border(if (groupFocused) 3.dp else 1.dp, if (groupFocused) Color.White else Color(0xFF30445F), RoundedCornerShape(8.dp))
                                        .onFocusChanged { groupFocused = it.isFocused }
                                        .focusable()
                                        .clickable { selectedGroup = group }
                                ) {
                                    Column(Modifier.padding(9.dp)) {
                                        Text(group, color = TextMain, maxLines = 2, fontSize = 12.sp)
                                        Text("$count Sender", color = Color.LightGray, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            itemsIndexed(visibleChannels) { _, channel ->
                                val index = channels.indexOf(channel)
                                var channelFocused by remember(channel.streamUrl) { mutableStateOf(false) }
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(
                                            when {
                                                channelFocused -> Color(0xFF15558A)
                                                index == selectedIndex -> Accent.copy(alpha = 0.65f)
                                                else -> Color(0xFF1B2A40)
                                            },
                                            RoundedCornerShape(8.dp)
                                        )
                                        .border(if (channelFocused) 3.dp else 1.dp, if (channelFocused) Color.White else Color(0xFF30445F), RoundedCornerShape(8.dp))
                                        .onFocusChanged { channelFocused = it.isFocused }
                                        .focusable()
                                        .clickable { selectChannel(index) }
                                        .padding(horizontal = 10.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(channel.name, color = TextMain, maxLines = 2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    if (index == selectedIndex) Text("▶", color = TextMain)
                                }
                            }
                        }
                    }
                    error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { showUrlDialog = true }) { Text("Playlist ändern") }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = { showSettings = true; showChannelList = false }) { Text("Einstellungen") }
                    }
                    TextButton(onClick = { showChannelList = false }) { Text("Zurück zum Player") }
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
                autoStartOnLaunch = currentAutoStartOnLaunch,
                onAutoStartOnLaunch = { currentAutoStartOnLaunch = it; onSaveSetting("auto_start_on_launch", it) },
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
    autoStartOnLaunch: Boolean,
    onAutoStartOnLaunch: (Boolean) -> Unit,
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
                SettingSwitch("Beim App-Start ersten Sender automatisch starten", autoStartOnLaunch, onAutoStartOnLaunch)
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
    var lastExtInfLine: String = ""
    content.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line.startsWith("#EXTINF", ignoreCase = true) -> {
                lastExtInfLine = line
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
                    val group = Regex("group-title=\\\"([^\\\"]+)\\\"").find(lastExtInfLine)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { "Allgemein" } ?: "Allgemein"
                    result += TvChannel(pendingName!!, line, group)
                }
                pendingName = null
            }
        }
    }
    return result
}

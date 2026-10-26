package moe.zyc.splitvise

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val Ink = Color(0xFF000000)
private val Card = Color(0xFF2A2A2E)
private val Chip = Color(0xFF1C1C1E)
private val Muted = Color(0xFF9A9AA2)
private val Coral = Color(0xFFFF6F61)
private val CoralCore = Color(0xFF6B2A24)
private val StopFill = Color(0xFFF6D0CB)
private val StopInk = Color(0xFF6B2A2A)
private val PauseFill = Color(0xFF2A2A2E)

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var recordings: LocalRecordings
    private val recorder = Recorder()
    private var recordingOpen = false
    private var onRecordingEnded: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        recordings = LocalRecordings(File(filesDir, "recordings"))
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Ink,
                    surface = Ink,
                    onBackground = Color.White,
                    onSurface = Color.White,
                ),
            ) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = Ink) {
                    App()
                }
            }
        }
    }

    override fun onDestroy() {
        if (recordingOpen) recorder.cancel()
        super.onDestroy()
    }

    private sealed interface Phase {
        data object Home : Phase
        data class Recording(val startedAt: Instant) : Phase
    }

    @Composable
    private fun App() {
        var showSettings by remember { mutableStateOf(!prefs.configured) }
        var phase by remember { mutableStateOf<Phase>(Phase.Home) }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                beginRecording(
                    onStarted = { phase = Phase.Recording(it) },
                    onEnded = { phase = Phase.Home },
                )
            }
        }
        val onRecord = {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                beginRecording(
                    onStarted = { phase = Phase.Recording(it) },
                    onEnded = { phase = Phase.Home },
                )
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        when {
            showSettings -> SettingsScreen(
                initialUrl = prefs.serverUrl,
                initialToken = prefs.token,
                onSave = { url, token ->
                    prefs.serverUrl = url
                    prefs.token = token
                    showSettings = false
                },
                onBack = if (prefs.configured) {
                    { showSettings = false }
                } else {
                    null
                },
            )
            phase is Phase.Recording -> RecordingScreen(
                startedAt = (phase as Phase.Recording).startedAt,
                onStop = {
                    onRecordingEnded = null
                    finishAndSave()
                    phase = Phase.Home
                },
                onDiscard = {
                    onRecordingEnded = null
                    discardRecording()
                    phase = Phase.Home
                },
            )
            else -> HomeScreen(
                onOpenSettings = { showSettings = true },
                onRecord = onRecord,
            )
        }
    }

    @Composable
    private fun SettingsScreen(
        initialUrl: String,
        initialToken: String,
        onSave: (String, String) -> Unit,
        onBack: (() -> Unit)?,
    ) {
        var url by remember { mutableStateOf(initialUrl) }
        var token by remember { mutableStateOf(initialToken) }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("splitvise", style = MaterialTheme.typography.headlineMedium)
                if (onBack != null) TextButton(onClick = onBack) { Text("back") }
            }
            Text("server URL and bearer token.")
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("server URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                onClick = { onSave(url, token) },
                enabled = token.isNotBlank() && url.isNotBlank(),
            ) { Text("save") }
        }
    }

    @Composable
    private fun HomeScreen(onOpenSettings: () -> Unit, onRecord: () -> Unit) {
        var query by remember { mutableStateOf("") }
        val files = recordings.list()
        val visible = files.map { file ->
            file to Instant.ofEpochMilli(file.lastModified()).atZone(ZoneId.systemDefault())
        }.filter { (_, at) ->
            query.isBlank() ||
                titleOf(at).contains(query, ignoreCase = true) ||
                subtitleOf(at).contains(query, ignoreCase = true) ||
                monthOf(at).contains(query, ignoreCase = true)
        }
        val grouped = visible.groupBy { (_, at) -> monthOf(at) }

        var playing by remember { mutableStateOf<String?>(null) }
        var progress by remember { mutableFloatStateOf(0f) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        DisposableEffect(Unit) { onDispose { player?.release() } }
        LaunchedEffect(playing) {
            while (isActive && playing != null) {
                val p = player
                if (p != null && p.duration > 0) {
                    progress = p.currentPosition.toFloat() / p.duration.toFloat()
                }
                delay(200)
            }
        }

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                SearchRow(query, onQuery = { query = it }, onOpenSettings = onOpenSettings)
                Spacer(Modifier.height(18.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    grouped.forEach { (month, rows) ->
                        item(key = "month-$month") {
                            Text(
                                month,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(rows, key = { it.first.absolutePath }) { (file, at) ->
                            RecordingCard(
                                title = titleOf(at),
                                subtitle = subtitleOf(at),
                                duration = formatDuration(recordings.durationMs(file) ?: 0),
                                progress = if (playing == file.absolutePath) progress else 0f,
                                playing = playing == file.absolutePath,
                                onPlay = {
                                    if (playing == file.absolutePath) {
                                        player?.pause()
                                        playing = null
                                        return@RecordingCard
                                    }
                                    player?.release()
                                    val next = MediaPlayer()
                                    next.setDataSource(file.absolutePath)
                                    next.setOnCompletionListener {
                                        playing = null
                                        progress = 0f
                                    }
                                    next.prepare()
                                    next.start()
                                    player = next
                                    playing = file.absolutePath
                                },
                            )
                        }
                    }
                }
            }
            RecordButton(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                onClick = {
                    player?.release()
                    player = null
                    playing = null
                    onRecord()
                },
            )
        }
    }

    @Composable
    private fun RecordingScreen(
        startedAt: Instant,
        onStop: () -> Unit,
        onDiscard: () -> Unit,
    ) {
        var tick by remember { mutableLongStateOf(0L) }
        var paused by remember { mutableStateOf(false) }
        val samples = remember { mutableStateListOf<Float>() }
        val startedLabel = DateTimeFormatter.ofPattern("MMM d 'at' HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(startedAt)

        LaunchedEffect(paused) {
            if (paused) return@LaunchedEffect
            val origin = SystemClock.elapsedRealtime()
            val base = tick
            while (isActive && !paused && recordingOpen) {
                val elapsed = base + (SystemClock.elapsedRealtime() - origin)
                tick = elapsed.coerceAtMost(Recorder.MAX_DURATION_MS.toLong())
                samples.add(recorder.level())
                if (samples.size > 56) samples.removeAt(0)
                if (tick >= Recorder.MAX_DURATION_MS) {
                    onStop()
                    break
                }
                delay(100)
            }
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(startedLabel, color = Color.White, fontSize = 20.sp)
                Spacer(Modifier.weight(1f))
            TrashButton(onClick = onDiscard)
            }
            Waveform(
                samples = samples,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(28.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(14.dp).clip(CircleShape).background(if (paused) Muted else Coral),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    formatClock(tick),
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(28.dp))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Button(
                    onClick = {
                        paused = if (paused) {
                            recorder.resume()
                            false
                        } else {
                            recorder.pause()
                            true
                        }
                    },
                    modifier = Modifier.weight(1f).height(72.dp),
                    shape = RoundedCornerShape(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PauseFill,
                        contentColor = Color.White,
                    ),
                ) { Text(if (paused) "Resume" else "Pause", fontSize = 18.sp) }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).height(72.dp),
                    shape = RoundedCornerShape(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StopFill,
                        contentColor = StopInk,
                    ),
                ) { Text("Stop", fontSize = 18.sp) }
            }
        }
    }

    private fun beginRecording(onStarted: (Instant) -> Unit, onEnded: () -> Unit) {
        val started = Instant.now()
        recordingOpen = true
        onRecordingEnded = onEnded
        val file = newClipFile(File(cacheDir, "rec"))
        recorder.start(file) {
            runOnUiThread {
                finishAndSave()
                val end = onRecordingEnded
                onRecordingEnded = null
                end?.invoke()
            }
        }
        onStarted(started)
    }

    private fun finishAndSave() {
        if (!recordingOpen) return
        recordingOpen = false
        val file = recorder.file
        recorder.stop()
        if (file != null && file.exists() && file.length() > 0L) {
            recordings.save(file)
        } else {
            file?.delete()
        }
    }

    private fun discardRecording() {
        recordingOpen = false
        recorder.cancel()
    }
}

@Composable
private fun SearchRow(query: String, onQuery: (String) -> Unit, onOpenSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(Chip).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
                singleLine = true,
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search recordings", color = Muted, fontSize = 16.sp)
                    inner()
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF5C4B8A)).clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Text("S", color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun RecordingCard(
    title: String,
    subtitle: String,
    duration: String,
    progress: Float,
    playing: Boolean,
    onPlay: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                if (playing) "❚❚" else "▶",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier.clickable(onClick = onPlay).padding(8.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(subtitle, color = Muted, modifier = Modifier.weight(1f))
            Text(duration, color = Muted)
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF4A4A50))) {
            Box(
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Color(0xFFE4E4EA)),
            )
        }
    }
}

@Composable
private fun RecordButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(84.dp).clip(CircleShape).background(Coral).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(CoralCore))
    }
}

@Composable
private fun Waveform(samples: List<Float>, modifier: Modifier) {
    Canvas(
        modifier.clip(RoundedCornerShape(28.dp)).background(Color(0xFF141416)),
    ) {
        val count = 48
        val step = size.width / count
        val start = (samples.size - count).coerceAtLeast(0)
        val mid = size.height / 2f
        for (i in 0 until count) {
            val amp = samples.getOrNull(start + i) ?: 0.05f
            val radius = 3.5f + amp * 14f
            drawCircle(
                color = Color(0xFFE6E6EA),
                radius = radius,
                center = Offset(step * i + step / 2f, mid),
            )
        }
    }
}

@Composable
private fun TrashButton(onClick: () -> Unit) {
    Canvas(
        Modifier.size(28.dp).clickable(onClick = onClick).padding(2.dp),
    ) {
        val ink = Color(0xFFB0B0B6)
        drawRoundRect(
            color = ink,
            topLeft = Offset(size.width * 0.22f, size.height * 0.28f),
            size = Size(size.width * 0.56f, size.height * 0.58f),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
        drawRect(
            color = ink,
            topLeft = Offset(size.width * 0.15f, size.height * 0.2f),
            size = Size(size.width * 0.7f, size.height * 0.06f),
        )
        drawRect(
            color = ink,
            topLeft = Offset(size.width * 0.38f, size.height * 0.08f),
            size = Size(size.width * 0.24f, size.height * 0.1f),
        )
    }
}

private fun titleOf(at: ZonedDateTime): String =
    DateTimeFormatter.ofPattern("EEEE 'at' HH:mm").format(at)

private fun subtitleOf(at: ZonedDateTime): String =
    DateTimeFormatter.ofPattern("EEE, MMM d").format(at)

private fun monthOf(at: ZonedDateTime): String =
    DateTimeFormatter.ofPattern("MMMM yyyy").format(at)

private fun formatDuration(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

private fun formatClock(ms: Long): String {
    val tenths = (ms / 100).coerceAtLeast(0)
    val mm = tenths / 600
    val ss = (tenths / 10) % 60
    val t = tenths % 10
    return String.format("%02d:%02d.%d", mm, ss, t)
}

package moe.zyc.splitvise

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    private lateinit var api: Api
    private lateinit var recordings: LocalRecordings
    private val recorder = Recorder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = Api(prefs)
        recordings = LocalRecordings(File(filesDir, "recordings"))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }

    override fun onDestroy() {
        recorder.cancel()
        super.onDestroy()
    }

    private fun lastLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return providers.mapNotNull { p ->
            try {
                lm.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            }
        }.maxByOrNull { it.time }
    }

    private enum class Screen { Capture, Library }

    @Composable
    private fun App() {
        var showSettings by remember { mutableStateOf(!prefs.configured) }
        var screen by remember { mutableStateOf(Screen.Capture) }
        when {
            showSettings -> SettingsScreen(
                initialUrl = prefs.serverUrl,
                initialToken = prefs.token,
                onSave = { url, token ->
                    prefs.serverUrl = url
                    prefs.token = token
                    showSettings = false
                },
            )
            screen == Screen.Library -> LibraryScreen(onBack = { screen = Screen.Capture })
            else -> CaptureScreen(
                onOpenSettings = { showSettings = true },
                onOpenLibrary = { screen = Screen.Library },
            )
        }
    }

    @Composable
    private fun SettingsScreen(
        initialUrl: String,
        initialToken: String,
        onSave: (String, String) -> Unit,
    ) {
        var url by remember { mutableStateOf(initialUrl) }
        var token by remember { mutableStateOf(initialToken) }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("splitvise", style = MaterialTheme.typography.headlineMedium)
            Text("server URL and bearer token. the token is stored in EncryptedSharedPreferences.")
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

    private sealed interface Phase {
        data object Idle : Phase
        data object Recording : Phase
        data class Preview(val file: File, val durationMs: Int, val recordedAt: Instant) : Phase
        data object Uploading : Phase
        data class Done(val id: String) : Phase
        data class Err(val message: String) : Phase
    }

    @Composable
    private fun CaptureScreen(onOpenSettings: () -> Unit, onOpenLibrary: () -> Unit) {
        var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
        var tick by remember { mutableLongStateOf(0L) }
        var loc by remember { mutableStateOf<Location?>(null) }
        val scope = rememberCoroutineScope()

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { _ ->
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                loc = lastLocation()
                startRecording { phase = it }
            } else {
                phase = Phase.Err("microphone permission denied")
            }
        }

        LaunchedEffect(phase) {
            if (phase is Phase.Recording) {
                val start = SystemClock.elapsedRealtime()
                while (phase is Phase.Recording) {
                    tick = SystemClock.elapsedRealtime() - start
                    delay(100)
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose { /* recorder cleaned in onDestroy */ }
        }

        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("splitvise", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onOpenSettings) { Text("settings") }
            }

            when (val p = phase) {
                Phase.Idle -> {
                    Text("tap to record. 60 second cap. no typing.")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onOpenLibrary) { Text("saved") }
                    }
                    Button(onClick = {
                        val need = mutableListOf<String>()
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            need.add(Manifest.permission.RECORD_AUDIO)
                        }
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                        if (need.isNotEmpty()) {
                            permissionLauncher.launch(need.toTypedArray())
                        } else {
                            loc = lastLocation()
                            startRecording { phase = it }
                        }
                    }) { Text("record") }
                }
                Phase.Recording -> {
                    val sec = (tick / 1000).coerceAtMost(60)
                    Text("recording… ${sec}s / 60s")
                    Button(onClick = { phase = stopToPreview() }) { Text("stop") }
                }
                is Phase.Preview -> {
                    Text("preview ${(p.durationMs / 1000.0).format1()}s")
                    PreviewPlayer(p.file)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            p.file.delete()
                            phase = Phase.Idle
                        }) { Text("discard") }
                        Button(onClick = {
                            phase = Phase.Uploading
                            scope.launch {
                                phase = try {
                                    val ack = withContext(Dispatchers.IO) {
                                        api.upload(
                                            p.file,
                                            p.durationMs,
                                            loc?.latitude,
                                            loc?.longitude,
                                            loc?.accuracy,
                                            p.recordedAt,
                                        )
                                    }
                                    Phase.Done(ack.id)
                                } catch (e: Exception) {
                                    stashOutbox(p.file)
                                    Phase.Err(e.message ?: "upload failed")
                                }
                            }
                        }) { Text("upload") }
                    }
                }
                Phase.Uploading -> Text("uploading…")
                is Phase.Done -> {
                    Text("uploaded")
                    Button(onClick = { phase = Phase.Idle }) { Text("record another") }
                }
                is Phase.Err -> {
                    Text(p.message)
                    Button(onClick = { phase = Phase.Idle }) { Text("ok") }
                }
            }
        }
    }

    private fun startRecording(setPhase: (Phase) -> Unit) {
        val file = newClipFile(File(cacheDir, "rec"))
        recorder.start(file) {
            runOnUiThread { setPhase(stopToPreview()) }
        }
        setPhase(Phase.Recording)
    }

    private fun stopToPreview(): Phase {
        val duration = recorder.stop()
        val file = recorder.file ?: return Phase.Err("nothing recorded")
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            return Phase.Err("empty recording")
        }
        val saved = recordings.save(file)
        return Phase.Preview(saved, duration, Instant.now())
    }

    private fun stashOutbox(file: File) {
        val box = File(filesDir, "outbox")
        box.mkdirs()
        file.copyTo(File(box, file.name), overwrite = true)
    }

    @Composable
    private fun LibraryScreen(onBack: () -> Unit) {
        var files by remember { mutableStateOf(recordings.list()) }
        var playing by remember { mutableStateOf<String?>(null) }
        var player by remember { mutableStateOf<MediaPlayer?>(null) }
        DisposableEffect(Unit) {
            onDispose {
                player?.release()
            }
        }
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("saved", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = {
                    player?.release()
                    player = null
                    onBack()
                }) { Text("back") }
            }
            if (files.isEmpty()) {
                Text("nothing saved yet")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files, key = { it.absolutePath }) { file ->
                        val dur = recordings.durationMs(file)
                        val whenSaved = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(file.lastModified()))
                        val label = if (dur == null) whenSaved else "$whenSaved · ${(dur / 1000.0).format1()}s"
                        Column(Modifier.fillMaxWidth()) {
                            Text(label)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (playing == file.absolutePath) {
                                        player?.pause()
                                        playing = null
                                        return@Button
                                    }
                                    player?.release()
                                    val next = MediaPlayer()
                                    next.setDataSource(file.absolutePath)
                                    next.setOnCompletionListener { playing = null }
                                    next.prepare()
                                    next.start()
                                    player = next
                                    playing = file.absolutePath
                                }) { Text(if (playing == file.absolutePath) "pause" else "play") }
                                OutlinedButton(onClick = {
                                    if (playing == file.absolutePath) {
                                        player?.release()
                                        player = null
                                        playing = null
                                    }
                                    recordings.delete(file)
                                    files = recordings.list()
                                }) { Text("delete") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewPlayer(file: File) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(file) {
        onDispose {
            player?.release()
            player = null
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            player?.release()
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.start()
            player = p
        }) { Text("play") }
        OutlinedButton(onClick = {
            player?.stop()
            player?.release()
            player = null
        }) { Text("stop") }
    }
}

private fun Double.format1(): String = String.format("%.1f", this)

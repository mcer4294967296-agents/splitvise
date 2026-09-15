package moe.zyc.splitvise

import android.media.MediaRecorder
import java.io.File
import java.io.IOException

class Recorder {
    private var recorder: MediaRecorder? = null
    var file: File? = null
        private set
    var startedAtMs: Long = 0
        private set

    fun start(output: File, onMaxDuration: () -> Unit) {
        stopQuietly()
        file = output
        startedAtMs = System.currentTimeMillis()
        val rec = MediaRecorder()
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioSamplingRate(44100)
        rec.setAudioEncodingBitRate(64_000)
        rec.setAudioChannels(1)
        rec.setMaxDuration(MAX_DURATION_MS)
        rec.setOutputFile(output.absolutePath)
        rec.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                onMaxDuration()
            }
        }
        rec.prepare()
        rec.start()
        recorder = rec
    }

    fun stop(): Int {
        val rec = recorder ?: return 0
        val elapsed = (System.currentTimeMillis() - startedAtMs).toInt().coerceAtLeast(0)
        try {
            rec.stop()
        } catch (_: RuntimeException) {
            // stop() throws if nothing was captured
        }
        rec.reset()
        rec.release()
        recorder = null
        return elapsed.coerceAtMost(MAX_DURATION_MS)
    }

    fun cancel() {
        stopQuietly()
        file?.delete()
        file = null
    }

    private fun stopQuietly() {
        val rec = recorder ?: return
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        try {
            rec.reset()
            rec.release()
        } catch (_: Exception) {
        }
        recorder = null
    }

    companion object {
        const val MAX_DURATION_MS = 60_000
    }
}

fun newClipFile(dir: File): File {
    if (!dir.exists() && !dir.mkdirs()) {
        throw IOException("cannot create ${dir.absolutePath}")
    }
    return File(dir, "clip-${System.currentTimeMillis()}.m4a")
}

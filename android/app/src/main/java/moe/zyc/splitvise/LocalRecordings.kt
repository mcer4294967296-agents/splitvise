package moe.zyc.splitvise

import android.media.MediaMetadataRetriever
import java.io.File
import java.io.IOException

class LocalRecordings(private val dir: File) {
    init {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("cannot create ${dir.absolutePath}")
        }
    }

    /** Move a finished recording into the library. */
    fun save(source: File): File {
        val dest = File(dir, source.name)
        if (source.absolutePath == dest.absolutePath) return dest
        if (!source.renameTo(dest)) {
            source.copyTo(dest, overwrite = true)
            source.delete()
        }
        return dest
    }

    fun list(): List<File> {
        return dir.listFiles { f -> f.isFile && f.extension.equals("m4a", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun delete(file: File) {
        if (file.parentFile?.absolutePath == dir.absolutePath) file.delete()
    }

    fun durationMs(file: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}

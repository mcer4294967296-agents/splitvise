package moe.zyc.splitvise

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

data class ClipAck(
    val id: String,
)

class Api(private val prefs: Prefs) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun upload(
        file: File,
        durationMs: Int,
        lat: Double?,
        lng: Double?,
        accuracyM: Float?,
        recordedAt: Instant,
    ): ClipAck {
        val base = prefs.serverUrl
        val token = prefs.token
        if (token.isBlank()) throw IOException("no token configured")

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                file.name,
                file.asRequestBody("audio/mp4".toMediaType()),
            )
            .addFormDataPart("duration_ms", durationMs.toString())
            .addFormDataPart("recorded_at", recordedAt.toString())
            .apply {
                if (lat != null && lng != null) {
                    addFormDataPart("lat", lat.toString())
                    addFormDataPart("lng", lng.toString())
                    if (accuracyM != null) addFormDataPart("accuracy_m", accuracyM.toString())
                }
            }
            .build()

        val req = Request.Builder()
            .url("$base/v1/clips")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("upload failed ${resp.code}: $text")
            }
            val id = JSONObject(text).getString("id")
            return ClipAck(id)
        }
    }
}

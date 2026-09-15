package moe.zyc.splitvise

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class Prefs(context: Context) {
    private val prefs: SharedPreferences

    init {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "splitvise",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var serverUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL)?.trimEnd('/') ?: DEFAULT_URL
        set(value) {
            prefs.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()
        }

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value.trim()).apply()
        }

    val configured: Boolean
        get() = token.isNotBlank()

    companion object {
        const val DEFAULT_URL = "https://staging.zyc.moe/splitvise"
        private const val KEY_URL = "server_url"
        private const val KEY_TOKEN = "token"
    }
}

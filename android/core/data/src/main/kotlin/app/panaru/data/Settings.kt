package app.panaru.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Preferenze dell'utente. Non contengono segreti (le API key stanno cifrate nel database). */
data class PanaruSettings(
    val autoCopyLink: Boolean = true,
    val maxConcurrentUploads: Int = 2,
    val defaultExpiryDays: Int = 14,
    val defaultAllowedDownloads: Int = 1,
    /** Dallo Share Sheet: carica subito con le impostazioni predefinite, senza chiedere. */
    val uploadImmediatelyFromShare: Boolean = true,
) {
    companion object {
        val CONCURRENCY_RANGE = 1..4
    }
}

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("panaru.settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<PanaruSettings> = state.asStateFlow()

    private fun read() = PanaruSettings(
        autoCopyLink = prefs.getBoolean(K_COPY, true),
        maxConcurrentUploads = prefs.getInt(K_CONC, 2).coerceIn(PanaruSettings.CONCURRENCY_RANGE),
        defaultExpiryDays = prefs.getInt(K_EXP, 14).coerceAtLeast(0),
        defaultAllowedDownloads = prefs.getInt(K_DL, 1).coerceAtLeast(0),
        uploadImmediatelyFromShare = prefs.getBoolean(K_IMM, true),
    )

    fun update(transform: (PanaruSettings) -> PanaruSettings) {
        val next = transform(state.value).let {
            it.copy(
                maxConcurrentUploads = it.maxConcurrentUploads.coerceIn(PanaruSettings.CONCURRENCY_RANGE),
                defaultExpiryDays = it.defaultExpiryDays.coerceAtLeast(0),
                defaultAllowedDownloads = it.defaultAllowedDownloads.coerceAtLeast(0),
            )
        }
        prefs.edit()
            .putBoolean(K_COPY, next.autoCopyLink)
            .putInt(K_CONC, next.maxConcurrentUploads)
            .putInt(K_EXP, next.defaultExpiryDays)
            .putInt(K_DL, next.defaultAllowedDownloads)
            .putBoolean(K_IMM, next.uploadImmediatelyFromShare)
            .apply()
        state.value = next
    }

    private companion object {
        const val K_COPY = "auto_copy"
        const val K_CONC = "max_concurrent"
        const val K_EXP = "default_expiry_days"
        const val K_DL = "default_downloads"
        const val K_IMM = "share_immediate"
    }
}

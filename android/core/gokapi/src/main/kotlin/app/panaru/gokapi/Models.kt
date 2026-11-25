package app.panaru.gokapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Risposta di `GET /api/info/version`. */
@Serializable
data class VersionInfo(
    @SerialName("Version") val version: String = "",
    @SerialName("VersionInt") val versionInt: Int = 0,
) {
    /** I nomi file in base64 per `/chunk/complete` sono supportati da Gokapi 2.0.1 (20001). */
    val supportsBase64FileNames: Boolean get() = versionInt >= MIN_SUPPORTED_VERSION

    companion object {
        const val MIN_SUPPORTED_VERSION = 20001
    }
}

/** Risposta di `GET /api/info/config`. Le dimensioni sono in megabyte. */
@Serializable
data class ServerConfig(
    @SerialName("MaxFilesize") val maxFileSizeMb: Int = 0,
    @SerialName("MaxChunksize") val maxChunkSizeMb: Int = 0,
    @SerialName("EndToEndEncryptionEnabled") val endToEndEncryptionEnabled: Boolean = false,
)

/** Oggetto `File` di Gokapi (solo i campi usati da Panaru). */
@Serializable
data class GokapiFile(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("SizeBytes") val sizeBytes: Long = 0,
    @SerialName("ContentType") val contentType: String = "",
    @SerialName("UrlDownload") val urlDownload: String = "",
    @SerialName("UrlHotlink") val urlHotlink: String = "",
    @SerialName("UploadDate") val uploadDate: Long = 0,
    @SerialName("ExpireAt") val expireAt: Long = 0,
    @SerialName("UnlimitedTime") val unlimitedTime: Boolean = false,
    @SerialName("UnlimitedDownloads") val unlimitedDownloads: Boolean = false,
    @SerialName("DownloadsRemaining") val downloadsRemaining: Int = 0,
    @SerialName("DownloadCount") val downloadCount: Int = 0,
    @SerialName("IsPasswordProtected") val isPasswordProtected: Boolean = false,
    @SerialName("IsEndToEndEncrypted") val isEndToEndEncrypted: Boolean = false,
    @SerialName("IsPendingDeletion") val isPendingDeletion: Boolean = false,
)

@Serializable
internal data class UploadResult(
    @SerialName("Result") val result: String = "",
    @SerialName("FileInfo") val fileInfo: GokapiFile? = null,
)

@Serializable
internal data class ErrorBody(
    @SerialName("Result") val result: String = "",
    @SerialName("ErrorMessage") val message: String = "",
    @SerialName("ErrorCode") val code: Int = -1,
)

/**
 * Opzioni di condivisione passate a `/chunk/complete`.
 * @property expiryDays giorni di validità; 0 = nessuna scadenza.
 * @property allowedDownloads download consentiti; 0 = illimitati.
 * @property password password opzionale (solo ASCII stampabile: Gokapi la legge da un header HTTP).
 */
data class UploadOptions(
    val expiryDays: Int = 14,
    val allowedDownloads: Int = 1,
    val password: String? = null,
) {
    init {
        require(expiryDays >= 0) { "expiryDays negativo" }
        require(allowedDownloads >= 0) { "allowedDownloads negativo" }
        require(password == null || isValidPassword(password)) { "password non valida" }
    }

    companion object {
        /** Gokapi riceve la password come header: sono ammessi solo caratteri ASCII stampabili. */
        fun isValidPassword(value: String): Boolean = value.all { it in ' '..'~' }
    }
}

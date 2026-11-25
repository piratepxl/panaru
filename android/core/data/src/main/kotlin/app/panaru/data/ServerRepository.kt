package app.panaru.data

import app.panaru.gokapi.GokapiClient
import app.panaru.gokapi.GokapiException
import app.panaru.gokapi.ServerConfig
import app.panaru.gokapi.ServerUrl
import app.panaru.gokapi.VersionInfo
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/** Server configurato, senza la chiave in chiaro. */
data class Server(val id: Long, val name: String, val baseUrl: String, val isDefault: Boolean) {
    val isInsecureLocal: Boolean get() = baseUrl.startsWith("http://")
}

/** Esito di "Verifica connessione". */
sealed interface ConnectionCheck {
    data class Ok(val version: VersionInfo, val config: ServerConfig) : ConnectionCheck {
        val versionTooOld: Boolean get() = !version.supportsBase64FileNames
    }
    data class Failed(val error: GokapiException) : ConnectionCheck
}

class ServerRepository(
    private val dao: ServerDao,
    private val secrets: SecretCipher,
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val configCache = ConcurrentHashMap<Long, Pair<Long, ServerConfig>>()

    val servers: Flow<List<ServerEntity>> get() = dao.observeAll()

    suspend fun all(): List<Server> = dao.all().map { it.toModel() }
    suspend fun byId(id: Long): Server? = dao.byId(id)?.toModel()
    suspend fun defaultServer(): Server? = (dao.defaultServer() ?: dao.all().firstOrNull())?.toModel()

    /** Verifica indirizzo e chiave senza salvare: `/info/version` (chiave valida) + `/info/config` (permesso UPLOAD). */
    suspend fun check(url: ServerUrl.Result.Valid, apiKey: String): ConnectionCheck {
        val client = GokapiClient(url.url, apiKey.trim(), http)
        return try {
            val version = client.version()
            val config = client.config()
            ConnectionCheck.Ok(version, config)
        } catch (e: GokapiException) {
            ConnectionCheck.Failed(e)
        }
    }

    /** Verifica un nuovo indirizzo usando la chiave già salvata per il server [id]. */
    suspend fun checkSaved(id: Long, url: ServerUrl.Result.Valid): ConnectionCheck {
        val e = dao.byId(id) ?: return ConnectionCheck.Failed(GokapiException.NotFound("Server removed"))
        return check(url, secrets.open(Sealed(e.keyCipher, e.keyIv)))
    }

    suspend fun add(name: String, url: ServerUrl.Result.Valid, apiKey: String, makeDefault: Boolean): Long {
        val sealed = secrets.seal(apiKey.trim())
        return dao.insertAsDefaultIfFirst(
            ServerEntity(
                name = name.trim().ifEmpty { url.url.host },
                baseUrl = url.canonical,
                isDefault = makeDefault,
                keyCipher = sealed.cipherText,
                keyIv = sealed.iv,
                createdAt = clock(),
            ),
        )
    }

    /** Aggiorna nome/indirizzo; la chiave viene sostituita solo se [newApiKey] non è vuota. */
    suspend fun update(id: Long, name: String, url: ServerUrl.Result.Valid, newApiKey: String?) {
        val current = dao.byId(id) ?: return
        val sealed = newApiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { secrets.seal(it) }
        dao.update(
            current.copy(
                name = name.trim().ifEmpty { url.url.host },
                baseUrl = url.canonical,
                keyCipher = sealed?.cipherText ?: current.keyCipher,
                keyIv = sealed?.iv ?: current.keyIv,
            ),
        )
        configCache.remove(id)
    }

    suspend fun setDefault(id: Long) = dao.setDefault(id)

    suspend fun delete(id: Long) {
        val wasDefault = dao.byId(id)?.isDefault == true
        dao.delete(id)
        configCache.remove(id)
        if (wasDefault) dao.all().firstOrNull()?.let { dao.setDefault(it.id) }
    }

    /** Client autenticato per il server. La chiave viene decifrata solo in memoria, al bisogno. */
    suspend fun client(id: Long): GokapiClient? {
        val e = dao.byId(id) ?: return null
        val key = secrets.open(Sealed(e.keyCipher, e.keyIv))
        return GokapiClient(e.baseUrl.toHttpUrl(), key, http)
    }

    /** Parametri di upload del server, in cache per 10 minuti. `null` se non disponibili. */
    suspend fun config(id: Long, client: GokapiClient): ServerConfig? {
        configCache[id]?.let { (at, cfg) -> if (clock() - at < CONFIG_TTL_MS) return cfg }
        return try {
            client.config().also { configCache[id] = clock() to it }
        } catch (e: GokapiException.NotFound) {
            null // server molto vecchio senza /info/config: si usano i valori di ripiego
        }
    }

    private fun ServerEntity.toModel() = Server(id, name, baseUrl, isDefault)

    private companion object {
        const val CONFIG_TTL_MS = 10 * 60 * 1000L
    }
}

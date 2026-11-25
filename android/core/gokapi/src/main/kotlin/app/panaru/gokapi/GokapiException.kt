package app.panaru.gokapi

import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Errori tipizzati del client: ciascuno corrisponde a un messaggio comprensibile nella UI. */
sealed class GokapiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401: chiave errata, revocata o senza il permesso richiesto (Gokapi non li distingue). */
    class Unauthorized(message: String = "Unauthorized") : GokapiException(message)

    /** 404: file inesistente o scaduto. */
    class NotFound(message: String = "Not found") : GokapiException(message)

    /** Il file supera `MaxFilesize` del server. */
    class FileTooLarge(val maxMb: Int?) : GokapiException("File too large (max $maxMb MB)")

    /** 429: troppe richieste (rate limiter di Gokapi). */
    class RateLimited : GokapiException("Too many requests")

    /** Errore restituito dal server con codice Gokapi e messaggio. */
    class Server(val status: Int, val code: Int?, message: String) : GokapiException(message) {
        val isRetryable: Boolean get() = status >= 500
    }

    /** Errore di rete (timeout, connessione rifiutata, DNS…). Ritentabile. */
    class Network(cause: Throwable) : GokapiException(cause.message ?: "Network error", cause)

    /** Certificato non valido o handshake TLS fallito. Non ritentabile. */
    class Tls(cause: Throwable) : GokapiException(cause.message ?: "TLS error", cause)

    /** Non è stato possibile leggere il file sorgente (permesso revocato, file modificato…). */
    class SourceUnavailable(cause: Throwable) : GokapiException(cause.message ?: "Source unavailable", cause)

    /** Il server ha risposto in un formato inatteso (probabilmente non è Gokapi). */
    class Protocol(message: String) : GokapiException(message)

    companion object {
        internal fun fromIo(e: IOException): GokapiException {
            var c: Throwable? = e
            while (c != null) {
                when (c) {
                    is SourceReadException -> return SourceUnavailable(c.cause ?: c)
                    is SSLException, is CertificateException -> return Tls(e)
                }
                c = c.cause
            }
            return Network(e)
        }
    }
}

/** Errore di lettura della sorgente durante la scrittura del corpo della richiesta. */
class SourceReadException(message: String, cause: Throwable? = null) : IOException(message, cause)

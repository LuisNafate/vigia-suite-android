package com.vigiasuite.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Comunicación HTTP con NVD: un único solicitante en serie, control de frecuencia, deduplicación, tiempo de espera,
 * reintentos ante 429/503 y caché local. Mismas reglas que vigia-suite-web/pc-lab/nvd-client.js. Límite público sin
 * clave (nvd.nist.gov/developers/start-here): 5 peticiones en 30 s; NVD recomienda esperar unos 6 s entre peticiones.
 * A diferencia del navegador, Android puede leer las cabeceras «message» y «Retry-After».
 */
class NvdRequestException(val kind: String, message: String, val status: Int? = null, val retryAfterSeconds: Long? = null) : IOException(message)

data class NvdHttpResult(val status: Int, val body: String?, val message: String? = null, val retryAfterSeconds: Long? = null)

/** Transporte bloqueante; en producción HttpURLConnection. Lanza SocketTimeoutException o IOException sin red. */
fun interface NvdTransport {
    fun get(url: String, timeoutMs: Int): NvdHttpResult
}

/** Sólo HTTPS (usesCleartextTraffic="false"), sin cookies ni clave de API; el agente es genérico. */
object UrlConnectionTransport : NvdTransport {
    override fun get(url: String, timeoutMs: Int): NvdHttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "VigiaSuite-Android")
            val status = connection.responseCode
            val body = if (status in 200..299) connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } else null
            return NvdHttpResult(status, body, connection.getHeaderField("message"), connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
        } finally {
            connection.disconnect()
        }
    }
}

data class NvdCachedEntry(val url: String, val body: String, val fetchedAt: Long)

interface NvdStore {
    suspend fun get(url: String): NvdCachedEntry?
    suspend fun put(entry: NvdCachedEntry)
}

class MemoryNvdStore : NvdStore {
    private val entries = LinkedHashMap<String, NvdCachedEntry>()
    override suspend fun get(url: String) = synchronized(entries) { entries[url] }
    override suspend fun put(entry: NvdCachedEntry) { synchronized(entries) { entries[entry.url] = entry } }
}

data class NvdResponse(val json: JSONObject, val fetchedAt: Long, val fromCache: Boolean, val stale: Boolean, val offline: Boolean)

class NvdClient(
    private val transport: NvdTransport = UrlConnectionTransport,
    private val store: NvdStore = MemoryNvdStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val ttlMs: Long = CACHE_TTL_MS,
    private val onWait: (reason: String, ms: Long) -> Unit = { _, _ -> },
) {
    private val gate = Mutex()
    private val inFlightLock = Mutex()
    private val inFlight = HashMap<String, CompletableDeferred<NvdResponse>>()
    private var lastStart: Long? = null

    private suspend fun throttle() {
        val wait = (lastStart ?: Long.MIN_VALUE / 2) + minIntervalMs - clock()
        if (wait > 0) { onWait(WAIT_INTERVAL, wait); sleeper(wait) }
        lastStart = clock()
    }

    private suspend fun once(url: String): JSONObject {
        throttle()
        val result = try {
            withContext(Dispatchers.IO) { transport.get(url, timeoutMs) }
        } catch (error: SocketTimeoutException) {
            throw NvdRequestException("timeout", errorMessage("timeout"))
        } catch (error: IOException) {
            throw NvdRequestException("offline", errorMessage("offline"))
        }
        if (result.status in 200..299) {
            return runCatching { JSONObject(result.body ?: "") }.getOrElse { throw NvdRequestException("format", errorMessage("format"), result.status) }
        }
        val kind = when (result.status) { 429 -> "rate_limited"; 403 -> "forbidden"; 404 -> "bad_request"; 503 -> "unavailable"; else -> "http" }
        val detail = result.message?.let { " NVD indica: $it" } ?: ""
        throw NvdRequestException(kind, errorMessage(kind, result.status) + detail, result.status, result.retryAfterSeconds)
    }

    private suspend fun fetchWithRetry(url: String): JSONObject {
        var attempt = 0
        while (true) {
            try { return once(url) } catch (error: NvdRequestException) {
                val retryable = (error.kind == "rate_limited" || error.kind == "unavailable") && attempt < 1
                if (!retryable) throw error
                val ms = if (error.kind == "rate_limited") {
                    (error.retryAfterSeconds?.times(1000) ?: RATE_LIMIT_BACKOFF_MS).coerceIn(RATE_LIMIT_BACKOFF_MS, MAX_BACKOFF_MS)
                } else UNAVAILABLE_BACKOFF_MS
                onWait(error.kind, ms)
                sleeper(ms)
                attempt += 1
            }
        }
    }

    private suspend fun load(url: String, force: Boolean): NvdResponse {
        val cached = runCatching { store.get(url) }.getOrNull()
        if (!force && cached != null && clock() - cached.fetchedAt < ttlMs) {
            runCatching { JSONObject(cached.body) }.getOrNull()?.let { return NvdResponse(it, cached.fetchedAt, fromCache = true, stale = false, offline = false) }
        }
        return try {
            // Una sola petición a la vez: el intervalo se mide entre peticiones reales.
            val json = gate.withLock { fetchWithRetry(url) }
            val fetchedAt = clock()
            runCatching { store.put(NvdCachedEntry(url, json.toString(), fetchedAt)) }
            NvdResponse(json, fetchedAt, fromCache = false, stale = false, offline = false)
        } catch (error: NvdRequestException) {
            val copy = cached?.let { runCatching { JSONObject(it.body) }.getOrNull() }
            if (copy != null && (error.kind == "offline" || error.kind == "timeout")) NvdResponse(copy, cached.fetchedAt, fromCache = true, stale = true, offline = true)
            else throw error
        }
    }

    /** Sin force, una copia de menos de 24 h se entrega sin consultar a NVD. Las consultas idénticas simultáneas se agrupan. */
    suspend fun get(url: String, force: Boolean = false): NvdResponse {
        val key = (if (force) "force:" else "") + url
        val (deferred, owner) = inFlightLock.withLock {
            inFlight[key]?.let { it to false } ?: CompletableDeferred<NvdResponse>().also { inFlight[key] = it }.let { it to true }
        }
        if (!owner) return deferred.await()
        try {
            deferred.complete(load(url, force))
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
        } finally {
            inFlightLock.withLock { inFlight.remove(key) }
        }
        return deferred.await()
    }

    companion object {
        const val MIN_INTERVAL_MS = 6_000L
        const val TIMEOUT_MS = 30_000
        const val RATE_LIMIT_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val UNAVAILABLE_BACKOFF_MS = 10_000L
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        const val WAIT_INTERVAL = "interval"

        private val MESSAGES = mapOf(
            "rate_limited" to "NVD limitó la frecuencia de consultas (HTTP 429). Espera unos segundos y vuelve a intentarlo.",
            "forbidden" to "NVD rechazó la consulta (HTTP 403). Puede deberse al límite de frecuencia; espera 30 s antes de reintentar.",
            "bad_request" to "NVD no aceptó los parámetros de la consulta (HTTP 404).",
            "unavailable" to "NVD no está disponible temporalmente (HTTP 503). Vuelve a intentarlo más tarde.",
            "timeout" to "NVD no respondió a tiempo. Revisa la conexión y vuelve a intentarlo.",
            "offline" to "No hay conexión con NVD. Comprueba la red; las consultas guardadas siguen disponibles.",
            "format" to "La respuesta de NVD no es JSON válido.",
        )
        fun errorMessage(kind: String, status: Int? = null) = MESSAGES[kind] ?: "NVD respondió con un error HTTP $status."
    }
}

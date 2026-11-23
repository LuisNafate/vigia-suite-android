package com.vigiasuite.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cliente NVD y caché Room con un transporte simulado: ninguna prueba consulta a NVD. Mismos escenarios que
 * vigia-suite-web/tests/nvd-client.test.mjs.
 */
@RunWith(RobolectricTestRunner::class)
class NvdClientTest {
    private val urlA = "https://services.nvd.nist.gov/rest/json/cves/2.0?cveIds=CVE-2021-44228"
    private val urlB = "https://services.nvd.nist.gov/rest/json/cves/2.0?cveIds=CVE-2022-0001"
    private fun ok(body: String = """{"vulnerabilities":[]}""") = NvdHttpResult(200, body)

    private inner class Harness(responses: List<Any>, store: NvdStore = MemoryNvdStore(), private val onWait: (String, Long) -> Unit = { _, _ -> }) {
        var clock = 1_000_000L
        val sleeps = mutableListOf<Long>()
        val calls = mutableListOf<Pair<String, Long>>()
        private val queue = ArrayDeque(responses)
        val transport = NvdTransport { url, _ ->
            synchronized(calls) { calls += url to clock }
            when (val next = queue.removeFirst()) { is Throwable -> throw next; is NvdHttpResult -> next; else -> error("respuesta no válida") }
        }
        val client = NvdClient(transport, store, clock = { clock }, sleeper = { sleeps += it; clock += it }, onWait = onWait)
    }

    @Test
    fun keepsSixSecondsBetweenRequestsAndNeverMoreThanFiveIn30Seconds() = runBlocking {
        val urls = (0 until 12).map { "$urlA&n=$it" }
        val h = Harness(urls.map { ok() })
        urls.forEach { h.client.get(it) }
        assertEquals(12, h.calls.size)
        h.calls.zipWithNext().forEach { (a, b) -> assertTrue(b.second - a.second >= 6_000) }
        h.calls.forEach { (_, at) -> assertTrue(h.calls.count { it.second >= at && it.second < at + 30_000 } <= 5) }
    }

    @Test
    fun simultaneousRequestsForTheSameUrlAreSharedAsOne() = runBlocking {
        val count = AtomicInteger()
        val transport = NvdTransport { _, _ -> count.incrementAndGet(); Thread.sleep(200); ok("""{"vulnerabilities":[1]}""") }
        val client = NvdClient(transport, sleeper = {})
        val results = (0 until 3).map { async(Dispatchers.Default) { client.get(urlA) } }.awaitAll()
        assertEquals(1, count.get())
        assertTrue(results.all { it.json.getJSONArray("vulnerabilities").length() == 1 })
    }

    @Test
    fun cacheRecordsWhenItWasFetchedExpiresAfter24HoursAndCanBeForced() = runBlocking {
        val h = Harness(listOf(ok("""{"v":1}"""), ok("""{"v":2}"""), ok("""{"v":3}""")))
        val first = h.client.get(urlA)
        assertFalse(first.fromCache)
        h.clock += 60_000
        val cached = h.client.get(urlA)
        assertEquals(listOf(true, first.fetchedAt, 1, 1), listOf(cached.fromCache, cached.fetchedAt, cached.json.getInt("v"), h.calls.size))
        val forced = h.client.get(urlA, force = true)
        assertEquals(listOf(false, 2, 2), listOf(forced.fromCache, forced.json.getInt("v"), h.calls.size))
        h.clock += NvdClient.CACHE_TTL_MS + 1
        val expired = h.client.get(urlA)
        assertEquals(listOf(false, 3, 3), listOf(expired.fromCache, expired.json.getInt("v"), h.calls.size))
    }

    @Test
    fun http429WaitsAndRetriesOnceHonouringRetryAfter() = runBlocking {
        val waits = mutableListOf<Pair<String, Long>>()
        val h = Harness(listOf(NvdHttpResult(429, null), ok("""{"v":"ok"}""")), onWait = { reason, ms -> waits += reason to ms })
        assertEquals("ok", h.client.get(urlA).json.getString("v"))
        assertTrue(waits.contains("rate_limited" to NvdClient.RATE_LIMIT_BACKOFF_MS))
        assertTrue(h.calls[1].second - h.calls[0].second >= NvdClient.RATE_LIMIT_BACKOFF_MS)

        val withHeader = Harness(listOf(NvdHttpResult(429, null, retryAfterSeconds = 45), ok()))
        withHeader.client.get(urlA)
        assertTrue(45_000L in withHeader.sleeps)
        val capped = Harness(listOf(NvdHttpResult(429, null, retryAfterSeconds = 3_600), ok()))
        capped.client.get(urlA)
        assertTrue("la espera se limita a 60 s", NvdClient.MAX_BACKOFF_MS in capped.sleeps && capped.sleeps.none { it > NvdClient.MAX_BACKOFF_MS })
    }

    @Test
    fun repeated429EndsInARateLimitError() = runBlocking {
        val h = Harness(listOf(NvdHttpResult(429, null), NvdHttpResult(429, null), ok()))
        val error = runCatching { h.client.get(urlA) }.exceptionOrNull() as NvdRequestException
        assertEquals("rate_limited" to 429, error.kind to error.status)
        assertEquals(2, h.calls.size)
    }

    @Test
    fun http503RetriesOnceAnd403404500DoNot() = runBlocking {
        assertEquals(1, Harness(listOf(NvdHttpResult(503, null), ok("""{"v":1}"""))).client.get(urlA).json.getInt("v"))
        for ((status, kind) in listOf(403 to "forbidden", 404 to "bad_request", 500 to "http")) {
            val h = Harness(listOf(NvdHttpResult(status, null, message = "Invalid cveId parameter."), ok()))
            val error = runCatching { h.client.get(urlA) }.exceptionOrNull() as NvdRequestException
            assertEquals(kind to status, error.kind to error.status)
            assertTrue("Android sí puede leer la cabecera message", error.message!!.contains("Invalid cveId parameter."))
            assertEquals(1, h.calls.size)
        }
    }

    @Test
    fun invalidJsonIsAFormatErrorAndIsNotCached() = runBlocking {
        val store = MemoryNvdStore()
        val h = Harness(listOf(ok("<html>no es JSON</html>")), store)
        assertEquals("format", (runCatching { h.client.get(urlA) }.exceptionOrNull() as NvdRequestException).kind)
        assertNull(store.get(urlA))
    }

    @Test
    fun offlineFailsWithoutCacheAndServesTheExpiredCopyMarkedAsOffline() = runBlocking {
        assertEquals("offline", (runCatching { Harness(listOf(IOException("sin red"))).client.get(urlA) }.exceptionOrNull() as NvdRequestException).kind)
        assertEquals("timeout", (runCatching { Harness(listOf(SocketTimeoutException("lento"))).client.get(urlA) }.exceptionOrNull() as NvdRequestException).kind)
        val store = MemoryNvdStore()
        val old = 1_000_000L - NvdClient.CACHE_TTL_MS - 1
        store.put(NvdCachedEntry(urlB, """{"v":"guardada"}""", old))
        val h = Harness(listOf(IOException("sin red")), store)
        val result = h.client.get(urlB)
        assertEquals(listOf("guardada", true, true, true, old), listOf(result.json.getString("v"), result.fromCache, result.stale, result.offline, result.fetchedAt))
        assertEquals(1, h.calls.size)
    }

    @Test
    fun anErrorDoesNotBlockTheNextRequest() = runBlocking {
        val h = Harness(listOf(NvdHttpResult(500, null), ok("""{"v":"siguiente"}""")))
        assertTrue(runCatching { h.client.get(urlA) }.isFailure)
        assertEquals("siguiente", h.client.get(urlB).json.getString("v"))
    }

    @Test
    fun roomCacheIsSeparateFromHistorySurvivesNewClientsAndKeepsTheNewestEntries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, NvdCacheDatabase::class.java).allowMainThreadQueries().build()
        try {
            assertFalse("la caché NVD no usa la base del historial", NvdCacheDatabase.NAME == HistoryDatabase.NAME)
            val store = RoomNvdStore(database.cacheDao(), maxEntries = 3)
            val first = Harness(listOf(ok("""{"v":1}""")), store)
            first.client.get(urlA)
            val second = Harness(emptyList(), store).apply { clock = first.clock + 1_000 }
            val cached = second.client.get(urlA)
            assertEquals(listOf(true, 0), listOf(cached.fromCache, second.calls.size))
            (1..5).forEach { store.put(NvdCachedEntry("$urlB&n=$it", "{}", first.clock + it)) }
            assertEquals(3, database.cacheDao().count())
            assertNull("se retiran las consultadas hace más tiempo", store.get(urlA))
            assertTrue(store.get("$urlB&n=5") != null)
        } finally {
            database.close()
        }
    }
}

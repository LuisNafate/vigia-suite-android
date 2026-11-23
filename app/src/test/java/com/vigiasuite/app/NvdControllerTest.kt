package com.vigiasuite.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Estado de la sección NVD y textos de la pantalla con respuestas simuladas de nvd-cases.json: ninguna prueba consulta
 * a NVD. Mismo recorrido que vigia-suite-web/tests/nvd-browser.test.mjs.
 */
@RunWith(RobolectricTestRunner::class)
class NvdControllerTest {
    private val fixture: JSONObject by lazy { JSONObject(javaClass.classLoader!!.getResource("nvd-cases.json")!!.readText()) }

    private fun fixtureBody(name: String) = fixture.getJSONObject("responses").getJSONObject(name).toString()

    private fun controller(routes: Map<String, String>, offline: () -> Boolean = { false }, requested: MutableList<String> = mutableListOf()): NvdController {
        val transport = NvdTransport { url, _ ->
            requested += url
            if (offline()) throw IOException("sin red")
            routes[url]?.let { NvdHttpResult(200, it) } ?: NvdHttpResult(404, null)
        }
        return NvdController({ onWait -> NvdClient(transport, MemoryNvdStore(), sleeper = {}, onWait = onWait) }, CoroutineScope(Dispatchers.Unconfined))
    }

    @Test
    fun controllerSearchesByIdKeywordAndProductAndReportsStates() = runBlocking {
        val base = "https://services.nvd.nist.gov/rest/json"
        val cpe = "cpe:2.3:a:apache:log4j:2.14.1:*:*:*:*:*:*:*"
        val cpeBody = """{"resultsPerPage":1,"startIndex":0,"totalResults":1,"products":[{"cpe":{"deprecated":false,"cpeName":"$cpe","titles":[{"title":"Apache Log4j 2.14.1","lang":"en"}]}}]}"""
        var offline = false
        val requested = mutableListOf<String>()
        val nvd = controller(mapOf(
            "$base/cves/2.0?cveIds=CVE-2021-44228" to fixtureBody("cve-log4j"),
            "$base/cpes/2.0?keywordSearch=apache%20log4j&resultsPerPage=20&startIndex=0" to cpeBody,
            "$base/cves/2.0?cpeName=${Nvd.encodeQueryValue(cpe)}&isVulnerable&resultsPerPage=20&startIndex=0" to fixtureBody("cve-log4j"),
            "$base/cves/2.0?keywordSearch=log&resultsPerPage=20&startIndex=0" to fixtureBody("keyword-page-1"),
            "$base/cves/2.0?keywordSearch=log&resultsPerPage=20&startIndex=2" to fixtureBody("keyword-page-2"),
            "$base/cves/2.0?keywordSearch=vacio&resultsPerPage=20&startIndex=0" to fixtureBody("cve-empty"),
        ), { offline }, requested)

        // Entradas inválidas: mensaje y ninguna petición.
        nvd.search("CVE-21-1")?.join()
        assertTrue(nvd.state.value.error && "CVE-AAAA-NNNN" in nvd.state.value.status)
        nvd.setMode(NvdMode.KEYWORD)
        nvd.search("router 04:B2:47:82:60:1C")?.join()
        assertTrue("No se envían direcciones MAC" in nvd.state.value.status)
        assertTrue(requested.isEmpty())

        // Identificador CVE → detalle abierto.
        nvd.setMode(NvdMode.CVE)
        nvd.search(" cve-2021-44228 ")!!.join()
        assertEquals("CVE-2021-44228", nvd.state.value.detail?.record?.id)
        assertEquals("Consultado en NVD", nvdFetchedText(nvd.state.value)!!.substringBefore(" el "))

        // Palabras clave con paginación y sin resultados.
        nvd.setMode(NvdMode.KEYWORD)
        nvd.search("log")!!.join()
        assertEquals(listOf(2, 3, 2), listOf(nvd.state.value.cves.size, nvd.state.value.cveTotal, nvd.state.value.cveNext))
        nvd.loadMore()!!.join()
        assertEquals(listOf("CVE-2022-0001", "CVE-2022-0002", "CVE-2022-0003"), nvd.state.value.cves.map { it.record.id })
        assertNull(nvd.state.value.cveNext)
        nvd.search("vacio")!!.join()
        assertEquals("Sin resultados en NVD para esta consulta.", nvd.state.value.status)

        // Producto: búsqueda CPE, selección y aplicabilidad.
        nvd.setMode(NvdMode.CPE)
        nvd.search("apache log4j")!!.join()
        assertEquals(1, nvd.state.value.cpes.size)
        nvd.selectCpe(0)!!.join()
        assertEquals(Applicability.APPLIES, nvd.state.value.cves.single().applicability?.status)

        // Sin conexión: «Actualizar» conserva la copia y lo indica.
        offline = true
        nvd.refresh()!!.join()
        assertTrue(nvd.state.value.offline)
        assertTrue(nvdFetchedText(nvd.state.value)!!.startsWith("Sin conexión con NVD"))
        assertEquals(1, nvd.state.value.cves.size)

        // Ninguna petición contiene datos del sensor.
        assertTrue(requested.none { Regex("ssid|bssid|address|rssi", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        if (requested.any { !it.startsWith(base) }) fail("sólo se contacta con NVD")
    }

    @Test
    fun screenTextsNeverPresentAnUnscoredRecordAsLowAndShowVersionAndSource() {
        val responses = fixture.getJSONObject("responses")
        val unscored = Nvd.parseCveResponse(responses.getJSONObject("cve-no-metrics")).items.single()
        assertEquals("Sin puntuación CVSS", nvdMetricText(unscored.primaryMetric))
        assertFalse("Baja" in nvdMetricText(unscored.primaryMetric))
        val log4j = Nvd.parseCveResponse(responses.getJSONObject("cve-log4j")).items.single()
        assertEquals("Crítica · 10.0 · CVSS 3.1", nvdMetricText(log4j.primaryMetric))
        assertEquals("nvd@nist.gov · principal (NVD)", nvdMetricSource(log4j.metrics.first()))
        assertEquals("Alta · 8.7 · CVSS 4.0", nvdMetricText(Nvd.parseCveResponse(responses.getJSONObject("cve-v4")).items.single().primaryMetric))
        assertEquals("Media · 5.0 · CVSS 2.0", nvdMetricText(Nvd.parseCveResponse(responses.getJSONObject("cve-v2-only")).items.single().primaryMetric))
        assertEquals(listOf("cualquier versión", "sin versión", "versión 2.14.1"), listOf("*", "-", "2.14.1").map(::nvdVersionLabel))
        assertNull(nvdFetchedText(NvdUiState()))
        assertTrue(nvdFetchedText(NvdUiState(fetchedAt = 0, fromCache = true))!!.startsWith("Datos guardados en este teléfono"))
    }
}

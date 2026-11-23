package com.vigiasuite.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Casos de app/src/test/resources/nvd-cases.json, copia idéntica de vigia-suite-web/tests/fixtures/: la versión Web
 * los verifica con nvd-core.js (tests/nvd-core.test.mjs) usando el mismo resumen de cada respuesta.
 */
@RunWith(RobolectricTestRunner::class)
class NvdEquivalenceTest {
    private val fixture: JSONObject by lazy { JSONObject(javaClass.classLoader!!.getResource("nvd-cases.json")!!.readText()) }
    private fun response(name: String) = fixture.getJSONObject("responses").getJSONObject(name)

    /** JSON → estructuras Kotlin; los números se comparan como Double (10 y 10.0 son la misma puntuación). */
    private fun plain(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { plain(value.opt(it)) }
        is JSONArray -> (0 until value.length()).map { plain(value.opt(it)) }
        is Number -> value.toDouble()
        is Map<*, *> -> value.mapValues { plain(it.value) }
        is List<*> -> value.map(::plain)
        else -> value
    }

    private fun page(page: NvdPage) = mapOf("totalResults" to page.totalResults, "startIndex" to page.startIndex, "resultsPerPage" to page.resultsPerPage, "nextStartIndex" to page.nextStartIndex)
    private fun metric(metric: CvssMetric?) = metric?.let { mapOf("version" to it.version, "source" to it.source, "type" to it.type, "score" to it.score, "severity" to it.severity, "vector" to it.vector) }

    /** Mismo resumen que summarize() de tests/nvd-core.test.mjs. */
    private fun summarize(name: String, json: JSONObject): Map<String, Any?> {
        if (name.startsWith("cpe")) {
            val result = Nvd.parseCpeResponse(json)
            return mapOf("page" to page(result.page), "skipped" to result.skipped, "products" to result.items.map {
                mapOf("cpeName" to it.cpeName, "title" to it.title, "vendor" to it.vendor, "product" to it.product, "version" to it.version,
                    "deprecated" to it.deprecated, "deprecatedBy" to it.deprecatedBy, "lastModified" to it.lastModified)
            })
        }
        val result = Nvd.parseCveResponse(json)
        if (name.startsWith("keyword")) return mapOf("page" to page(result.page), "skipped" to result.skipped, "ids" to result.items.map { it.id })
        return mapOf("page" to page(result.page), "skipped" to result.skipped, "items" to result.items.map { item ->
            mapOf(
                "id" to item.id, "status" to item.status, "rejected" to item.rejected, "published" to item.published, "lastModified" to item.lastModified,
                "description" to item.description, "primaryMetric" to metric(item.primaryMetric), "metricVersions" to item.metrics.map { it.version },
                "metricSources" to item.metrics.map { it.source }, "v2Severity" to item.metrics.firstOrNull { it.version == "2.0" }?.severity,
                "weaknesses" to item.weaknesses, "tags" to item.tags, "references" to item.references.map { it.url },
                "configurations" to item.configurations.map { c -> mapOf("operator" to c.operator, "nodes" to c.nodes.size, "matches" to c.nodes.sumOf { it.matches.size }) },
                "affected" to item.affected.map { a -> mapOf("vendor" to a.vendor, "product" to a.product, "versions" to a.versions.map { v ->
                    "${v.version}${v.lessThan?.let { " < $it" } ?: ""}${v.lessThanOrEqual?.let { " <= $it" } ?: ""} ${v.status}"
                }) },
                "kev" to item.kev?.added,
            )
        })
    }

    @Test
    fun everySharedResponseIsInterpretedLikeWeb() {
        val expected = fixture.getJSONObject("expected")
        assertTrue(expected.length() >= 10)
        expected.keys().forEach { name -> assertEquals(name, plain(expected.getJSONObject(name)), plain(summarize(name, response(name)))) }
    }

    @Test
    fun unexpectedFormatsAreReportedAsFormatErrors() {
        val names = fixture.getJSONArray("formatErrors")
        for (index in 0 until names.length()) {
            val json = response(names.getString(index))
            assertThrows(NvdFormatException::class.java) { Nvd.parseCveResponse(json) }
            assertThrows(NvdFormatException::class.java) { Nvd.parseCpeResponse(json) }
        }
    }

    @Test
    fun applicabilityMatchesWebForVersionsRangesAndConditions() {
        val cases = fixture.getJSONArray("applicability")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val cve = Nvd.parseCveResponse(response(case.getString("response"))).items.single()
            val result = Nvd.applicabilityFor(cve, case.getString("cpeName"))
            assertEquals(case.getString("name"), case.getString("expected"), result.status)
            assertEquals(case.getString("name"), plain(case.getJSONArray("requirements")), result.requirements)
        }
    }

    @Test
    fun versionComparisonMatchesWeb() {
        val cases = fixture.getJSONArray("versions")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val expected = if (case.isNull("expected")) null else case.getInt("expected")
            assertEquals("${case.getString("a")} frente a ${case.getString("b")}", expected, Nvd.compareVersions(case.getString("a"), case.getString("b")))
        }
    }

    @Test
    fun urlsMatchWebAndInvalidInputNeverBuildsARequest() {
        val cases = fixture.getJSONArray("urls")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val input = case.getString("input")
            val options = case.optJSONObject("options") ?: JSONObject()
            val exact = options.optBoolean("exact")
            val noRejected = options.optBoolean("noRejected")
            val start = options.optInt("startIndex", 0)
            val build: () -> String = when (val kind = case.getString("kind")) {
                "cveById" -> { { Nvd.cveByIdUrl(input) } }
                "cveByKeyword" -> { { Nvd.cveByKeywordUrl(input, exact, noRejected, start) } }
                "cpeSearch" -> { { Nvd.cpeSearchUrl(input, start) } }
                "cveByCpe" -> { { Nvd.cveByCpeUrl(input, noRejected, start) } }
                else -> error("tipo desconocido $kind")
            }
            if (case.isNull("expected")) assertThrows(case.getString("name"), NvdInputException::class.java) { build() }
            else assertEquals(case.getString("name"), case.getString("expected"), build())
        }
    }

    @Test
    fun unscoredRecordsAreNeverPresentedAsLowRisk() {
        val unscored = Nvd.parseCveResponse(response("cve-no-metrics")).items.single()
        assertNull(unscored.primaryMetric)
        assertEquals("Sin puntuación CVSS", Nvd.severityLabel(unscored.primaryMetric?.severity))
        assertEquals("Baja", Nvd.severityLabel("LOW"))
        assertEquals("Pendiente de análisis", Nvd.statusLabel("Awaiting Analysis"))
        val log4j = Nvd.parseCveResponse(response("cve-log4j")).items.single()
        assertEquals(listOf("3.1", "CRITICAL", 10.0), log4j.primaryMetric!!.let { listOf(it.version, it.severity, it.score) })
        assertTrue(log4j.references.none { it.url.startsWith("javascript") })
        assertEquals("This product uses the NVD API but is not endorsed or certified by the NVD.", Nvd.NOTICE)
    }
}

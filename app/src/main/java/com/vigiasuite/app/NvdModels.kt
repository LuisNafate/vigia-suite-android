package com.vigiasuite.app

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Lógica pura de la consulta a NVD (CVE API 2.0 y CPE API 2.0): validación, URL, normalización y aplicabilidad de un
 * CPE. Equivale a vigia-suite-web/pc-lab/nvd-core.js; nvd-cases.json (copia idéntica en los dos repositorios) lo
 * comprueba. Las fechas de NVD están en UTC; cveId está obsoleto en favor de cveIds; keywordExactMatch, noRejected e
 * isVulnerable se envían sin valor; CVSS v2 lleva baseSeverity fuera de cvssData y v3.x/v4.0 dentro.
 */
object Nvd {
    const val BASE = "https://services.nvd.nist.gov/rest/json"
    const val CVE_ENDPOINT = "$BASE/cves/2.0"
    const val CPE_ENDPOINT = "$BASE/cpes/2.0"
    const val PAGE_SIZE = 20
    const val MAX_KEYWORD_LENGTH = 120
    /** Aviso que los Términos de uso de la API piden mostrar de forma visible. */
    const val NOTICE = "This product uses the NVD API but is not endorsed or certified by the NVD."

    private val CVE_ID = Regex("^CVE-\\d{4}-\\d{4,19}$")
    // Una MAC o un BSSID no identifican un producto ni su versión: no se envían a NVD.
    private val HARDWARE_ADDRESS = Regex("(^|[^0-9a-f])[0-9a-f]{2}([:-][0-9a-f]{2}){5}([^0-9a-f]|$)", RegexOption.IGNORE_CASE)
    private val SEVERITIES = setOf("NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL")
    private val METRIC_KEYS = listOf("cvssMetricV40" to "4.0", "cvssMetricV31" to "3.1", "cvssMetricV30" to "3.0", "cvssMetricV2" to "2.0")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?(Z|[+-]\\d{2}:\\d{2})?$")
    private val ZONE = Regex("(Z|[+-]\\d{2}:\\d{2})$")
    private val UNRESERVED = Regex("[A-Za-z0-9\\-_.~:]")

    fun encodeQueryValue(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
        val char = (byte.toInt() and 0xFF).toChar()
        if (byte >= 0 && UNRESERVED.matches(char.toString())) char.toString() else "%%%02X".format(byte.toInt() and 0xFF)
    }

    fun normalizeCveId(text: String?): String? = text?.trim()?.uppercase()?.takeIf { CVE_ID.matches(it) }

    fun cleanKeyword(text: String?): String {
        val value = (text ?: "").trim().replace(Regex("(?U)\\s+"), " ")
        if (value.isEmpty()) throw NvdInputException("Escribe al menos una palabra.")
        if (value.length > MAX_KEYWORD_LENGTH) throw NvdInputException("La búsqueda admite hasta $MAX_KEYWORD_LENGTH caracteres.")
        if (HARDWARE_ADDRESS.containsMatchIn(value)) throw NvdInputException("No se envían direcciones MAC, BSSID ni direcciones BLE a NVD: no identifican un producto ni su versión.")
        return value
    }

    /** Los 13 campos de un nombre CPE 2.3 respetando «\:», o null si no es válido. */
    fun splitCpe(name: String?): List<String>? {
        if (name == null) return null
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < name.length) {
            val char = name[index]
            if (char == '\\' && index + 1 < name.length) { current.append(char).append(name[index + 1]); index += 2; continue }
            if (char == ':') { parts += current.toString(); current.clear() } else current.append(char)
            index += 1
        }
        parts += current.toString()
        if (parts.size != 13 || !parts[0].equals("cpe", ignoreCase = true) || parts[1] != "2.3" || !Regex("^[aoh*-]$", RegexOption.IGNORE_CASE).matches(parts[2])) return null
        if (parts.any { it.isEmpty() }) return null
        return parts
    }

    fun isCpeName(name: String?) = splitCpe(name) != null

    private fun page(startIndex: Int) = "resultsPerPage=$PAGE_SIZE&startIndex=${startIndex.coerceAtLeast(0)}"

    fun cveByIdUrl(text: String?): String {
        val id = normalizeCveId(text) ?: throw NvdInputException("El identificador debe tener la forma CVE-AAAA-NNNN (por ejemplo, CVE-2021-44228).")
        return "$CVE_ENDPOINT?cveIds=$id"
    }

    fun cveByKeywordUrl(text: String?, exact: Boolean = false, noRejected: Boolean = false, startIndex: Int = 0): String =
        "$CVE_ENDPOINT?keywordSearch=${encodeQueryValue(cleanKeyword(text))}${if (exact) "&keywordExactMatch" else ""}${if (noRejected) "&noRejected" else ""}&${page(startIndex)}"

    fun cpeSearchUrl(text: String?, startIndex: Int = 0): String = "$CPE_ENDPOINT?keywordSearch=${encodeQueryValue(cleanKeyword(text))}&${page(startIndex)}"

    /** CVE en las que el CPE elegido figura como vulnerable (isVulnerable), con los rangos de versión de NVD. */
    fun cveByCpeUrl(cpeName: String?, noRejected: Boolean = false, startIndex: Int = 0): String {
        if (!isCpeName(cpeName)) throw NvdInputException("Selecciona un producto de la lista de CPE.")
        return "$CVE_ENDPOINT?cpeName=${encodeQueryValue(cpeName!!)}&isVulnerable${if (noRejected) "&noRejected" else ""}&${page(startIndex)}"
    }

    private fun JSONObject?.text(key: String): String? = (this?.opt(key) as? String)?.trim()?.ifEmpty { null }
    private fun JSONObject?.array(key: String): List<Any?> = (this?.opt(key) as? JSONArray)?.let { array -> (0 until array.length()).map { array.opt(it) } } ?: emptyList()
    private fun JSONObject?.objects(key: String): List<JSONObject> = array(key).filterIsInstance<JSONObject>()
    private fun JSONObject?.int(key: String): Int? = (this?.opt(key) as? Number)?.takeIf { it is Int || it is Long }?.toLong()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()

    /** Fecha de NVD (UTC sin zona) en milisegundos, o null. */
    fun parseDate(value: String?): Long? {
        val raw = value?.trim()?.ifEmpty { null } ?: return null
        if (!DATE.matches(raw)) return null
        return runCatching {
            if (ZONE.containsMatchIn(raw)) OffsetDateTime.parse(raw).toInstant().toEpochMilli()
            else LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private fun metric(raw: JSONObject, keyVersion: String): CvssMetric? {
        val data = raw.optJSONObject("cvssData")
        val score = (data?.opt("baseScore") as? Number)?.toDouble()?.takeIf { it in 0.0..10.0 }
        val severityRaw = if (keyVersion == "2.0") raw.opt("baseSeverity") else data?.opt("baseSeverity")
        val severity = (severityRaw as? String)?.uppercase()?.takeIf { it in SEVERITIES }
        if (score == null && severity == null) return null
        val type = (raw.opt("type") as? String)?.takeIf { it == "Primary" || it == "Secondary" }
        return CvssMetric(data.text("version") ?: keyVersion, raw.text("source"), type, score, severity, data.text("vectorString"))
    }

    /** Métricas de la versión de CVSS más reciente a la más antigua; con la misma versión, Primary primero. */
    fun metrics(raw: JSONObject?): List<CvssMetric> = METRIC_KEYS.flatMap { (key, version) ->
        raw.objects(key).mapNotNull { metric(it, version) }.sortedBy { if (it.type == "Primary") 0 else 1 }
    }

    private fun match(raw: JSONObject): CpeMatch? {
        val criteria = raw.text("criteria")?.takeIf(::isCpeName) ?: return null
        return CpeMatch(criteria, raw.opt("vulnerable") as? Boolean, raw.text("versionStartIncluding"), raw.text("versionStartExcluding"),
            raw.text("versionEndIncluding"), raw.text("versionEndExcluding"))
    }

    private fun operator(value: Any?) = if (value == "AND") "AND" else "OR"

    private fun configuration(raw: JSONObject): NvdConfiguration? {
        val nodes = raw.objects("nodes").map { node ->
            ConfigurationNode(operator(node.opt("operator")), node.opt("negate") == true, node.objects("cpeMatch").mapNotNull(::match))
        }.filter { it.matches.isNotEmpty() }
        return if (nodes.isEmpty()) null else NvdConfiguration(operator(raw.opt("operator")), raw.opt("negate") == true, nodes)
    }

    /** URL de una referencia tal como la publica NVD, sólo si es http(s) absoluta, con servidor y sin espacios. */
    fun safeUrl(value: String?): String? {
        val raw = value?.trim()?.ifEmpty { null } ?: return null
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(raw) || raw.any { it.isWhitespace() }) return null
        return raw.takeIf { runCatching { URL(raw).host }.getOrNull()?.isNotEmpty() == true }
    }

    private fun affected(raw: JSONObject): List<AffectedProduct> = raw.objects("affected").flatMap { entry ->
        entry.objects("affectedData").map { item ->
            AffectedProduct(entry.text("source"), item.text("vendor"), item.text("product"), item.objects("versions").map {
                AffectedVersion(it.text("version"), it.text("status"), it.text("lessThan"), it.text("lessThanOrEqual"))
            }.filter { it.version != null || it.lessThan != null || it.lessThanOrEqual != null })
        }
    }.filter { it.vendor != null || it.product != null }

    /** Registro CVE normalizado, o null si no trae un identificador válido. Nada se completa por suposición. */
    fun cve(raw: JSONObject?): CveRecord? {
        if (raw == null) return null
        val rawId = raw.opt("id") as? String ?: return null
        val id = normalizeCveId(rawId)?.takeIf { it == rawId.trim() } ?: return null
        val descriptions = raw.objects("descriptions").filter { it.text("value") != null }
        val status = raw.text("vulnStatus")
        val metrics = metrics(raw.optJSONObject("metrics"))
        val references = LinkedHashMap<String, NvdReference>()
        raw.objects("references").forEach { item ->
            val url = safeUrl(item.opt("url") as? String) ?: return@forEach
            references.putIfAbsent(url, NvdReference(url, item.text("source"), item.array("tags").filterIsInstance<String>()))
        }
        return CveRecord(
            id = id,
            status = status,
            rejected = status != null && status.startsWith("reject", ignoreCase = true),
            published = parseDate(raw.opt("published") as? String),
            lastModified = parseDate(raw.opt("lastModified") as? String),
            description = (descriptions.firstOrNull { it.opt("lang") == "en" } ?: descriptions.firstOrNull()).text("value"),
            metrics = metrics,
            weaknesses = raw.objects("weaknesses").flatMap { it.objects("description") }.mapNotNull { it.text("value") }.distinct(),
            tags = raw.objects("cveTags").flatMap { it.array("tags") }.filterIsInstance<String>().distinct(),
            references = references.values.toList(),
            configurations = raw.objects("configurations").mapNotNull(::configuration),
            affected = affected(raw),
            kev = raw.text("cisaExploitAdd")?.let { KevEntry(it, raw.text("cisaActionDue"), raw.text("cisaRequiredAction"), raw.text("cisaVulnerabilityName")) },
        )
    }

    private fun page(json: JSONObject): NvdPage {
        val total = json.int("totalResults")
        val start = json.int("startIndex") ?: 0
        val perPage = json.int("resultsPerPage") ?: 0
        val next = start + perPage
        return NvdPage(total, start, perPage, if (total != null && perPage > 0 && next < total) next else null)
    }

    fun parseCveResponse(json: JSONObject): NvdResult<CveRecord> {
        val list = json.opt("vulnerabilities") as? JSONArray ?: throw NvdFormatException("La respuesta de NVD no tiene el formato de la CVE API 2.0.")
        val items = (0 until list.length()).map { cve((list.opt(it) as? JSONObject)?.optJSONObject("cve")) }
        return NvdResult(page(json), items.filterNotNull(), items.count { it == null }, parseDate(json.opt("timestamp") as? String))
    }

    fun parseCpeResponse(json: JSONObject): NvdResult<CpeProduct> {
        val list = json.opt("products") as? JSONArray ?: throw NvdFormatException("La respuesta de NVD no tiene el formato de la CPE API 2.0.")
        val items = (0 until list.length()).map { index ->
            val cpe = (list.opt(index) as? JSONObject)?.optJSONObject("cpe")
            val name = cpe?.opt("cpeName") as? String
            val parts = splitCpe(name)
            if (cpe == null || parts == null) null else {
                val titles = cpe.objects("titles").filter { it.text("title") != null }
                CpeProduct(name!!, cpe.text("cpeNameId"), (titles.firstOrNull { it.opt("lang") == "en" } ?: titles.firstOrNull()).text("title"),
                    parts[3], parts[4], parts[5], cpe.opt("deprecated") == true,
                    cpe.objects("deprecatedBy").mapNotNull { it.text("cpeName") }.filter(::isCpeName), parseDate(cpe.opt("lastModified") as? String))
            }
        }
        return NvdResult(page(json), items.filterNotNull(), items.count { it == null }, parseDate(json.opt("timestamp") as? String))
    }

    private val NUMERIC = Regex("^\\d+$")

    /** -1, 0, 1 o null si no se pueden ordenar con certeza (ver compareVersions de la versión Web). */
    fun compareVersions(a: String?, b: String?): Int? {
        val left = a?.trim()?.ifEmpty { null } ?: return null
        val right = b?.trim()?.ifEmpty { null } ?: return null
        if (left.equals(right, ignoreCase = true)) return 0
        val x = left.split(Regex("[._-]"))
        val y = right.split(Regex("[._-]"))
        if (!(x + y).all { NUMERIC.matches(it) }) return null
        for (index in 0 until maxOf(x.size, y.size)) {
            val difference = BigInteger(x.getOrElse(index) { "0" }).compareTo(BigInteger(y.getOrElse(index) { "0" }))
            if (difference != 0) return if (difference < 0) -1 else 1
        }
        return 0
    }

    private val WILDCARD = Regex("(^|[^\\\\])[*?]")
    private fun hasWildcard(value: String) = value != "*" && WILDCARD.containsMatchIn(value)
    private fun unquote(value: String) = value.replace(Regex("\\\\(.)"), "$1").lowercase()

    private fun componentMatches(criteria: String, name: String): Boolean? {
        if (criteria == "*") return true
        if (hasWildcard(criteria)) return null
        if (criteria == "-") return if (name == "-") true else if (name == "*") null else false
        if (name == "*" || name == "-" || hasWildcard(name)) return if (name == "-") false else null
        return unquote(criteria) == unquote(name)
    }

    /** Si un cpeMatch de NVD incluye el producto elegido: true, false o null (no se puede comprobar). */
    fun matchApplies(match: CpeMatch, cpeName: String): Boolean? {
        val criteria = splitCpe(match.criteria) ?: return null
        val name = splitCpe(cpeName) ?: return null
        var unknown = false
        for (index in listOf(2, 3, 4, 6, 7, 8, 9, 10, 11, 12)) {
            when (componentMatches(criteria[index], name[index])) { false -> return false; null -> unknown = true; true -> {} }
        }
        val version = name[5]
        if (!match.hasRange) {
            val result = componentMatches(criteria[5], version)
            if (result == false) return false
            return if (result == null || unknown) null else true
        }
        if (version == "*" || version == "-" || hasWildcard(version)) return null
        val checks = listOf<Pair<String?, (Int) -> Boolean>>(
            match.versionStartIncluding to { it >= 0 }, match.versionStartExcluding to { it > 0 },
            match.versionEndIncluding to { it <= 0 }, match.versionEndExcluding to { it < 0 },
        )
        for ((bound, accept) in checks) {
            if (bound == null) continue
            val order = compareVersions(version, bound)
            if (order == null) { unknown = true; continue }
            if (!accept(order)) return false
        }
        return if (unknown) null else true
    }

    fun rangeLabel(match: CpeMatch): String {
        val parts = listOfNotNull(
            match.versionStartIncluding?.let { "desde $it (incluida)" }, match.versionStartExcluding?.let { "posteriores a $it" },
            match.versionEndIncluding?.let { "hasta $it (incluida)" }, match.versionEndExcluding?.let { "anteriores a $it" },
        )
        if (parts.isNotEmpty()) return parts.joinToString(", ")
        return when (val version = splitCpe(match.criteria)?.get(5)) { "*" -> "todas las versiones"; "-" -> "sin versión aplicable"; else -> "versión ${unquote(version ?: "")}" }
    }

    fun productLabel(cpeName: String): String = splitCpe(cpeName)?.let { "${unquote(it[3])} ${unquote(it[4])}".replace('_', ' ') } ?: cpeName

    /** Misma clasificación que applicabilityFor() de la versión Web. */
    fun applicabilityFor(cve: CveRecord, cpeName: String): Applicability {
        if (cve.configurations.isEmpty()) return Applicability(Applicability.NO_CONFIGURATIONS, emptyList())
        val outcomes = cve.configurations.map { configuration ->
            var direct = false
            var uncertain = false
            val others = mutableListOf<ConfigurationNode>()
            configuration.nodes.forEach { node ->
                val results = node.matches.filter { it.vulnerable == true }.map { matchApplies(it, cpeName) }
                if (!node.negate && true in results) direct = true
                else {
                    if (!node.negate && null in results) uncertain = true
                    others += node
                }
            }
            when {
                !direct -> Applicability(if (uncertain) Applicability.UNCERTAIN else Applicability.NOT_MATCHED, emptyList())
                configuration.operator != "AND" || others.isEmpty() -> Applicability(Applicability.APPLIES, emptyList())
                else -> Applicability(Applicability.CONDITIONAL, others.map { node ->
                    (if (node.negate) "no " else "") + node.matches.joinToString(if (node.operator == "AND") " y " else " o ") { "${productLabel(it.criteria)} (${rangeLabel(it)})" }
                })
            }
        }
        for (status in listOf(Applicability.APPLIES, Applicability.CONDITIONAL, Applicability.UNCERTAIN)) {
            val found = outcomes.filter { it.status == status }
            if (found.isNotEmpty()) return Applicability(status, found.flatMap { it.requirements }.distinct())
        }
        return Applicability(Applicability.NOT_MATCHED, emptyList())
    }

    private val SEVERITY_LABELS = mapOf("CRITICAL" to "Crítica", "HIGH" to "Alta", "MEDIUM" to "Media", "LOW" to "Baja", "NONE" to "Ninguna (CVSS 0)")
    /** Una CVE sin métrica nunca se presenta como baja. */
    fun severityLabel(severity: String?) = SEVERITY_LABELS[severity] ?: "Sin puntuación CVSS"

    private val STATUS_LABELS = mapOf(
        "Received" to "Recibida", "Awaiting Analysis" to "Pendiente de análisis", "Undergoing Analysis" to "En análisis", "Analyzed" to "Analizada",
        "Modified" to "Modificada", "Deferred" to "Diferida", "Rejected" to "Rechazada",
    )
    fun statusLabel(status: String?) = if (status == null) "Estado no informado" else STATUS_LABELS[status] ?: status

    val APPLICABILITY_LABELS = mapOf(
        Applicability.APPLIES to "Afecta al producto elegido según NVD",
        Applicability.CONDITIONAL to "Afecta sólo si se cumplen otras condiciones",
        Applicability.UNCERTAIN to "No se pudo comprobar la versión",
        Applicability.NOT_MATCHED to "NVD la asocia, pero ningún criterio vulnerable incluye el producto elegido",
        Applicability.NO_CONFIGURATIONS to "NVD aún no publicó productos afectados",
    )
}

class NvdInputException(message: String) : IllegalArgumentException(message)
class NvdFormatException(message: String) : IllegalStateException(message)

data class CvssMetric(val version: String, val source: String?, val type: String?, val score: Double?, val severity: String?, val vector: String?)
data class CpeMatch(
    val criteria: String, val vulnerable: Boolean?, val versionStartIncluding: String?, val versionStartExcluding: String?,
    val versionEndIncluding: String?, val versionEndExcluding: String?,
) {
    val hasRange get() = listOf(versionStartIncluding, versionStartExcluding, versionEndIncluding, versionEndExcluding).any { it != null }
}
data class ConfigurationNode(val operator: String, val negate: Boolean, val matches: List<CpeMatch>)
data class NvdConfiguration(val operator: String, val negate: Boolean, val nodes: List<ConfigurationNode>)
data class NvdReference(val url: String, val source: String?, val tags: List<String>)
data class AffectedVersion(val version: String?, val status: String?, val lessThan: String?, val lessThanOrEqual: String?)
data class AffectedProduct(val source: String?, val vendor: String?, val product: String?, val versions: List<AffectedVersion>)
data class KevEntry(val added: String, val dueDate: String?, val action: String?, val name: String?)
data class CveRecord(
    val id: String, val status: String?, val rejected: Boolean, val published: Long?, val lastModified: Long?, val description: String?,
    val metrics: List<CvssMetric>, val weaknesses: List<String>, val tags: List<String>, val references: List<NvdReference>,
    val configurations: List<NvdConfiguration>, val affected: List<AffectedProduct>, val kev: KevEntry?,
) {
    val primaryMetric: CvssMetric? get() = metrics.firstOrNull()
}
data class CpeProduct(
    val cpeName: String, val cpeNameId: String?, val title: String?, val vendor: String, val product: String, val version: String,
    val deprecated: Boolean, val deprecatedBy: List<String>, val lastModified: Long?,
)
data class NvdPage(val totalResults: Int?, val startIndex: Int, val resultsPerPage: Int, val nextStartIndex: Int?)
data class NvdResult<T>(val page: NvdPage, val items: List<T>, val skipped: Int, val timestamp: Long?)
data class Applicability(val status: String, val requirements: List<String>) {
    companion object {
        const val APPLIES = "applies"
        const val CONDITIONAL = "conditional"
        const val UNCERTAIN = "uncertain"
        const val NOT_MATCHED = "not_matched"
        const val NO_CONFIGURATIONS = "no_configurations"
    }
}

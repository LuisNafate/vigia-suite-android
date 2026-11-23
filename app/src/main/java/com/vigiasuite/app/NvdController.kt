package com.vigiasuite.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NvdMode { CVE, KEYWORD, CPE }

data class CveEntry(val record: CveRecord, val applicability: Applicability?)

data class NvdUiState(
    val mode: NvdMode = NvdMode.CVE,
    val loading: Boolean = false,
    val status: String = "Sin consultas todavía.",
    val error: Boolean = false,
    val fetchedAt: Long? = null,
    val fromCache: Boolean = false,
    val offline: Boolean = false,
    val showCpes: Boolean = false,
    val cpes: List<CpeProduct> = emptyList(),
    val cpeTotal: Int? = null,
    val cpeNext: Int? = null,
    val selectedCpe: CpeProduct? = null,
    val showCves: Boolean = false,
    val cves: List<CveEntry> = emptyList(),
    val cveTotal: Int? = null,
    val cveNext: Int? = null,
    val detailId: String? = null,
) {
    val detail: CveEntry? get() = cves.firstOrNull { it.record.id == detailId }
    val canRefresh: Boolean get() = !loading && (showCves || showCpes)
}

/**
 * Estado de la consulta NVD de Android. Sólo envía lo que la persona escribe o elige: no lee el inventario del sensor
 * ni el historial. La interpretación de los datos es la de Nvd (NvdModels.kt) y la red la de NvdClient.
 */
class NvdController(
    clientFactory: ((String, Long) -> Unit) -> NvdClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _state = MutableStateFlow(NvdUiState())
    val state: StateFlow<NvdUiState> = _state.asStateFlow()
    private val client = clientFactory { reason, ms -> _state.update { it.copy(status = waitMessage(reason, ms), error = false) } }
    private var urlFor: ((Int) -> String)? = null
    private var cvePages = mutableListOf<Int>()
    private var cpeQuery: String? = null
    private var job: Job? = null

    fun setMode(mode: NvdMode) = _state.update { it.copy(mode = mode) }

    private fun run(block: suspend () -> Unit): Job? {
        if (_state.value.loading) return null
        _state.update { it.copy(loading = true, error = false, status = "Consultando NVD…") }
        return scope.launch {
            try { block() } catch (error: NvdInputException) { fail(error.message) } catch (error: NvdRequestException) { fail(error.message) } catch (error: NvdFormatException) { fail(error.message) }
            finally { _state.update { it.copy(loading = false) } }
        }.also { job = it }
    }

    private fun fail(message: String?) = _state.update { it.copy(status = message ?: "No se pudo completar la consulta.", error = true) }

    private fun noted(response: NvdResponse) = _state.update { it.copy(fetchedAt = response.fetchedAt, fromCache = response.fromCache, offline = response.offline) }

    private suspend fun loadCves(startIndex: Int, force: Boolean = false): NvdResult<CveRecord> {
        val response = client.get(urlFor!!(startIndex), force)
        noted(response)
        val parsed = Nvd.parseCveResponse(response.json)
        val cpe = _state.value.selectedCpe
        _state.update { current ->
            val base = if (startIndex == 0) emptyList() else current.cves
            val known = base.map { it.record.id }.toSet()
            val added = parsed.items.filter { it.id !in known }.map { CveEntry(it, cpe?.let { selected -> Nvd.applicabilityFor(it, selected.cpeName) }) }
            val cves = base + added
            val skipped = if (parsed.skipped > 0) " ${parsed.skipped} registro(s) sin identificador válido se omitieron." else ""
            current.copy(showCves = true, cves = cves, cveTotal = parsed.page.totalResults, cveNext = parsed.page.nextStartIndex,
                status = if (cves.isEmpty()) "Sin resultados en NVD para esta consulta.$skipped" else "${cves.size} vulnerabilidad(es) cargada(s).$skipped")
        }
        if (startIndex == 0) cvePages = mutableListOf(0) else cvePages += startIndex
        return parsed
    }

    private suspend fun loadCpes(startIndex: Int, force: Boolean = false) {
        val response = client.get(Nvd.cpeSearchUrl(cpeQuery, startIndex), force)
        noted(response)
        val parsed = Nvd.parseCpeResponse(response.json)
        _state.update { current ->
            val base = if (startIndex == 0) emptyList() else current.cpes
            val cpes = base + parsed.items.filter { item -> base.none { it.cpeName == item.cpeName } }
            current.copy(showCpes = true, cpes = cpes, cpeTotal = parsed.page.totalResults, cpeNext = parsed.page.nextStartIndex,
                status = if (cpes.isEmpty()) "NVD no encontró productos con esas palabras." else "Elige el producto y la versión exactos para ver sus CVE.")
        }
    }

    private fun clearResults() {
        urlFor = null
        cvePages = mutableListOf()
        _state.update { it.copy(showCves = false, cves = emptyList(), cveTotal = null, cveNext = null, selectedCpe = null, detailId = null) }
    }

    fun search(text: String, exact: Boolean = false, noRejected: Boolean = false): Job? {
        val mode = _state.value.mode
        // Se valida antes de borrar los resultados anteriores: un texto inválido no llega a NVD.
        try {
            when (mode) {
                NvdMode.CVE -> Nvd.cveByIdUrl(text)
                NvdMode.KEYWORD -> Nvd.cveByKeywordUrl(text, exact, noRejected)
                NvdMode.CPE -> Nvd.cpeSearchUrl(text)
            }
        } catch (error: NvdInputException) { fail(error.message); return null }
        if (_state.value.loading) return null
        clearResults()
        if (mode == NvdMode.CPE) {
            cpeQuery = text
            return run { loadCpes(0) }
        }
        cpeQuery = null
        _state.update { it.copy(showCpes = false, cpes = emptyList(), cpeTotal = null, cpeNext = null) }
        urlFor = if (mode == NvdMode.CVE) { _ -> Nvd.cveByIdUrl(text) } else { start -> Nvd.cveByKeywordUrl(text, exact, noRejected, start) }
        return run {
            val parsed = loadCves(0)
            if (mode == NvdMode.CVE) {
                if (parsed.items.size == 1) _state.update { it.copy(detailId = parsed.items.single().id) }
                if (parsed.items.isEmpty()) _state.update { it.copy(status = "NVD no tiene un registro con el identificador ${Nvd.normalizeCveId(text)}.") }
            }
        }
    }

    fun selectCpe(index: Int, noRejected: Boolean = false): Job? {
        val cpe = _state.value.cpes.getOrNull(index) ?: return null
        if (_state.value.loading) return null
        urlFor = { start -> Nvd.cveByCpeUrl(cpe.cpeName, noRejected, start) }
        _state.update { it.copy(selectedCpe = cpe, detailId = null, cves = emptyList(), showCves = false) }
        return run { loadCves(0) }
    }

    fun loadMore(): Job? = _state.value.cveNext?.let { next -> if (urlFor == null) null else run { loadCves(next) } }
    fun loadMoreCpes(): Job? = _state.value.cpeNext?.let { next -> if (cpeQuery == null) null else run { loadCpes(next) } }

    /** Vuelve a consultar NVD aunque haya copia guardada, página por página. */
    fun refresh(): Job? = when {
        urlFor != null -> { val pages = cvePages.ifEmpty { mutableListOf(0) }.toList(); run { pages.forEach { loadCves(it, force = true) } } }
        cpeQuery != null -> run { loadCpes(0, force = true) }
        else -> null
    }

    fun openDetail(id: String) = _state.update { it.copy(detailId = id) }
    fun closeDetail() = _state.update { it.copy(detailId = null) }

    companion object {
        fun waitMessage(reason: String, ms: Long): String {
            val seconds = (ms + 999) / 1000
            return when (reason) {
                NvdClient.WAIT_INTERVAL -> "Esperando $seconds s para respetar el límite de frecuencia de NVD…"
                "rate_limited" -> "NVD limitó la frecuencia (HTTP 429). Se reintentará en $seconds s…"
                else -> "NVD no está disponible (HTTP 503). Se reintentará en $seconds s…"
            }
        }

        fun create(context: Context): NvdController {
            val store = RoomNvdStore(NvdCacheDatabase.open(context).cacheDao())
            return NvdController({ onWait -> NvdClient(store = store, onWait = onWait) })
        }
    }
}

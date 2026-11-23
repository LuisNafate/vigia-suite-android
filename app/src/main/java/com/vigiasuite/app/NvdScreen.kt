package com.vigiasuite.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

private val NvdDanger = Color(0xFF9C3131)

internal fun nvdDate(ms: Long?): String = ms?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "No disponible"
private fun nvdDateTime(ms: Long?): String = ms?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "No disponible"

/** «Alta · 7.5 · CVSS 3.1» o «Sin puntuación CVSS»: una CVE sin métrica nunca se presenta como baja. */
internal fun nvdMetricText(metric: CvssMetric?): String {
    if (metric == null) return "Sin puntuación CVSS"
    val severity = metric.severity?.let(Nvd::severityLabel) ?: "Sin severidad"
    val score = metric.score?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "sin valor"
    return "$severity · $score · CVSS ${metric.version}"
}

internal fun nvdMetricSource(metric: CvssMetric): String =
    "${metric.source ?: "fuente no informada"} · ${when (metric.type) { "Primary" -> "principal (NVD)"; "Secondary" -> "secundaria (CNA)"; else -> "tipo no informado" }}"

internal fun nvdFetchedText(state: NvdUiState): String? {
    val at = state.fetchedAt ?: return null
    val when_ = nvdDateTime(at)
    return when {
        state.offline -> "Sin conexión con NVD: se muestran datos guardados el $when_. Usa «Actualizar» cuando vuelva la red."
        state.fromCache -> "Datos guardados en este teléfono, consultados el $when_."
        else -> "Consultado en NVD el $when_."
    }
}

internal fun nvdVersionLabel(version: String) = when (version) { "*" -> "cualquier versión"; "-" -> "sin versión"; else -> "versión $version" }

/** Sección «Vulnerabilidades»: consulta manual de NVD. No usa el inventario del sensor. */
@Composable
internal fun NvdSection(controller: NvdController) {
    val state by controller.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var exact by rememberSaveable { mutableStateOf(false) }
    var noRejected by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current

    NvdCard {
        Text("Vulnerabilidades documentadas", fontWeight = FontWeight.Bold)
        Text("CVE publicadas por NVD. Son vulnerabilidades documentadas, no hallazgos del sensor.", color = Muted, fontSize = 12.sp)
        Text(Nvd.NOTICE, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text("Sólo se envía lo que escribes o eliges, nunca el inventario del sensor.", color = Muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(NvdMode.CVE to "CVE", NvdMode.KEYWORD to "Palabras clave", NvdMode.CPE to "Producto").forEach { (mode, label) ->
                FilterChip(selected = state.mode == mode, onClick = { controller.setMode(mode) }, label = { Text(label, fontSize = 11.sp) })
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it.take(Nvd.MAX_KEYWORD_LENGTH) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(when (state.mode) { NvdMode.CVE -> "Identificador CVE"; NvdMode.KEYWORD -> "Palabras clave"; NvdMode.CPE -> "Producto o fabricante" }) },
            placeholder = { Text(when (state.mode) { NvdMode.CVE -> "CVE-2021-44228"; NvdMode.KEYWORD -> "apache log4j"; NvdMode.CPE -> "openssl 1.1.1" }) },
        )
        if (state.mode == NvdMode.KEYWORD) CheckRow("Frase exacta", exact) { exact = it }
        if (state.mode != NvdMode.CVE) CheckRow("Excluir CVE rechazadas", noRejected) { noRejected = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { controller.search(query, exact, noRejected) }, enabled = !state.loading) { Text("Consultar NVD") }
            TextButton(onClick = { controller.refresh() }, enabled = state.canRefresh) { Text("Actualizar") }
        }
        Text(state.status, fontSize = 12.sp, color = if (state.error) NvdDanger else Color(0xFF191919))
        nvdFetchedText(state)?.let { Text(it, color = Muted, fontSize = 11.sp) }
        Text("Máximo 5 consultas cada 30 s; las guardadas duran 24 h.", color = Muted, fontSize = 10.sp)
    }

    if (state.showCpes) {
        ResultCard("Productos del diccionario CPE", state.cpes.size) {
            Text("Elige la entrada exacta de tu producto. ${state.cpeTotal?.let { "NVD tiene $it coincidencias." } ?: ""}", color = Muted, fontSize = 11.sp)
            if (state.cpes.isEmpty()) EmptyResult("NVD no encontró productos con esas palabras.")
            state.cpes.forEachIndexed { index, cpe ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cpe.title ?: Nvd.productLabel(cpe.cpeName), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("${nvdVersionLabel(cpe.version)}${if (cpe.deprecated) " · entrada obsoleta" else ""}", color = Muted, fontSize = 11.sp)
                        Text(cpe.cpeName, color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    TextButton(onClick = { controller.selectCpe(index, noRejected) }, enabled = !state.loading) { Text("Ver CVE") }
                }
            }
            if (state.cpeNext != null) TextButton(onClick = { controller.loadMoreCpes() }, enabled = !state.loading) { Text("Cargar más productos") }
        }
    }

    if (state.showCves) {
        ResultCard("Vulnerabilidades", state.cves.size) {
            state.selectedCpe?.let { Text("Producto elegido: ${it.title ?: it.cpeName}. CVE en las que NVD lo marca como vulnerable.", color = Muted, fontSize = 11.sp) }
            state.cveTotal?.let { Text("${state.cves.size} cargadas de $it en NVD.", color = Muted, fontSize = 11.sp) }
            if (state.cves.isEmpty()) EmptyResult("NVD no devolvió vulnerabilidades para esta consulta.")
            state.cves.forEach { entry ->
                val record = entry.record
                Column(Modifier.fillMaxWidth().clickable { controller.openDetail(record.id) }.padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(record.id, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(nvdMetricText(record.primaryMetric), color = if (record.primaryMetric?.severity in setOf("HIGH", "CRITICAL")) NvdDanger else Cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("${Nvd.statusLabel(record.status)} · publicada ${nvdDate(record.published)}${if (record.kev != null) " · catálogo KEV" else ""}", color = Muted, fontSize = 11.sp)
                    entry.applicability?.let { Text(Nvd.APPLICABILITY_LABELS.getValue(it.status), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                    Text(record.description ?: "Descripción no disponible", color = Muted, fontSize = 11.sp, maxLines = 2)
                }
                HorizontalDivider(color = PanelSoft)
            }
            if (state.cveNext != null) TextButton(onClick = { controller.loadMore() }, enabled = !state.loading) { Text("Cargar más resultados") }
        }
    }

    state.detail?.let { entry -> NvdDetail(entry, state, onClose = controller::closeDetail, open = { url -> runCatching { uri.openUri(url) } }) }
}

@Composable
private fun NvdDetail(entry: CveEntry, state: NvdUiState, onClose: () -> Unit, open: (String) -> Unit) {
    val record = entry.record
    NvdCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(record.id, fontWeight = FontWeight.Black)
            TextButton(onClick = onClose) { Text("Cerrar") }
        }
        val flags = listOfNotNull(if (record.rejected) "Rechazada" else null, record.kev?.let { "Catálogo KEV de CISA (añadida ${it.added})" }) + record.tags
        if (flags.isNotEmpty()) Text(flags.joinToString(" · "), color = NvdDanger, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(record.description ?: "NVD no incluye una descripción para este registro.", fontSize = 12.sp)
        Fact("Estado en NVD", Nvd.statusLabel(record.status))
        Fact("Publicada", nvdDateTime(record.published))
        Fact("Última modificación", nvdDateTime(record.lastModified))
        Fact("Puntuación principal", nvdMetricText(record.primaryMetric))
        Fact("Debilidades (CWE)", record.weaknesses.joinToString(", ").ifEmpty { "No informadas" })
        entry.applicability?.let { applicability ->
            HorizontalDivider(color = PanelSoft)
            Text("Aplicabilidad para ${state.selectedCpe?.title ?: state.selectedCpe?.cpeName}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(Nvd.APPLICABILITY_LABELS.getValue(applicability.status), fontSize = 12.sp)
            if (applicability.requirements.isNotEmpty()) Text("Requiere además: ${applicability.requirements.joinToString("; ")}.", color = Muted, fontSize = 11.sp)
            Text("Comprobación local de los rangos y condiciones de NVD; no confirma que tu equipo concreto sea vulnerable.", color = Muted, fontSize = 10.sp)
        }
        HorizontalDivider(color = PanelSoft)
        Text("Puntuaciones CVSS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        if (record.metrics.isEmpty()) Text("Sin métricas CVSS publicadas. No se interpreta como riesgo bajo.", color = Muted, fontSize = 11.sp)
        record.metrics.forEach { metric ->
            Text(nvdMetricText(metric), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(nvdMetricSource(metric) + (metric.vector?.let { " · $it" } ?: ""), color = Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        HorizontalDivider(color = PanelSoft)
        Text("Productos afectados según NVD", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        if (record.configurations.isEmpty()) Text("NVD todavía no publicó configuraciones para esta CVE.", color = Muted, fontSize = 11.sp)
        record.configurations.forEachIndexed { index, configuration ->
            Text("Configuración ${index + 1}: ${if (configuration.operator == "AND") "se deben cumplir todos los grupos" else "basta con cualquier grupo"}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            configuration.nodes.forEach { node ->
                node.matches.forEach { match ->
                    val role = when (match.vulnerable) { true -> "vulnerable"; false -> "plataforma o requisito"; null -> "sin indicar" }
                    Text("${if (node.negate) "no: " else "• "}${Nvd.productLabel(match.criteria)} · ${Nvd.rangeLabel(match)} · $role", fontSize = 11.sp)
                }
            }
        }
        if (record.affected.isNotEmpty()) {
            Text("Productos afectados según la CNA", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            record.affected.forEach { product ->
                val versions = product.versions.joinToString("; ") { v -> listOfNotNull(v.version, v.lessThan?.let { "hasta antes de $it" }, v.lessThanOrEqual?.let { "hasta $it incluida" }, v.status?.let { "($it)" }).joinToString(" ") }
                Text("${listOfNotNull(product.vendor, product.product).joinToString(" · ")}${if (versions.isNotEmpty()) ": $versions" else ""}", fontSize = 11.sp)
            }
        }
        HorizontalDivider(color = PanelSoft)
        Text("Referencias (${record.references.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        if (record.references.isEmpty()) Text("Sin referencias publicadas.", color = Muted, fontSize = 11.sp)
        record.references.forEach { reference ->
            Column(Modifier.fillMaxWidth().clickable { open(reference.url) }.padding(vertical = 2.dp)) {
                Text(reference.url, color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                val meta = listOfNotNull(reference.source).plus(reference.tags).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = Muted, fontSize = 10.sp)
            }
        }
        TextButton(onClick = { open("https://nvd.nist.gov/vuln/detail/${record.id}") }) { Text("Abrir el registro en nvd.nist.gov") }
        nvdFetchedText(state)?.let { Text(it, color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun NvdCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 12.dp))
    }
}

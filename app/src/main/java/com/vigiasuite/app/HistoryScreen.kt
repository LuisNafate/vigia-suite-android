package com.vigiasuite.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val Danger = Color(0xFF9C3131)
private fun dateLabel(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
private fun statusLabel(status: String) = when (status) {
    HistoryStatus.COMPLETE -> "Completa"
    HistoryStatus.PARTIAL -> "Incompleta"
    HistoryStatus.LEGACY -> "Histórica · sólo resumen"
    else -> status
}
private fun ssidLabel(ssid: String?) = when { ssid == null -> "No informado"; ssid.isEmpty() -> "[oculta]"; else -> ssid }
private fun authText(auth: Int?) = auth?.let(::authLabel) ?: "No informado"
/** Texto de una fila de «Cambios de autenticación»: igual que la columna de history.js en Web. */
internal fun authChangeDetail(change: AuthChange) = change.bssid + when (change.protection) {
    AuthProtection.WEAKER -> " · protección más débil"
    AuthProtection.UNKNOWN -> " · cambio sin calificar"
    else -> ""
}
internal fun authChangeValue(change: AuthChange) = "${authText(change.before.auth)} → ${authText(change.after.auth)}"
private fun rssiText(rssi: Int?) = rssi?.let { "$it dBm" } ?: "—"
private fun orUnknown(value: Any?) = value?.toString() ?: "No disponible"

/** Sección Historial: lista, detalle, comparación, privacidad y conservación. */
@Composable
internal fun HistorySection(repository: HistoryRepository, assessments: List<AssessmentEntity>) {
    val message by repository.message.collectAsState()
    var openedId by remember { mutableStateOf<String?>(null) }
    var comparing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<AssessmentEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    // Si una evaluación seleccionada se elimina, deja de contar para la comparación.
    val validSelection = selected.filter { id -> assessments.any { it.id == id } }.toSet()

    BackHandler(enabled = openedId != null || comparing != null) { openedId = null; comparing = null }

    message?.let { Text(it, color = Muted, fontSize = 12.sp) }
    when {
        openedId != null -> DetailCard(repository, openedId!!, onClose = { openedId = null }, onDelete = { pendingDelete = it })
        comparing != null -> ComparisonCard(repository, comparing!!, onClose = { comparing = null })
        else -> {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column { Text("Evaluaciones", fontWeight = FontWeight.Bold); Text("${assessments.size} guardadas en este teléfono", color = Muted, fontSize = 11.sp) }
                        Button(
                            enabled = validSelection.size == 2,
                            onClick = { val (a, b) = validSelection.toList(); comparing = a to b },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Night),
                        ) { Text("Comparar") }
                    }
                    Text("Marca dos evaluaciones para compararlas. La más antigua se usa como referencia.", color = Muted, fontSize = 12.sp)
                    if (assessments.isEmpty()) EmptyResult("Aún no hay evaluaciones. Conecta el sensor y usa «Evaluar mi entorno».")
                    assessments.forEach { item ->
                        HorizontalDivider(color = PanelSoft)
                        val checked = item.id in validSelection
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                enabled = checked || validSelection.size < 2,
                                onCheckedChange = { selected = if (it) validSelection + item.id else validSelection - item.id },
                                colors = CheckboxDefaults.colors(checkedColor = Cyan),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(dateLabel(item.createdAt), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("${item.wifiCount ?: "—"} redes, ${item.wifiOpen ?: "—"} abiertas · ${item.bleCount ?: "—"} BLE", color = Muted, fontSize = 11.sp)
                                Text(flagsText(item), color = if (item.status == HistoryStatus.COMPLETE) Muted else Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${item.score}/100 · ${item.level}", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                TextButton(onClick = { openedId = item.id }) { Text("Ver detalle", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
            PrivacyCard(repository, assessments.size, onClearAll = { confirmClear = true })
        }
    }

    pendingDelete?.let { item ->
        ConfirmDialog(
            title = "Eliminar evaluación",
            text = "Se eliminará la evaluación del ${dateLabel(item.createdAt)} y todas sus observaciones. No se puede deshacer.",
            confirm = "Eliminar",
            onConfirm = { pendingDelete = null; openedId = null; selected = selected - item.id; repository.launch { delete(listOf(item.id)) } },
            onDismiss = { pendingDelete = null },
        )
    }
    if (confirmClear) ConfirmDialog(
        title = "Eliminar todo el historial",
        text = "Se eliminarán las ${assessments.size} evaluaciones guardadas en este teléfono, con todas sus observaciones. No se puede deshacer.",
        confirm = "Eliminar todo",
        onConfirm = { confirmClear = false; selected = emptySet(); repository.launch { deleteAll() } },
        onDismiss = { confirmClear = false },
    )
}

private fun flagsText(item: AssessmentEntity) = buildList {
    add(statusLabel(item.status))
    if (item.wifiTruncated == true) add("Wi-Fi truncado")
    if (item.bleTruncated == true) add("BLE truncado")
    if (item.bleFailed == true) add("BLE falló")
}.joinToString(" · ")

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun DetailCard(repository: HistoryRepository, id: String, onClose: () -> Unit, onDelete: (AssessmentEntity) -> Unit) {
    var detail by remember(id) { mutableStateOf<AssessmentDetail?>(null) }
    var missing by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        val loaded = runCatching { repository.detail(id) }.getOrNull()
        detail = loaded
        missing = loaded == null
    }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Detalle de la evaluación", fontWeight = FontWeight.Bold)
                TextButton(onClick = onClose) { Text("Cerrar") }
            }
            if (missing) EmptyResult("La evaluación ya no existe.")
            val current = detail ?: return@Column
            val item = current.assessment
            fun truncated(value: Boolean?) = when (value) { null -> "No informado"; true -> "Sí, límite del sensor"; false -> "No" }
            Fact("Fecha", dateLabel(item.createdAt))
            Fact("Puntuación", "${item.score}/100 · ${item.level}")
            Fact("Estado", statusLabel(item.status))
            Fact("Observaciones", "${item.wifiCount ?: "—"} Wi-Fi · ${item.bleCount ?: "—"} BLE")
            Fact("Wi-Fi truncado", truncated(item.wifiTruncated))
            Fact("BLE truncado", truncated(item.bleTruncated))
            Fact("Escaneo BLE", when (item.bleFailed) { null -> "No informado"; true -> "Falló"; false -> "Completado" })
            HorizontalDivider(color = PanelSoft)
            Fact("Placa", orUnknown(item.sensorBoard))
            Fact("Chip", orUnknown(item.sensorChip))
            Fact("MAC del sensor", orUnknown(item.sensorMac))
            Fact("Firmware", orUnknown(item.sensorFirmware))
            Fact("Protocolo", item.sensorProtocol?.let { "VIGIA/$it" } ?: "No disponible")
            Button(onClick = { onDelete(item) }, colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Danger)) { Text("Eliminar esta evaluación") }
        }
    }
    val current = detail ?: return
    if (!current.assessment.hasObservations) {
        EmptyResult("Evaluación histórica migrada desde la versión anterior: sólo se guardó el resumen. No existen observaciones individuales y no se reconstruyen.")
        return
    }
    ResultCard(title = "Redes Wi-Fi", count = current.wifi.size) {
        if (current.wifi.isEmpty()) EmptyResult("Sin redes registradas.")
        current.wifi.sortedByDescending { it.rssi ?: -999 }.forEach {
            ResultRow(ssidLabel(it.ssid), "${it.bssid ?: "BSSID no informado"} · Canal ${it.channel ?: "—"} · ${authText(it.auth)}", rssiText(it.rssi))
        }
    }
    ResultCard(title = "Dispositivos Bluetooth LE", count = current.ble.size) {
        if (current.ble.isEmpty()) EmptyResult("Sin dispositivos registrados.")
        current.ble.sortedByDescending { it.rssi ?: -999 }.forEach {
            val manufacturer = it.manufacturerId?.let(::manufacturerLabel) ?: "sin fabricante"
            val connection = when (it.connectable) { null -> "conexión no informada"; true -> "conectable"; false -> "solo anuncio" }
            ResultRow(
                it.name?.ifBlank { null } ?: "[sin nombre]",
                "${it.address ?: "dirección no informada"} · ${bleAddressLabel(it.addressType)} · $manufacturer · $connection${it.services?.let { s -> " · $s" } ?: ""}",
                rssiText(it.rssi),
            )
        }
    }
}

@Composable
private fun ComparisonCard(repository: HistoryRepository, ids: Pair<String, String>, onClose: () -> Unit) {
    var result by remember(ids) { mutableStateOf<ComparisonResult?>(null) }
    var missing by remember(ids) { mutableStateOf(false) }
    LaunchedEffect(ids) {
        val loaded = runCatching { repository.compare(ids.first, ids.second) }.getOrNull()
        result = loaded
        missing = loaded == null
    }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Comparación", fontWeight = FontWeight.Bold)
                TextButton(onClick = onClose) { Text("Cerrar") }
            }
            if (missing) EmptyResult("Una de las evaluaciones ya no existe.")
            val comparison = result ?: return@Column
            val delta = comparison.scoreDelta.let { if (it > 0) "+$it" else "$it" }
            Text("${dateLabel(comparison.before.createdAt)} → ${dateLabel(comparison.after.createdAt)}", color = Muted, fontSize = 12.sp)
            Text("Puntuación ${comparison.before.score} → ${comparison.after.score} ($delta)", fontWeight = FontWeight.Black, fontSize = 18.sp)
            comparison.warnings.forEach { Text("• $it", color = Amber, fontSize = 12.sp) }
            Text("Criterio: una red se identifica por su BSSID. Si el BSSID se mantiene con otro SSID, canal o cifrado, es la misma red con otra configuración. Sólo las direcciones BLE públicas se comparan una a una.", color = Muted, fontSize = 11.sp)
            if (comparison.detailed) {
                val findings = HistoryLogic.findings(comparison)
                if (findings.isEmpty()) Text("No se detectaron cambios relevantes de seguridad entre ambas evaluaciones.", color = Muted, fontSize = 12.sp)
                findings.forEach { finding ->
                    val label = when (finding.severity) { "high" -> "ALTO"; "medium" -> "MEDIO"; else -> "INFO" }
                    Text("$label · ${finding.title}", color = if (finding.severity == "high") Danger else Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(finding.detail, color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
    val comparison = result ?: return
    if (!comparison.detailed) return
    val wifi = comparison.wifi
    ResultCard("Redes nuevas", wifi.added.size) {
        if (wifi.added.isEmpty()) EmptyResult("Sin cambios.")
        wifi.added.forEach { ResultRow(ssidLabel(it.ssid), "${it.bssid} · Canal ${it.channel ?: "—"} · ${authText(it.auth)}${if (it in wifi.ssidReuse) " · SSID ya conocido con otro BSSID" else ""}", rssiText(it.rssi)) }
    }
    ResultCard("Dejaron de aparecer", wifi.removed.size) {
        if (wifi.removed.isEmpty()) EmptyResult("Sin cambios.")
        wifi.removed.forEach { ResultRow(ssidLabel(it.ssid), "${it.bssid} · Canal ${it.channel ?: "—"} · ${authText(it.auth)}", rssiText(it.rssi)) }
    }
    ResultCard("Cambios de canal", wifi.channelChanged.size) {
        if (wifi.channelChanged.isEmpty()) EmptyResult("Sin cambios.")
        wifi.channelChanged.forEach { ResultRow(ssidLabel(it.after.ssid), it.bssid, "${it.before.channel} → ${it.after.channel}") }
    }
    ResultCard("Cambios de autenticación", wifi.authChanged.size) {
        if (wifi.authChanged.isEmpty()) EmptyResult("Sin cambios.")
        wifi.authChanged.forEach { ResultRow(ssidLabel(it.after.ssid), authChangeDetail(it), authChangeValue(it)) }
    }
    ResultCard("Cambios de nombre", wifi.renamed.size) {
        if (wifi.renamed.isEmpty()) EmptyResult("Sin cambios.")
        wifi.renamed.forEach { ResultRow("${ssidLabel(it.before.ssid)} → ${ssidLabel(it.after.ssid)}", it.bssid, "") }
    }
    val ble = comparison.ble
    ResultCard("Bluetooth LE · direcciones públicas", ble.added.size + ble.removed.size) {
        if (!ble.compared) { EmptyResult("No se compara Bluetooth porque el escaneo falló en una de las evaluaciones."); return@ResultCard }
        Text("${ble.persistent} dirección(es) pública(s) en ambas · ${ble.unmatchedBefore} → ${ble.unmatchedAfter} anuncios con dirección aleatoria, resoluble o no informada (no se comparan individualmente porque pueden cambiar).", color = Muted, fontSize = 11.sp)
        ble.added.forEach { ResultRow(it.name?.ifBlank { null } ?: "[sin nombre]", "${it.address} · nueva", rssiText(it.rssi)) }
        ble.removed.forEach { ResultRow(it.name?.ifBlank { null } ?: "[sin nombre]", "${it.address} · ya no observada", rssiText(it.rssi)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PrivacyCard(repository: HistoryRepository, total: Int, onClearAll: () -> Unit) {
    val scope = rememberCoroutineScope()
    val saved = remember { repository.retention() }
    var maxCount by remember { mutableIntStateOf(saved.maxCount) }
    var maxAge by remember { mutableIntStateOf(saved.maxAgeDays) }
    var pendingPolicy by remember { mutableStateOf<Pair<RetentionPolicy, Int>?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Privacidad y conservación", fontWeight = FontWeight.Bold)
            listOf(
                "Fecha, puntuación, nivel y estado de cada evaluación.",
                "Identidad del sensor (placa, chip, MAC, firmware, protocolo) si el sensor la informó.",
                "Por red Wi-Fi: SSID, BSSID, canal, RSSI y autenticación anunciada.",
                "Por dispositivo BLE: nombre, dirección, tipo, RSSI, conectividad, fabricante y servicios.",
                "No se guardan contraseñas, tráfico ni la consola serial. Nada sale del teléfono.",
            ).forEach { Text("• $it", color = Muted, fontSize = 12.sp) }
            HorizontalDivider(color = PanelSoft)
            Text("Conservar como máximo", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RetentionPolicy.COUNT_OPTIONS.forEach { value ->
                    FilterChip(selected = maxCount == value, onClick = { maxCount = value }, label = { Text(if (value == 0) "Sin límite" else "$value", fontSize = 11.sp) })
                }
            }
            Text("Eliminar evaluaciones con más de", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RetentionPolicy.AGE_OPTIONS.forEach { value ->
                    FilterChip(selected = maxAge == value, onClick = { maxAge = value }, label = { Text(if (value == 0) "Sin límite" else "$value días", fontSize = 11.sp) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val policy = RetentionPolicy.normalized(maxCount, maxAge)
                        scope.launch {
                            val affected = runCatching { repository.expiredWith(policy).size }.getOrDefault(0)
                            if (affected > 0) pendingPolicy = policy to affected else repository.launch { updateRetention(policy) }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Night),
                ) { Text("Aplicar política") }
                Button(enabled = total > 0, onClick = onClearAll, colors = ButtonDefaults.buttonColors(containerColor = PanelSoft, contentColor = Danger)) { Text("Eliminar todo") }
            }
        }
    }
    pendingPolicy?.let { (policy, affected) ->
        ConfirmDialog(
            title = "Aplicar política de conservación",
            text = "Con esta política se eliminarán $affected evaluación(es) ahora y las siguientes que la excedan. ¿Continuar?",
            confirm = "Aplicar",
            onConfirm = { pendingPolicy = null; repository.launch { updateRetention(policy) } },
            onDismiss = { pendingPolicy = null; maxCount = repository.retention().maxCount; maxAge = repository.retention().maxAgeDays },
        )
    }
}

@Composable
internal fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = Danger, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

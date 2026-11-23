package com.vigiasuite.app

// Lógica pura del historial (sin Android ni Room en tiempo de ejecución). Es equivalente a
// vigia-suite-web/pc-lab/history-core.js: mismos campos, estados, criterios de comparación y severidades.

object HistoryStatus {
    const val COMPLETE = "complete"
    const val PARTIAL = "partial"
    const val LEGACY = "legacy"
}

object HistorySource {
    const val LIVE = "live"
    const val LEGACY = "legacy"
}

data class RetentionPolicy(val maxCount: Int = 100, val maxAgeDays: Int = 0) {
    companion object {
        val COUNT_OPTIONS = listOf(20, 50, 100, 0)
        val AGE_OPTIONS = listOf(30, 90, 365, 0)
        // 100 evaluaciones sin límite de antigüedad: nunca recorta las 20 sesiones de la versión anterior.
        val DEFAULT = RetentionPolicy()

        fun normalized(maxCount: Int, maxAgeDays: Int) = RetentionPolicy(
            maxCount = if (maxCount in COUNT_OPTIONS) maxCount else DEFAULT.maxCount,
            maxAgeDays = if (maxAgeDays in AGE_OPTIONS) maxAgeDays else DEFAULT.maxAgeDays,
        )
    }
}

/** Lo que el sensor informó sobre sí mismo. Lo no recibido queda en null. */
data class SensorIdentity(
    val board: String? = null,
    val chip: String? = null,
    val mac: String? = null,
    val firmware: String? = null,
    val protocol: Int? = null,
)

data class AssessmentDetail(
    val assessment: AssessmentEntity,
    val wifi: List<WifiObservationEntity>,
    val ble: List<BleObservationEntity>,
)

data class ChannelChange(val bssid: String, val before: WifiObservationEntity, val after: WifiObservationEntity)
data class AuthChange(val bssid: String, val before: WifiObservationEntity, val after: WifiObservationEntity, val downgrade: Boolean)
data class SsidChange(val bssid: String, val before: WifiObservationEntity, val after: WifiObservationEntity)

data class WifiComparison(
    val added: List<WifiObservationEntity> = emptyList(),
    val removed: List<WifiObservationEntity> = emptyList(),
    val channelChanged: List<ChannelChange> = emptyList(),
    val authChanged: List<AuthChange> = emptyList(),
    val renamed: List<SsidChange> = emptyList(),
    val ssidReuse: List<WifiObservationEntity> = emptyList(),
    val unchanged: Int = 0,
    val unidentified: Int = 0,
)

data class BleComparison(
    val compared: Boolean = false,
    val added: List<BleObservationEntity> = emptyList(),
    val removed: List<BleObservationEntity> = emptyList(),
    val persistent: Int = 0,
    val unmatchedBefore: Int = 0,
    val unmatchedAfter: Int = 0,
)

data class ComparisonResult(
    val before: AssessmentEntity,
    val after: AssessmentEntity,
    val detailed: Boolean,
    val warnings: List<String>,
    val wifi: WifiComparison,
    val ble: BleComparison,
) {
    val scoreDelta: Int get() = after.score - before.score
}

data class ComparisonFinding(val severity: String, val title: String, val detail: String)

object HistoryLogic {
    private val MAC_REGEX = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    private val BLE_ADDRESS_TYPES = setOf("public", "random", "public_identity", "random_identity")
    private const val DAY_MS = 24L * 60 * 60 * 1000

    // Orden de robustez de wifi_auth_mode_t del ESP32. Un modo desconocido no se juzga.
    private val AUTH_RANK = mapOf(0 to 0, 1 to 1, 2 to 2, 4 to 3, 3 to 4, 8 to 4, 5 to 5, 7 to 5, 6 to 6)

    fun normalizeMac(value: String?): String? {
        val text = value?.trim()?.uppercase()?.replace('-', ':') ?: return null
        return if (MAC_REGEX.matches(text)) text else null
    }

    fun levelFor(score: Int) = if (score >= 60) "ALTO" else if (score >= 30) "MEDIO" else "BAJO"

    private fun Int.inRangeOrNull(range: IntRange): Int? = if (this in range) this else null

    /**
     * Observación Wi-Fi a partir del modelo existente. El parser ya deja en null un campo ausente o inválido; el rango
     * se vuelve a comprobar aquí para que el historial nunca guarde un canal 0 ni un modo fuera de wifi_auth_mode_t.
     */
    fun wifiObservation(assessmentId: String, seq: Int, network: WifiNetwork) = WifiObservationEntity(
        assessmentId = assessmentId,
        seq = seq,
        ssid = network.ssid,
        bssid = normalizeMac(network.bssid),
        channel = network.channel?.inRangeOrNull(VigiaFields.CHANNEL),
        rssi = network.rssi?.inRangeOrNull(VigiaFields.RSSI),
        auth = network.auth?.inRangeOrNull(VigiaFields.AUTH),
    )

    fun bleObservation(assessmentId: String, seq: Int, device: BleDevice) = BleObservationEntity(
        assessmentId = assessmentId,
        seq = seq,
        name = device.name,
        address = normalizeMac(device.address),
        addressType = if (device.addressType in BLE_ADDRESS_TYPES) device.addressType else "unknown",
        rssi = device.rssi?.inRangeOrNull(VigiaFields.RSSI),
        connectable = device.connectable,
        manufacturerId = device.manufacturerId.inRangeOrNull(0..0xFFFF),
        services = device.services.trim().ifBlank { null },
    )

    /**
     * Evaluación completa. La puntuación se recibe ya calculada (RiskScore.compute) y aquí no se recalcula; se guarda
     * junto con la versión de la fórmula que la produjo.
     */
    fun buildAssessment(
        id: String,
        createdAt: Long,
        status: String,
        sensor: SensorIdentity,
        score: Int,
        wifiNetworks: List<WifiNetwork>,
        bleDevices: List<BleDevice>,
        wifiTruncated: Boolean?,
        bleTruncated: Boolean?,
        bleFailed: Boolean?,
        scoreFormula: Int? = RiskScore.FORMULA_VERSION,
    ): AssessmentDetail {
        require(id.isNotBlank()) { "La evaluación necesita un identificador." }
        require(score in 0..100) { "La puntuación debe estar entre 0 y 100." }
        require(status == HistoryStatus.COMPLETE || status == HistoryStatus.PARTIAL) { "Estado de evaluación no válido: $status" }
        val wifi = wifiNetworks.mapIndexed { index, network -> wifiObservation(id, index, network) }
        val ble = bleDevices.mapIndexed { index, device -> bleObservation(id, index, device) }
        val assessment = AssessmentEntity(
            id = id, createdAt = createdAt, origin = "android", source = HistorySource.LIVE, status = status,
            sensorBoard = sensor.board?.ifBlank { null }, sensorChip = sensor.chip?.ifBlank { null },
            sensorMac = normalizeMac(sensor.mac), sensorFirmware = sensor.firmware?.ifBlank { null }, sensorProtocol = sensor.protocol,
            score = score, level = levelFor(score),
            wifiCount = wifi.size, bleCount = ble.size,
            wifiOpen = wifi.count { it.auth == 0 }, bleNearby = ble.count { (it.rssi ?: -999) >= -65 },
            wifiTruncated = wifiTruncated, bleTruncated = bleTruncated, bleFailed = bleFailed,
            hasObservations = true, scoreFormula = scoreFormula,
        )
        return AssessmentDetail(assessment, wifi, ble)
    }

    fun legacyId(record: AnalysisRecord) = "legacy-android-${record.createdAt}-${record.score}"

    /** Resumen de SharedPreferences como evaluación histórica: sin observaciones y sin inventar campos. */
    fun legacyAssessment(record: AnalysisRecord): AssessmentEntity {
        require(record.createdAt > 0) { "fecha inválida" }
        require(record.score in 0..100) { "puntuación fuera de rango" }
        require(record.level in setOf("BAJO", "MEDIO", "ALTO")) { "nivel desconocido" }
        return AssessmentEntity(
            id = legacyId(record), createdAt = record.createdAt, origin = "android", source = HistorySource.LEGACY,
            status = HistoryStatus.LEGACY, sensorBoard = null, sensorChip = null, sensorMac = null, sensorFirmware = null,
            sensorProtocol = null, score = record.score, level = record.level,
            wifiCount = record.wifiTotal, bleCount = record.bleTotal, wifiOpen = record.wifiOpen, bleNearby = record.bleNearby,
            wifiTruncated = null, bleTruncated = null, bleFailed = null, hasObservations = false, scoreFormula = null,
        )
    }

    data class LegacyPlan(val records: List<AssessmentEntity>, val rejected: List<String>)

    fun prepareLegacyMigration(records: List<AnalysisRecord>): LegacyPlan {
        val accepted = LinkedHashMap<String, AssessmentEntity>()
        val rejected = mutableListOf<String>()
        records.forEachIndexed { index, record ->
            runCatching { legacyAssessment(record) }
                .onSuccess { accepted.putIfAbsent(it.id, it) }
                .onFailure { rejected += "registro $index: ${it.message}" }
        }
        return LegacyPlan(accepted.values.toList(), rejected)
    }

    /** Identificadores que exceden la política (0 = sin límite), de la más reciente a la más antigua. */
    fun selectExpired(assessments: List<AssessmentEntity>, policy: RetentionPolicy, now: Long): List<String> {
        val sorted = assessments.sortedByDescending { it.createdAt }
        val expired = mutableSetOf<String>()
        if (policy.maxAgeDays > 0) sorted.filter { now - it.createdAt > policy.maxAgeDays * DAY_MS }.forEach { expired += it.id }
        if (policy.maxCount > 0) sorted.drop(policy.maxCount).forEach { expired += it.id }
        return sorted.filter { it.id in expired }.map { it.id }
    }

    fun authRank(auth: Int?): Int? = auth?.let { AUTH_RANK[it] }

    fun isAuthDowngrade(before: Int?, after: Int?): Boolean {
        val from = authRank(before) ?: return false
        val to = authRank(after) ?: return false
        return to < from
    }

    // Sólo una dirección pública (tipo 0) identifica de forma estable. Aleatorias y resolubles (RPA) cambian.
    fun isStableBleAddress(observation: BleObservationEntity) = observation.addressType == "public" && observation.address != null

    private fun <T> strongestByKey(items: List<T>, key: (T) -> String?, rssi: (T) -> Int?): Map<String, T> {
        val map = LinkedHashMap<String, T>()
        items.forEach { item ->
            val k = key(item) ?: return@forEach
            val current = map[k]
            if (current == null || (rssi(item) ?: -999) > (rssi(current) ?: -999)) map[k] = item
        }
        return map
    }

    /**
     * Si la puntuación guardada pudo calcularse sin la regla de congestión (RiskScore.CONGESTION_RULE_SINCE). Sólo lo
     * dice la versión guardada en la evaluación; null (no registrada) se trata como «pudo», nunca como un valor supuesto.
     */
    fun mayLackCongestionRule(assessment: AssessmentEntity): Boolean =
        assessment.scoreFormula == null || assessment.scoreFormula < RiskScore.CONGESTION_RULE_SINCE

    /**
     * Si la regla de congestión habría aplicado a esta evaluación. Con observaciones se sabe exactamente (un canal con 12
     * o más redes); con un resumen histórico sólo se descarta cuando hay menos de 12 redes en total.
     */
    fun congestionRuleApplies(detail: AssessmentDetail): Boolean = if (detail.assessment.hasObservations) {
        (RiskScore.busiestChannel(detail.wifi.map { it.channel })?.second ?: 0) >= RiskScore.CONGESTED_CHANNEL_NETWORKS
    } else {
        (detail.assessment.wifiCount ?: Int.MAX_VALUE) >= RiskScore.CONGESTED_CHANNEL_NETWORKS
    }

    /**
     * Compara dos evaluaciones; la más antigua es la referencia. Wi-Fi se identifica por BSSID: un BSSID igual con
     * otro SSID es la misma red renombrada. En BLE sólo se emparejan direcciones públicas.
     *
     * Las puntuaciones guardadas no se recalculan. Si sólo una de las dos pudo puntuarse sin la regla de congestión y esa
     * regla le habría aplicado, se advierte. La versión Web no necesita esta advertencia: su fórmula no cambió.
     */
    fun compare(left: AssessmentDetail, right: AssessmentDetail): ComparisonResult {
        val (older, newer) = if (left.assessment.createdAt <= right.assessment.createdAt) left to right else right to left
        val a = older.assessment
        val b = newer.assessment
        val detailed = a.hasObservations && b.hasObservations
        val warnings = buildList {
            if (!detailed) add("Una de las evaluaciones es histórica y sólo conserva el resumen: no se pueden comparar redes ni dispositivos individuales.")
            if (a.wifiTruncated == true || b.wifiTruncated == true) add("El inventario Wi-Fi alcanzó el límite de 32 redes del sensor: una red «desaparecida» puede no haberse informado.")
            if (a.bleTruncated == true || b.bleTruncated == true) add("El inventario BLE alcanzó el límite de 32 dispositivos del sensor.")
            if (a.bleFailed == true || b.bleFailed == true) add("El escaneo Bluetooth falló en una de las evaluaciones: la comparación BLE no es representativa.")
            if (a.status == HistoryStatus.PARTIAL || b.status == HistoryStatus.PARTIAL) add("Una de las evaluaciones quedó incompleta.")
            val withoutRule = listOf(older, newer).filter { mayLackCongestionRule(it.assessment) }
            if (withoutRule.size == 1 && congestionRuleApplies(withoutRule.single())) {
                add("Una de las evaluaciones no registra haberse puntuado con la fórmula actual de Android (se guardó antes de esta versión o es un resumen histórico). Si se calculó sin la regla que suma 5 puntos cuando un canal concentra 12 o más redes, hasta 5 puntos de la diferencia pueden deberse a ese cambio y no al entorno.")
            }
        }
        if (!detailed) return ComparisonResult(a, b, false, warnings, WifiComparison(), BleComparison())

        val oldWifi = strongestByKey(older.wifi, { it.bssid }, { it.rssi })
        val newWifi = strongestByKey(newer.wifi, { it.bssid }, { it.rssi })
        val knownSsids = oldWifi.values.mapNotNull { it.ssid?.ifEmpty { null } }.toSet()
        val added = mutableListOf<WifiObservationEntity>()
        val reuse = mutableListOf<WifiObservationEntity>()
        val channel = mutableListOf<ChannelChange>()
        val auth = mutableListOf<AuthChange>()
        val renamed = mutableListOf<SsidChange>()
        var unchanged = 0
        newWifi.forEach { (bssid, current) ->
            val previous = oldWifi[bssid]
            if (previous == null) {
                added += current
                if (!current.ssid.isNullOrEmpty() && current.ssid in knownSsids) reuse += current
                return@forEach
            }
            var changed = false
            if (previous.channel != null && current.channel != null && previous.channel != current.channel) { channel += ChannelChange(bssid, previous, current); changed = true }
            if (previous.auth != null && current.auth != null && previous.auth != current.auth) { auth += AuthChange(bssid, previous, current, isAuthDowngrade(previous.auth, current.auth)); changed = true }
            if (previous.ssid != null && current.ssid != null && previous.ssid != current.ssid) { renamed += SsidChange(bssid, previous, current); changed = true }
            if (!changed) unchanged += 1
        }
        val removed = oldWifi.filterKeys { it !in newWifi }.values.toList()
        val wifi = WifiComparison(
            added, removed, channel, auth, renamed, reuse, unchanged,
            unidentified = older.wifi.count { it.bssid == null } + newer.wifi.count { it.bssid == null },
        )

        val ble = if (a.bleFailed == true || b.bleFailed == true) BleComparison() else {
            val oldBle = strongestByKey(older.ble.filter(::isStableBleAddress), { it.address }, { it.rssi })
            val newBle = strongestByKey(newer.ble.filter(::isStableBleAddress), { it.address }, { it.rssi })
            BleComparison(
                compared = true,
                added = newBle.filterKeys { it !in oldBle }.values.toList(),
                removed = oldBle.filterKeys { it !in newBle }.values.toList(),
                persistent = newBle.keys.count { it in oldBle },
                unmatchedBefore = older.ble.count { !isStableBleAddress(it) },
                unmatchedAfter = newer.ble.count { !isStableBleAddress(it) },
            )
        }
        return ComparisonResult(a, b, true, warnings, wifi, ble)
    }

    /** Mismas severidades y textos que comparisonFindings() de la versión Web. */
    fun findings(result: ComparisonResult): List<ComparisonFinding> = buildList {
        val downgrades = result.wifi.authChanged.count { it.downgrade }
        val newOpen = result.wifi.added.count { it.auth == 0 }
        if (downgrades > 0) add(ComparisonFinding("high", "$downgrades red(es) redujeron su protección", "El mismo BSSID anuncia ahora un cifrado más débil. Verifica la configuración del punto de acceso."))
        if (result.wifi.ssidReuse.isNotEmpty()) add(ComparisonFinding("medium", "${result.wifi.ssidReuse.size} BSSID nuevo(s) con un SSID ya conocido", "Puede ser un AP adicional, una red mesh o un AP no autorizado. Valida ubicación y propietario antes de concluir."))
        if (newOpen > 0) add(ComparisonFinding("medium", "$newOpen red(es) abierta(s) nueva(s)", "Aparecieron redes sin cifrado que no estaban en la evaluación anterior."))
        val otherAuth = result.wifi.authChanged.size - downgrades
        if (otherAuth > 0) add(ComparisonFinding("info", "$otherAuth cambio(s) de autenticación sin pérdida de protección", "El BSSID mantiene o mejora su cifrado."))
        if (result.wifi.channelChanged.isNotEmpty()) add(ComparisonFinding("info", "${result.wifi.channelChanged.size} red(es) cambiaron de canal", "Es habitual cuando el AP elige canal automáticamente."))
        if (result.wifi.renamed.isNotEmpty()) add(ComparisonFinding("info", "${result.wifi.renamed.size} red(es) cambiaron de nombre", "Mismo BSSID con otro SSID: se trata como la misma red reconfigurada, no como una red nueva."))
    }
}

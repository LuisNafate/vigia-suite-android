package com.vigiasuite.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

// Mismos escenarios que vigia-suite-web/tests/history-core.test.mjs: ambos clientes deben interpretar igual.
class HistoryLogicTest {
    private fun ap(bssid: String, ssid: String = "Casa", channel: Int = 6, auth: Int = 3, rssi: Int = -60) = WifiNetwork(ssid, rssi, channel, auth, bssid)
    private fun ble(address: String, type: String = "public", rssi: Int = -70) = BleDevice("", rssi, address, type, false, -1, "")

    private fun detail(id: String, createdAt: Long, wifi: List<WifiNetwork> = emptyList(), ble: List<BleDevice> = emptyList(),
                       status: String = HistoryStatus.COMPLETE, wifiTruncated: Boolean? = false, bleFailed: Boolean? = false) =
        HistoryLogic.buildAssessment(id, createdAt, status, SensorIdentity(), 10, wifi, ble, wifiTruncated, false, bleFailed)

    @Test
    fun buildAssessmentKeepsProtocolFieldsAndMarksUnknownAsNull() {
        val result = HistoryLogic.buildAssessment(
            "a1", 1_000L, HistoryStatus.COMPLETE,
            SensorIdentity(board = "nodemcu-esp32s", chip = "ESP32", mac = "04:b2:47:82:60:1c", firmware = "0.5.0-arduino", protocol = 2),
            42, listOf(ap("aa:bb:cc:dd:ee:01", ssid = "", auth = 0), ap("AA:BB:CC:DD:EE:02", channel = 0)),
            listOf(BleDevice("", -50, "11:22:33:44:55:66", "public", true, 76, " fd44 ")), false, true, false,
        )
        assertEquals("04:B2:47:82:60:1C", result.assessment.sensorMac)
        assertEquals(2, result.assessment.sensorProtocol)
        assertEquals("MEDIO", result.assessment.level)
        assertEquals(2, result.assessment.wifiCount)
        assertEquals(1, result.assessment.wifiOpen)
        assertEquals(1, result.assessment.bleNearby)
        assertEquals(true, result.assessment.bleTruncated)
        assertEquals("", result.wifi[0].ssid)
        assertEquals("AA:BB:CC:DD:EE:01", result.wifi[0].bssid)
        assertNull("un canal 0 no es válido y queda como desconocido", result.wifi[1].channel)
        assertEquals(76, result.ble[0].manufacturerId)
        assertEquals("fd44", result.ble[0].services)
        assertTrue(result.wifi.all { it.assessmentId == "a1" } && result.ble.all { it.assessmentId == "a1" })
    }

    @Test
    fun buildAssessmentDoesNotInventSensorDataAndRejectsInvalidInput() {
        val result = HistoryLogic.buildAssessment("x", 1L, HistoryStatus.PARTIAL, SensorIdentity(), 0, emptyList(),
            listOf(BleDevice("", -60, "zz", "otro", false, -1, "")), null, null, true)
        val a = result.assessment
        assertNull(a.sensorBoard); assertNull(a.sensorChip); assertNull(a.sensorMac); assertNull(a.sensorFirmware); assertNull(a.sensorProtocol)
        assertNull(result.ble[0].address)
        assertEquals("unknown", result.ble[0].addressType)
        assertNull(result.ble[0].manufacturerId)
        assertNull(a.wifiTruncated)
        assertThrows(IllegalArgumentException::class.java) { HistoryLogic.buildAssessment("x", 1L, HistoryStatus.COMPLETE, SensorIdentity(), 101, emptyList(), emptyList(), null, null, null) }
        assertThrows(IllegalArgumentException::class.java) { HistoryLogic.buildAssessment("x", 1L, HistoryStatus.LEGACY, SensorIdentity(), 1, emptyList(), emptyList(), null, null, null) }
        assertThrows(IllegalArgumentException::class.java) { HistoryLogic.buildAssessment(" ", 1L, HistoryStatus.COMPLETE, SensorIdentity(), 1, emptyList(), emptyList(), null, null, null) }
    }

    @Test
    fun wifiComparisonUsesBssidAsIdentity() {
        val before = detail("a", 1000, listOf(
            ap("AA:AA:AA:AA:AA:01"), ap("AA:AA:AA:AA:AA:02", channel = 1), ap("AA:AA:AA:AA:AA:03", auth = 3),
            ap("AA:AA:AA:AA:AA:04", ssid = "Oficina"), ap("AA:AA:AA:AA:AA:05", ssid = "Vieja"),
        ))
        val after = detail("b", 2000, listOf(
            ap("AA:AA:AA:AA:AA:01"), ap("AA:AA:AA:AA:AA:02", channel = 11), ap("AA:AA:AA:AA:AA:03", auth = 0),
            ap("AA:AA:AA:AA:AA:04", ssid = "Oficina-5"), ap("AA:AA:AA:AA:AA:06", ssid = "Nueva", auth = 0), ap("AA:AA:AA:AA:AA:07", ssid = "Casa"),
        ))
        val result = HistoryLogic.compare(after, before)
        assertEquals("la más antigua es la referencia", "a", result.before.id)
        assertEquals(listOf("AA:AA:AA:AA:AA:06", "AA:AA:AA:AA:AA:07"), result.wifi.added.map { it.bssid })
        assertEquals(listOf("AA:AA:AA:AA:AA:05"), result.wifi.removed.map { it.bssid })
        assertEquals(listOf(Triple("AA:AA:AA:AA:AA:02", 1, 11)), result.wifi.channelChanged.map { Triple(it.bssid, it.before.channel, it.after.channel) })
        assertEquals(1, result.wifi.authChanged.size)
        assertTrue(result.wifi.authChanged[0].downgrade)
        assertEquals("renombrada, no nueva", listOf("AA:AA:AA:AA:AA:04"), result.wifi.renamed.map { it.bssid })
        assertEquals(1, result.wifi.unchanged)
        assertEquals(listOf("AA:AA:AA:AA:AA:07"), result.wifi.ssidReuse.map { it.bssid })
        val findings = HistoryLogic.findings(result)
        assertEquals("high", findings.first().severity)
        assertTrue(findings.any { it.title.contains("abierta") })
        assertEquals(10 - 10, result.scoreDelta)
    }

    @Test
    fun wifiComparisonToleratesIncompleteObservations() {
        val before = detail("a", 1, listOf(ap("AA:AA:AA:AA:AA:01", channel = 0, auth = -5), ap("sin-bssid")))
        val after = detail("b", 2, listOf(ap("aa:aa:aa:aa:aa:01", channel = 6, auth = 3), ap("")))
        val result = HistoryLogic.compare(before, after)
        assertTrue(result.wifi.channelChanged.isEmpty())
        assertTrue(result.wifi.authChanged.isEmpty())
        assertEquals(1, result.wifi.unchanged)
        assertEquals(2, result.wifi.unidentified)
        assertTrue(result.wifi.added.isEmpty() && result.wifi.removed.isEmpty())
    }

    @Test
    fun bleComparisonOnlyMatchesPublicAddresses() {
        val before = detail("a", 1, ble = listOf(ble("11:11:11:11:11:11"), ble("22:22:22:22:22:22"), ble("33:33:33:33:33:33", "random"), ble("44:44:44:44:44:44", "public_identity")))
        val after = detail("b", 2, ble = listOf(ble("11:11:11:11:11:11"), ble("55:55:55:55:55:55"), ble("33:33:33:33:33:33", "random"), ble("44:44:44:44:44:44", "public_identity")))
        val result = HistoryLogic.compare(before, after).ble
        assertTrue(result.compared)
        assertEquals(1, result.persistent)
        assertEquals(listOf("55:55:55:55:55:55"), result.added.map { it.address })
        assertEquals(listOf("22:22:22:22:22:22"), result.removed.map { it.address })
        assertEquals("las aleatorias y resolubles no se emparejan aunque coincidan", 2, result.unmatchedBefore)
        assertEquals(2, result.unmatchedAfter)
    }

    @Test
    fun comparisonWithLegacyTruncatedOrFailedEvaluationsWarns() {
        val legacy = AssessmentDetail(HistoryLogic.legacyAssessment(AnalysisRecord(1_000L, 5, "BAJO", 3, 0, 1, 0)), emptyList(), emptyList())
        val live = detail("b", 2_000L, listOf(ap("AA:AA:AA:AA:AA:01")), wifiTruncated = true)
        val summaryOnly = HistoryLogic.compare(legacy, live)
        assertFalse(summaryOnly.detailed)
        assertTrue(summaryOnly.wifi.added.isEmpty())
        assertTrue(summaryOnly.warnings.any { it.contains("histórica") })
        assertTrue(summaryOnly.warnings.any { it.contains("32 redes") })
        val failed = HistoryLogic.compare(detail("a", 1, ble = listOf(ble("11:11:11:11:11:11"))), detail("c", 3, status = HistoryStatus.PARTIAL, bleFailed = true))
        assertFalse(failed.ble.compared)
        assertTrue("un BLE fallido no implica dispositivos desaparecidos", failed.ble.removed.isEmpty())
        assertTrue(failed.warnings.any { it.contains("incompleta") })
    }

    @Test
    fun comparisonAcrossTheScoringChangeWarnsOnlyWhenTheCongestionRuleApplied() {
        val since = 1_000L
        val congested = (1..12).map { ap("AA:AA:AA:AA:AA:%02X".format(it), channel = 6) }
        val spread = (1..12).map { ap("AA:AA:AA:AA:AA:%02X".format(it), channel = it) }
        val warning = "fórmula previa de Android"
        fun warns(before: AssessmentDetail, after: AssessmentDetail) =
            HistoryLogic.compare(after, before, since).warnings.any { warning in it }

        assertTrue("antes con 12 redes en el canal 6", warns(detail("a", 500, congested), detail("b", 1_500, congested)))
        assertFalse("antes sin canal congestionado: la fórmula nueva da lo mismo", warns(detail("a", 500, spread), detail("b", 1_500, congested)))
        assertFalse("las dos con la fórmula nueva", warns(detail("a", 1_000, congested), detail("b", 1_500, congested)))
        assertFalse("las dos con la fórmula anterior", warns(detail("a", 500, congested), detail("b", 900, congested)))
        assertFalse("sin fecha de cambio no se advierte", HistoryLogic.compare(detail("a", 500, congested), detail("b", 1_500, congested)).warnings.any { warning in it })

        val legacy = { wifiTotal: Int -> AssessmentDetail(HistoryLogic.legacyAssessment(AnalysisRecord(400, 20, "BAJO", wifiTotal, 0, 0, 0)), emptyList(), emptyList()) }
        assertTrue("resumen histórico con 15 redes: no se puede descartar", warns(legacy(15), detail("b", 1_500, congested)))
        assertFalse("resumen histórico con 3 redes: imposible 12 en un canal", warns(legacy(3), detail("b", 1_500, congested)))
        // La puntuación guardada no se recalcula.
        assertEquals(20, HistoryLogic.compare(legacy(15), detail("b", 1_500, congested), since).before.score)
    }

    @Test
    fun authDowngradeOnlyJudgesKnownModes() {
        assertTrue(HistoryLogic.isAuthDowngrade(3, 0))
        assertTrue(HistoryLogic.isAuthDowngrade(6, 3))
        assertFalse(HistoryLogic.isAuthDowngrade(3, 6))
        assertFalse(HistoryLogic.isAuthDowngrade(3, 42))
        assertFalse(HistoryLogic.isAuthDowngrade(null, 0))
    }

    @Test
    fun legacyMigrationKeepsSummariesAvoidsDuplicatesAndRejectsDamagedRecords() {
        val record = AnalysisRecord(1_731_240_000_000, 33, "MEDIO", 7, 2, 4, 1)
        val plan = HistoryLogic.prepareLegacyMigration(listOf(record, record.copy(), AnalysisRecord(0, 1, "BAJO", 0, 0, 0, 0), record.copy(level = "OTRO", createdAt = 5)))
        assertEquals(1, plan.records.size)
        assertEquals(2, plan.rejected.size)
        val item = plan.records.single()
        assertEquals("legacy-android-1731240000000-33", item.id)
        assertEquals(HistoryStatus.LEGACY, item.status)
        assertFalse(item.hasObservations)
        assertEquals(7, item.wifiCount)
        assertEquals(4, item.bleCount)
        assertNull(item.sensorFirmware)
        assertNull(item.wifiTruncated)
    }

    @Test
    fun retentionByCountAndAge() {
        val day = 24L * 60 * 60 * 1000
        val now = 200 * day
        val items = (0 until 6).map { HistoryLogic.legacyAssessment(AnalysisRecord(now - it * 20 * day, 1, "BAJO", 0, 0, 0, 0)).copy(id = "e$it") }
        assertEquals(emptyList<String>(), HistoryLogic.selectExpired(items, RetentionPolicy(0, 0), now))
        assertEquals(listOf("e2", "e3", "e4", "e5"), HistoryLogic.selectExpired(items, RetentionPolicy(20, 30), now))
        assertEquals(listOf("e5"), HistoryLogic.selectExpired(items, RetentionPolicy(0, 90), now))
        assertEquals(RetentionPolicy.DEFAULT, RetentionPolicy.normalized(7, 12))
        val many = (0 until 25).map { HistoryLogic.legacyAssessment(AnalysisRecord(now - it, 1, "BAJO", 0, 0, 0, 0)).copy(id = "m$it") }
        assertEquals(listOf("m20", "m21", "m22", "m23", "m24"), HistoryLogic.selectExpired(many, RetentionPolicy(20, 0), now))
    }
}

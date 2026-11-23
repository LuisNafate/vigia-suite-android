package com.vigiasuite.app

/**
 * Puntuación de exposición de una evaluación. Es la única implementación en Android: la usan la pantalla
 * (MainActivity.analyze) y el historial (UsbSerialController.recordAnalysis).
 *
 * Equivale a renderAnalysis() de vigia-suite-web/pc-lab/app.js; los casos de
 * app/src/test/resources/scoring-cases.json (copia idéntica de vigia-suite-web/tests/fixtures/) lo comprueban.
 *
 * Un campo ausente o inválido (null) nunca cuenta: una red sin autenticación informada no es abierta ni débil, y un
 * RSSI desconocido no es cercano.
 */
object RiskScore {
    /**
     * Versión que se guarda en AssessmentEntity.scoreFormula con cada evaluación nueva (la misma que usa la versión Web).
     * 2: Android suma la regla de congestión de Web. 3: clasificación BLE por subtipo de dirección (BleAddress).
     * Las evaluaciones anteriores conservan su puntuación y su versión (null = no registrada).
     */
    const val FORMULA_VERSION = 3
    /** Primera versión con la regla de congestión. */
    const val CONGESTION_RULE_SINCE = 2
    /** Primera versión con la clasificación BLE de BleAddress: sólo pública o aleatoria estática es estable. */
    const val BLE_RULE_SINCE = 3
    const val CONGESTED_CHANNEL_NETWORKS = 12

    data class Result(
        val score: Int,
        val level: String,
        val open: Int,
        val weak: Int,
        val strongOpen: Int,
        val stableNearby: Int,
        val busiestChannel: Int?,
        val busiestCount: Int,
    )

    /** Canal con más redes; los canales desconocidos no se agrupan. */
    fun busiestChannel(channels: List<Int?>): Pair<Int, Int>? =
        channels.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.toPair()

    fun compute(wifi: List<WifiNetwork>, ble: List<BleDevice>): Result {
        val open = wifi.count { it.auth == 0 }
        val weak = wifi.count { it.auth == 1 || it.auth == 2 }
        val strongOpen = wifi.count { it.auth == 0 && it.rssi != null && it.rssi >= -70 }
        // Versión 3: cercano, conectable y con identificador estable; privada o no determinada no suma.
        val stableNearby = ble.count { it.rssi != null && it.rssi >= -65 && it.connectable && BleAddress.isStable(BleAddress.classify(it.addressType, it.address)) }
        val busiest = busiestChannel(wifi.map { it.channel })
        var score = minOf(40, open * 3) + minOf(25, strongOpen * 5) + minOf(25, weak * 12) + minOf(10, stableNearby * 2)
        if (wifi.isNotEmpty() && open.toFloat() / wifi.size >= .25f) score += 10
        // Regla de Web (opción A): un canal que concentra 12 o más redes suma 5 puntos.
        if (busiest != null && busiest.second >= CONGESTED_CHANNEL_NETWORKS) score += 5
        score = minOf(100, score)
        return Result(score, HistoryLogic.levelFor(score), open, weak, strongOpen, stableNearby, busiest?.first, busiest?.second ?: 0)
    }
}

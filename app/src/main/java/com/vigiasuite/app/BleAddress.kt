package com.vigiasuite.app

/**
 * Clasificación de direcciones BLE (fórmula de puntuación versión 3). Igual que vigia-suite-web/pc-lab/ble-address.js;
 * ble-address-cases.json (copia idéntica en los dos repositorios) lo comprueba.
 *
 * Origen de los datos (firmware 0.5.0, núcleo 3.3.12 con Bluedroid): address_type es esp_ble_addr_type_t sin traducir
 * (0 public, 1 random, 2 public_identity, 3 random_identity) y address es BLEAddress::toString(), que imprime
 * scan_rst.bda desde el byte 0, el más significativo. Los dos bits altos del primer par hexadecimal indican el subtipo
 * de una dirección aleatoria (Bluetooth Core, Vol 6, Parte B, 1.3.2): 11 estática, 01 privada resoluble, 00 privada no
 * resoluble y 10 reservado.
 *
 * Los tipos 2 y 3 sólo aparecen cuando el controlador resolvió una dirección privada resoluble con la IRK de un
 * dispositivo emparejado: la dirección recibida es la identidad resuelta, no la que se ve en el aire. El sensor no se
 * empareja, así que no se tratan como rastreables por terceros.
 */
object BleAddress {
    enum class Kind(val code: String, val label: String) {
        PUBLIC("public", "pública"),
        STATIC("static", "aleatoria estática"),
        RESOLVABLE("resolvable", "privada resoluble"),
        NON_RESOLVABLE("non_resolvable", "privada no resoluble"),
        RESOLVED_IDENTITY("resolved_identity", "identidad resuelta"),
        UNKNOWN("unknown", "no determinada"),
    }

    private val RANDOM_SUBTYPES = listOf(Kind.NON_RESOLVABLE, Kind.RESOLVABLE, Kind.UNKNOWN, Kind.STATIC)

    fun classify(addressType: String?, address: String?): Kind {
        if (addressType == "public_identity" || addressType == "random_identity") return Kind.RESOLVED_IDENTITY
        if (addressType != "public" && addressType != "random") return Kind.UNKNOWN
        val mac = HistoryLogic.normalizeMac(address) ?: return Kind.UNKNOWN
        if (addressType == "public") return Kind.PUBLIC
        return RANDOM_SUBTYPES[mac.substring(0, 2).toInt(16) shr 6]
    }

    /** Identificador que puede repetirse entre sesiones: pública o aleatoria estática (ésta cambia si el dispositivo la regenera). */
    fun isStable(kind: Kind) = kind == Kind.PUBLIC || kind == Kind.STATIC
    fun isPrivate(kind: Kind) = kind == Kind.RESOLVABLE || kind == Kind.NON_RESOLVABLE || kind == Kind.RESOLVED_IDENTITY

    data class Points(val current: Int, val previous: Int)

    /**
     * Puntos BLE de las observaciones guardadas con la regla de la versión 3 y con la anterior (todo tipo que no contenga
     * «random» era estable). Sólo se usa para advertir al comparar evaluaciones; nunca reemplaza la puntuación guardada.
     */
    fun blePoints(items: List<BleObservationEntity>): Points {
        val candidates = items.filter { (it.rssi ?: -999) >= -65 && it.connectable == true }
        fun points(count: Int) = minOf(10, count * 2)
        return Points(
            current = points(candidates.count { isStable(classify(it.addressType, it.address)) }),
            previous = points(candidates.count { !it.addressType.contains("random") }),
        )
    }
}

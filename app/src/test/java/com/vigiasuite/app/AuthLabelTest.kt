package com.vigiasuite.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Etiquetas de wifi_auth_mode_t del núcleo esp32 3.3.12 (esp_wifi_types_generic.h). Mismos textos que la versión Web. */
class AuthLabelTest {
    @Test
    fun everyModeOfTheCoreHasItsLabel() {
        val expected = listOf(
            "Abierta", "WEP", "WPA-PSK", "WPA2-PSK", "WPA/WPA2", "WPA2 Enterprise", "WPA3-PSK", "WPA2/WPA3", "WAPI",
            "OWE", "WPA3 Enterprise 192 bits", "WPA3-PSK (EXT)", "WPA3-PSK mixto (EXT)", "DPP", "WPA3 Enterprise",
            "WPA2/WPA3 Enterprise", "WPA Enterprise",
        )
        assertEquals(expected, (0..16).map(::authLabel))
    }

    @Test
    fun unknownAndMissingModesAreNotPresentedAsOpen() {
        assertEquals("Modo 17 no reconocido", authLabel(17))
        assertEquals("No informada", authLabel(null))
    }
}

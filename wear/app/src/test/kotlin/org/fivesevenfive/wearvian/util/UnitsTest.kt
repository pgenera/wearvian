package org.fivesevenfive.wearvian.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class UnitsTest {

    private val original = Locale.getDefault()

    @After
    fun restore() = Locale.setDefault(original)

    @Test
    fun usIsFahrenheitAndMiles() {
        Locale.setDefault(Locale.US)
        assertEquals("79°F", Units.temp(26)) // 26°C == 78.8 → 79°F
        assertEquals("86°F", Units.temp(30))
        assertEquals("137 mi", Units.range(220)) // 220 km == 136.7 → 137 mi
    }

    @Test
    fun franceIsCelsiusAndKm() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("26°C", Units.temp(26))
        assertEquals("220 km", Units.range(220))
    }

    @Test
    fun ukIsMilesButCelsius() {
        Locale.setDefault(Locale.UK)
        assertEquals("26°C", Units.temp(26)) // UK: distance in miles, temperature in °C
        assertEquals("137 mi", Units.range(220))
    }
}

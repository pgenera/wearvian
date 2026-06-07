package org.fivesevenfive.wearvian.util

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Locale-based display units for the watch's region. Android exposes no public
 * "metric vs imperial" toggle, so we infer from the default locale's country —
 * and keep temperature and distance independent (the UK shows miles but °C).
 */
object Units {
    // Countries that display temperature in Fahrenheit.
    private val FAHRENHEIT = setOf("US", "BS", "BZ", "KY", "LR", "PW", "FM", "MH")
    // Countries that display road distances in miles.
    private val MILES = setOf("US", "GB", "MM", "LR")

    private val country: String get() = Locale.getDefault().country.uppercase(Locale.ROOT)

    val useFahrenheit: Boolean get() = country in FAHRENHEIT
    val useMiles: Boolean get() = country in MILES

    /** Temperature: input °C → localized string, e.g. "79°F" or "26°C". */
    fun temp(celsius: Int): String =
        if (useFahrenheit) "${(celsius * 9 / 5.0 + 32).roundToInt()}°F" else "$celsius°C"

    /** Distance: input km → localized string, e.g. "137 mi" or "220 km". */
    fun range(km: Int): String =
        if (useMiles) "${(km / 1.609344).roundToInt()} mi" else "$km km"
}

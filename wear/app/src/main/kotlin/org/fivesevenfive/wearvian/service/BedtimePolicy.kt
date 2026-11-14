package org.fivesevenfive.wearvian.service

/** Wear OS bedtime mode as far as this app can tell (see [BedtimeMode] for the reads). */
enum class BedtimeState { ACTIVE, INACTIVE, UNKNOWN }

/**
 * Turn the digital key OFF when Wear OS bedtime mode comes on — a watch on the nightstand (or on
 * a sleeping wrist) has no business being a live car key, and the BLE presence session is the
 * battery's biggest cost. Pure (no Android types) so the rules are JVM-testable; [PresenceService]
 * and the boot / upgrade receivers enact the results.
 *
 * There is no public bedtime API a third-party app can hold the permission for, so the state comes
 * from two UNDOCUMENTED `Settings.Global` keys read by string: `bedtime_mode` (Pixel Watch / stock
 * Wear OS) and `setting_bedtime_mode_running_state` (Samsung). Either can vanish or change meaning
 * in an OS update, so [BedtimeState.UNKNOWN] (neither key present or readable) fails safe to "do
 * nothing": the key behaves exactly as it did before this feature.
 *
 * Only the rising edge turns the key off, and the key is never re-armed when bedtime ends: a key
 * the user deliberately turns back on during bedtime stays on until bedtime ends and starts again.
 */
object BedtimePolicy {

    /**
     * Combine the two key reads: null = unreadable (threw), -1 = absent. ACTIVE if either is 1;
     * INACTIVE if at least one key is present and none is 1; otherwise UNKNOWN.
     */
    fun state(pixel: Int?, samsung: Int?): BedtimeState = when {
        pixel == 1 || samsung == 1 -> BedtimeState.ACTIVE
        listOf(pixel, samsung).any { it != null && it != ABSENT } -> BedtimeState.INACTIVE
        else -> BedtimeState.UNKNOWN
    }

    /** True only when bedtime has just come on (ACTIVE now, not ACTIVE before). */
    fun shouldTurnKeyOff(previous: BedtimeState, now: BedtimeState): Boolean =
        now == BedtimeState.ACTIVE && previous != BedtimeState.ACTIVE

    /**
     * For starts the user did not make (boot, app upgrade, the system's sticky restart): don't bring
     * the key up during bedtime. User-made starts (app open / toggle, go-passive, tile wake) ignore it.
     */
    fun allowBackgroundStart(now: BedtimeState): Boolean = now != BedtimeState.ACTIVE

    /** The `Settings.Global.getInt` default meaning "key not present". */
    const val ABSENT = -1
}

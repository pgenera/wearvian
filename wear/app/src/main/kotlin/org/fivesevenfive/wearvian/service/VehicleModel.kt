package org.fivesevenfive.wearvian.service

/**
 * Rivian body style, decoded from the VIN entirely on the watch (the VIN is already stored in the
 * enrollment, so this needs no companion change or re-enrollment).
 *
 * The 4th VIN character (index 3) is the body style: `T` = R1T (truck), `S` = R1S (SUV). Confirmed
 * against a real R1S VIN (`7PDSGABA…`) and public Rivian VIN documentation. Anything else (R2, a
 * blank/short VIN, an unknown line) maps to [UNKNOWN], which callers treat as the R1S default so the
 * UI never breaks for an unrecognized vehicle.
 *
 * The two body styles differ in the rear closure: R1S has a **liftgate** (open + close), R1T has a
 * **tailgate** (open only — there is no tailgate-close command in Rivian's command registry). See
 * the R1T usages in [org.fivesevenfive.wearvian.ui.ControlScreens] and
 * [org.fivesevenfive.wearvian.tile.KeyTileService].
 */
enum class VehicleModel {
    R1S,
    R1T,
    UNKNOWN;

    val isTruck: Boolean get() = this == R1T

    companion object {
        fun fromVin(vin: String): VehicleModel = when (vin.getOrNull(3)?.uppercaseChar()) {
            'T' -> R1T
            'S' -> R1S
            else -> UNKNOWN
        }
    }
}

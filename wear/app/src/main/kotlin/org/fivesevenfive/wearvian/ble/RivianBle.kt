package org.fivesevenfive.wearvian.ble

import java.util.UUID

/** BLE identifiers for the Rivian phone-key protocol (see docs/PROTOCOL.md). */
object RivianBle {
    /** Local name advertised by the vehicle's phone-key GATT peripheral. */
    const val DEVICE_NAME = "Rivian Phone Key"

    val SERVICE_ACTIVE_ENTRY: UUID = UUID.fromString("52495356-454e-534f-5253-455256494345")
    val CHAR_ACTIVE_ENTRY: UUID = UUID.fromString("5249565F-4D4F-424B-4559-5F5752495445")
    val CHAR_PHONE_ID_VEHICLE_ID: UUID = UUID.fromString("AA49565A-4D4F-424B-4559-5F5752495445")
    val CHAR_PHONE_NONCE_VEHICLE_NONCE: UUID = UUID.fromString("E020A15D-E730-4B2C-908B-51DAF9D41E19")
    val CHAR_VEHICLE_STATUS: UUID = UUID.fromString("afb2e704-842b-4e6a-9bd2-b1b305828f24")

    /**
     * Encrypted active-command channel: the app writes 64-byte AES-128-GCM command
     * frames here (NOT the legacy 0x18 [CHAR_ACTIVE_ENTRY]); the vehicle notifies
     * status/ranging back. See docs/passive-entry-protocol.md.
     */
    val CHAR_ACTIVE_COMMAND: UUID = UUID.fromString("5ae32b92-eafb-471b-afe8-e88eec4a4774")

    /** "RIVIAN READ CHAR" — the continuous drive presence-heartbeat channel. */
    val CHAR_RIVIAN_READ: UUID = UUID.fromString("52495649-414e-2052-4541-442043484152")

    /** Standard Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Parse the vasVehicleId (dashed UUID or 32-hex) into the service UUID the sensors advertise. */
    fun vehicleServiceUuid(vasVehicleId: String): UUID? = runCatching {
        val s = vasVehicleId.trim()
        if (s.contains("-")) {
            UUID.fromString(s)
        } else {
            val h = s.lowercase().removePrefix("0x")
            require(h.length == 32) { "not 32 hex chars" }
            UUID.fromString("${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}")
        }
    }.getOrNull()

    /** Active-entry commands (used in milestone 2). */
    object Command {
        const val WAKE_VEHICLE = "WAKE_VEHICLE"
        const val UNLOCK_ALL_CLOSURES = "UNLOCK_ALL_CLOSURES"
        const val LOCK_ALL_CLOSURES = "LOCK_ALL_CLOSURES"
        const val OPEN_FRUNK = "OPEN_FRUNK"
        const val CLOSE_FRUNK = "CLOSE_FRUNK"
        const val OPEN_ALL_WINDOWS = "OPEN_ALL_WINDOWS"
        const val CLOSE_ALL_WINDOWS = "CLOSE_ALL_WINDOWS"
        const val HONK_AND_FLASH_LIGHTS = "HONK_AND_FLASH_LIGHTS"
    }
}

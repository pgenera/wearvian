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

    /** Standard Client Characteristic Configuration Descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

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

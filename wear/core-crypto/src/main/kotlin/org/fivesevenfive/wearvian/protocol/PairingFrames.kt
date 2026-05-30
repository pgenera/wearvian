package org.fivesevenfive.wearvian.protocol

import org.fivesevenfive.wearvian.crypto.RivianCrypto

/**
 * Byte-level framing for the BLE phone-key pairing handshake, mirroring
 * `ble.pair_phone` in the reference client. Kept framework-free so it can be
 * unit-tested on the JVM.
 *
 * Handshake (phone = BLE central, vehicle = "Rivian Phone Key" peripheral):
 *   1. write [phoneIdBytes] to the Phone-ID/Vehicle-ID characteristic
 *   2. read the vehicle's id back and check [vehicleIdMatches]
 *   3. write [pairingWrite] (nonce ‖ hmac) to the Phone-nonce/Vehicle-nonce char
 *   4. trigger OS bonding (BluetoothDevice.createBond on Android)
 */
object PairingFrames {

    const val PHONE_NONCE_LEN = 16
    const val HMAC_LEN = 32

    /** Rivian ids are dash-separated hex (a UUID-like string); the wire form drops dashes. */
    fun normalizeId(id: String): String = id.replace("-", "").lowercase()

    /** The bytes written to the Phone-ID characteristic (vasPhoneId, dashes stripped). */
    fun phoneIdBytes(vasPhoneId: String): ByteArray = RivianCrypto.fromHex(normalizeId(vasPhoneId))

    /** True when the id echoed back by the vehicle equals the expected vasVehicleId. */
    fun vehicleIdMatches(received: ByteArray, expectedVasVehicleId: String): Boolean =
        RivianCrypto.toHex(received).equals(normalizeId(expectedVasVehicleId), ignoreCase = true)

    /**
     * The 48-byte payload for the nonce characteristic: the 16-byte phone nonce
     * followed by the 32-byte HMAC over that nonce.
     */
    fun pairingWrite(phoneNonce: ByteArray, hmac: ByteArray): ByteArray {
        require(phoneNonce.size == PHONE_NONCE_LEN) { "nonce must be $PHONE_NONCE_LEN bytes" }
        require(hmac.size == HMAC_LEN) { "hmac must be $HMAC_LEN bytes" }
        return phoneNonce + hmac
    }
}

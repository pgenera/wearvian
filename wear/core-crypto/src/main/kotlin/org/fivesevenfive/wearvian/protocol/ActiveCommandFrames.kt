package org.fivesevenfive.wearvian.protocol

import org.fivesevenfive.wearvian.crypto.RivianCrypto
import java.security.SecureRandom

/**
 * Active-entry command + presence-heartbeat framing, reverse-engineered from the
 * official Rivian Android app (`com.rivian.android.consumer`; full notes in
 * `docs/passive-entry-protocol.md`). Pure-JVM so it is unit-testable.
 *
 * All HMACs key on `sessionSecret = HKDF-SHA256(ECDH(phonePriv, vehiclePub))` =
 * [RivianCrypto.deriveSecretKey] (the same key our proven pairing handshake uses).
 *
 *   messageKey = first16(HMAC-SHA256(sessionSecret, CONSTANT_B))   // AES-128
 *   AAD        = pNonce XOR vNonce
 *
 * - Active command (char 0x20, encrypted, 64 B):
 *     0x16 ‖ 0x01 ‖ IV(12) ‖ AES-128-GCM(plaintext, AAD)
 *     plaintext = cmd(2,LE) ‖ HMAC-SHA256(sessionSecret, pNonce ‖ vNonce ‖ counter(4,LE) ‖ cmd)
 * - Drive heartbeat (char 0x1b, plaintext, 37 B):
 *     counter(4,LE) ‖ flag(1) ‖ HMAC-SHA256(sessionSecret, pNonce ‖ vNonce ‖ counter(4,LE) ‖ flag)
 * - Auth nonce (char 0x15, 48 B):  pNonce(16) ‖ HMAC-SHA256(sessionSecret, pNonce)
 *     — byte-identical to [PairingFrames.pairingWrite]; cross-checked in tests.
 */
object ActiveCommandFrames {

    const val TYPE_ACTIVE_CMD_REQUEST: Byte = 0x16
    // Inbound frame types the vehicle notifies on char 0x20 during a live session
    // (decoded 2026-06-06, docs/passive-entry-protocol.md). Same wrapping as the
    // outbound command frame: [type][0x01][12B IV][AES-128-GCM ct+tag].
    const val TYPE_ACTIVE_CMD_RESPONSE: Byte = 0x17 // CommandReturnValue ack, one per command
    const val TYPE_VEHICLE_STATUS: Byte = 0x18      // streamed lock/closure/charge state
    const val VERSION_1: Byte = 0x01
    const val IV_LEN = 12
    /** Smallest decryptable inbound frame: type + version + IV + GCM tag. */
    const val MIN_FRAME_LEN = 2 + IV_LEN + 16

    /** `q60/a` CONSTANT_B — the fixed input to the AES-key HMAC. */
    val CONSTANT_B: ByteArray =
        RivianCrypto.fromHex("07862b2ed8328106a7cdff7b5c1b23bedffddb33a6aa3c82b0fedc784485df77")

    // BLE-sendable active-command codes (2-byte little-endian attrValue), extracted from
    // the decompiled command registry (h60 classes -> em.k2). Complete set of codes with a
    // non-empty BLE byte array. In this app build the OpenLiftgate/OpenTailgate classes were
    // nulled to an empty byte array (the app routes them via the cloud) and their natural
    // code 0x2a is reserved as k2.NONE -- but every open/close pair is adjacent
    // (open = close-1: windows 15/16, tonneau 10/11, frunk 26/27, charge 36/37), so 0x2a is
    // the open-liftgate code the vehicle firmware almost certainly still accepts;
    // OPEN_LIFTGATE below is that pattern-predicted code (verify on-vehicle). Genuinely
    // cloud-only (no plausible BLE code): WakeVehicle, Start/StopCharging, SetChargingLimit,
    // HVAC seat/temperature, GearGuard video. SoC/range are cloud telemetry, not BLE.
    object Cmd {
        // --- Closures / locks ---
        const val UNLOCK_ALL = 0x0003
        const val LOCK_ALL = 0x0006
        const val OPEN_FRUNK = 0x0026
        const val CLOSE_FRUNK = 0x0027
        const val OPEN_LIFTGATE = 0x002a  // pattern-predicted (app reserves it as NONE); verify on-vehicle
        const val CLOSE_LIFTGATE = 0x002b
        const val OPEN_ALL_WINDOWS = 0x0015
        const val CLOSE_ALL_WINDOWS = 0x0016
        const val OPEN_CHARGE_PORT = 0x0036
        const val CLOSE_CHARGE_PORT = 0x0037
        // R1T-only closures (no-op on an R1S)
        const val OPEN_TONNEAU_COVER = 0x0010
        const val CLOSE_TONNEAU_COVER = 0x0011
        const val RELEASE_LEFT_SIDE_BIN = 0x000e
        const val RELEASE_RIGHT_SIDE_BIN = 0x000f
        // --- Alarms / signaling ---
        const val PANIC_ON = 0x0007
        const val PANIC_OFF = 0x0034
        const val FLASH_LIGHTS = 0x006e
        const val ACTIVATE_SOUND = 0x006f
        // --- Gear Guard (security) ---
        const val ENABLE_GEAR_GUARD = 0x0017
        const val DISABLE_GEAR_GUARD = 0x0018
        // --- Cabin preconditioning (set-temp 0x0035 takes a temp arg, not a plain code) ---
        const val CABIN_PRECONDITION_ENABLE = 0x0019
        const val CABIN_PRECONDITION_DISABLE = 0x001a
        // --- Two-factor drive authorization ---
        const val DRIVE_AUTH_ALLOW = 0x0072
        const val DRIVE_AUTH_DENY = 0x0073
        const val DRIVE_AUTH_MOBILE_NOTIF_ENABLE = 0x005e
        const val DRIVE_AUTH_MOBILE_NOTIF_DISABLE = 0x005f
    }

    private fun sessionKey(sharedSecret: ByteArray) = RivianCrypto.deriveSecretKey(sharedSecret)

    /** AES-128 message key = first 16 bytes of HMAC-SHA256(sessionSecret, CONSTANT_B). */
    fun aesKey(sharedSecret: ByteArray): ByteArray =
        RivianCrypto.hmacSha256(sessionKey(sharedSecret), CONSTANT_B).copyOf(16)

    /** AAD = pNonce XOR vNonce (truncated to the shorter length). */
    fun aad(pNonce: ByteArray, vNonce: ByteArray): ByteArray {
        val n = minOf(pNonce.size, vNonce.size)
        return ByteArray(n) { (pNonce[it].toInt() xor vNonce[it].toInt()).toByte() }
    }

    fun le16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    fun le32(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    /** AUTH_PNONCE frame (== [PairingFrames.pairingWrite]); the value written to char 0x15. */
    fun authNonce(sharedSecret: ByteArray, pNonce: ByteArray): ByteArray =
        pNonce + RivianCrypto.hmacSha256(sessionKey(sharedSecret), pNonce)

    /** ACTIVE_COMMAND plaintext (before encryption). */
    fun activeCommandPlaintext(
        sharedSecret: ByteArray, pNonce: ByteArray, vNonce: ByteArray, counter: Int, commandCode: Int,
    ): ByteArray {
        val cmd = le16(commandCode)
        val mac = RivianCrypto.hmacSha256(sessionKey(sharedSecret), pNonce + vNonce + le32(counter) + cmd)
        return cmd + mac
    }

    /** Full encrypted active-command frame written to char 0x20 (64 bytes for a 2-byte code). */
    fun activeCommandFrame(
        sharedSecret: ByteArray, pNonce: ByteArray, vNonce: ByteArray,
        counter: Int, commandCode: Int, iv: ByteArray = randomIv(),
    ): ByteArray {
        require(iv.size == IV_LEN) { "iv must be $IV_LEN bytes" }
        val pt = activeCommandPlaintext(sharedSecret, pNonce, vNonce, counter, commandCode)
        val ct = RivianCrypto.aesGcmEncrypt(aesKey(sharedSecret), iv, aad(pNonce, vNonce), pt)
        return byteArrayOf(TYPE_ACTIVE_CMD_REQUEST, VERSION_1) + iv + ct
    }

    /**
     * Decrypt an inbound 0x20 frame the vehicle notifies during a live session — a
     * command response (0x17) or a vehicle-status report (0x18). Wrapping mirrors the
     * outbound command: `[type][0x01][12B IV][AES-128-GCM ct+tag]`, same AES key and
     * AAD. Returns the plaintext, or null if the frame is too short or the GCM tag
     * doesn't verify (wrong key / not actually an encrypted frame). Lets the watch
     * read closure/lock state from its own session instead of needing a phone snoop.
     */
    fun decryptInbound(
        sharedSecret: ByteArray, pNonce: ByteArray, vNonce: ByteArray, frame: ByteArray,
    ): ByteArray? {
        if (frame.size < MIN_FRAME_LEN) return null
        val iv = frame.copyOfRange(2, 2 + IV_LEN)
        val ct = frame.copyOfRange(2 + IV_LEN, frame.size)
        return runCatching {
            RivianCrypto.aesGcmDecrypt(aesKey(sharedSecret), iv, aad(pNonce, vNonce), ct)
        }.getOrNull()
    }

    /**
     * Brute-probe the decryption of a compact inbound 0x20 frame (0x17 ack / 0x18 status,
     * ~20 B) whose IV is NOT carried in the frame. Our outbound command uses an explicit
     * 12-B IV, but the vehicle's compact replies are `[type][ver][ct][16-B GCM tag]` with an
     * *implicit* IV we must derive. The session key is on-device, so we just try a handful of
     * plausible (IV, AAD) derivations and let GCM's tag tell us which is right — a successful
     * decrypt is definitive (no key material ever leaves the watch). Returns
     * "iv=… aad=… pt=…" for the first combo that authenticates, or null if none do.
     *
     * `csn` is the command sequence number this frame relates to (for an ack: the csn of the
     * command just sent; for status it may not matter). We try csn and csn-1, LE and BE.
     */
    fun probeInbound(
        sharedSecret: ByteArray, pNonce: ByteArray, vNonce: ByteArray, csn: Int, frame: ByteArray,
    ): String? {
        if (frame.size < 2 + 16) return null
        val key = aesKey(sharedSecret)
        val ct = frame.copyOfRange(2, frame.size) // strip [type][ver]; rest = ct‖tag
        fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        val ivs = buildList {
            add("zero" to ByteArray(IV_LEN))
            add("csn_le" to (le32(csn) + ByteArray(8)))
            add("csn-1_le" to (le32(csn - 1) + ByteArray(8)))
            add("csn_be" to (be32(csn) + ByteArray(8)))
            add("csn-1_be" to (ByteArray(8) + be32(csn - 1)))
            add("pNonce12" to pNonce.copyOf(IV_LEN))
            add("vNonce12" to vNonce.copyOf(IV_LEN))
            add("xor12" to aad(pNonce, vNonce).copyOf(IV_LEN))
        }
        val aads = buildList {
            add("xor" to aad(pNonce, vNonce))
            add("none" to ByteArray(0))
            add("pNonce" to pNonce)
            add("vNonce" to vNonce)
        }
        for ((ivName, iv) in ivs) for ((aadName, ad) in aads) {
            val pt = runCatching { RivianCrypto.aesGcmDecrypt(key, iv, ad, ct) }.getOrNull()
            if (pt != null) return "iv=$ivName aad=$aadName pt=${RivianCrypto.toHex(pt)}"
        }
        return null
    }

    /** PASSIVE_ENTRY heartbeat frame written (unencrypted) to char 0x1b (37 bytes). */
    fun heartbeatFrame(
        sharedSecret: ByteArray, pNonce: ByteArray, vNonce: ByteArray, counter: Int, flag: Byte,
    ): ByteArray {
        val flagArr = byteArrayOf(flag)
        val mac = RivianCrypto.hmacSha256(sessionKey(sharedSecret), pNonce + vNonce + le32(counter) + flagArr)
        return le32(counter) + flagArr + mac
    }

    fun randomIv(): ByteArray = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
}

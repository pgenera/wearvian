package org.fivesevenfive.wearvian.protocol

import org.fivesevenfive.wearvian.crypto.RivianCrypto
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Signed-params authorization frame written to the **"Q" characteristic**
 * (`0823DA14-040B-4914-BF7C-450AFA2850DA`, PLAIN_DATA_IN) to authorize a sensor
 * BLE bond. Reverse-engineered from `com.rivian.android.consumer` (`s60/b0.c`
 * builds the protobuf, `s60/b0.e` signs it; protobuf schema recovered from
 * `classes4.dex`). See docs/passive-entry-protocol.md and
 * docs/signed-params-protobuf-smali.txt.
 *
 * A sensor won't complete SMP pairing until it receives this proof that the phone
 * holds the enrollment key. The wire form is a protobuf envelope:
 *
 *   n1 { e2 signed_msg = 1 }
 *   e2 { bytes payload = 1; uint32 seq = 2; (bools 3,4 default-false); c2 sig = 5 }
 *   c2 { Algorithm type = 1 [HMAC_SHA256=1]; bytes mac = 2 }
 *   payload = p1 { repeated l1 } .toByteArray()
 *   l1 { uint32 transactionId = 1; oneof content { r = 5 | f0 = 9 } }
 *   r  { x = 1 }   x {}   f0 { s1 = 1 }   s1 { Model model=1 (DEFAULT omitted); string modelRaw=2 }
 *
 * Signing key/preimage are the same session-HMAC family as commands/heartbeats:
 *   mac = HMAC-SHA256(sessionSecret,
 *           payload ‖ counter(4,LE) ‖ phoneId(16,BE) ‖ pNonce(16) ‖ vNonce(16))
 *
 * NOTE: the oneof case numbers, the empty `x`, and the `Model`/`modelRaw` values are
 * reconstructed from smali; they don't affect the HMAC (computed over our own
 * serialized payload) but must be parseable by the car. Lock byte-exact against a
 * reprovision btsnoop (`protoc --decode_raw`) or the dex2jar oracle before relying on it.
 */
object SignedParams {

    private const val WIRE_VARINT = 0
    private const val WIRE_LEN = 2

    /** Base-128 varint (protobuf). */
    fun varint(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>(10)
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) { out.add(b.toByte()); break }
            out.add((b or 0x80).toByte())
        }
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int): ByteArray = varint(((field shl 3) or wire).toLong())
    private fun lenField(field: Int, bytes: ByteArray): ByteArray =
        tag(field, WIRE_LEN) + varint(bytes.size.toLong()) + bytes
    private fun varintField(field: Int, value: Int): ByteArray = tag(field, WIRE_VARINT) + varint(value.toLong())

    // ---- message builders (schema from classes4.dex) ----
    private fun x(): ByteArray = ByteArray(0)                         // x {} empty
    private fun r(): ByteArray = lenField(1, x())                     // r { x = 1 }
    private fun s1(modelRaw: String): ByteArray =                     // s1 { model=1 DEFAULT omitted; modelRaw=2 }
        if (modelRaw.isEmpty()) ByteArray(0) else lenField(2, modelRaw.toByteArray(Charsets.UTF_8))
    private fun f0(modelRaw: String): ByteArray = lenField(1, s1(modelRaw))  // f0 { s1 = 1 }
    private fun l1WithR(txid: Int): ByteArray = varintField(1, txid) + lenField(5, r())
    private fun l1WithF0(txid: Int, modelRaw: String): ByteArray = varintField(1, txid) + lenField(9, f0(modelRaw))

    /** The signed payload: `p1 { repeated l1 }` = l1{r{x}} ‖ l1{f0{s1}}, serialized. */
    fun payload(txid1: Int = 1, txid2: Int = 3, modelRaw: String = ""): ByteArray =
        lenField(1, l1WithR(txid1)) + lenField(1, l1WithF0(txid2, modelRaw))

    /** phoneId UUID → 16 bytes big-endian (`s60.h.p` = `o(uuid, BIG_ENDIAN)`). */
    fun phoneIdBigEndian(phoneId: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(phoneId.mostSignificantBits).putLong(phoneId.leastSignificantBits).array()

    /** The HMAC over the payload, exactly as `s60/b0.e` assembles it. */
    fun sign(
        sharedSecret: ByteArray, pNonce: ByteArray, vNonce: ByteArray, phoneId: UUID,
        counter: Int, payload: ByteArray,
    ): ByteArray {
        val preimage = payload + ActiveCommandFrames.le32(counter) + phoneIdBigEndian(phoneId) + pNonce + vNonce
        return RivianCrypto.hmacSha256(RivianCrypto.deriveSecretKey(sharedSecret), preimage)
    }

    /** Full `n1` signed-params frame to write to the Q characteristic. */
    fun frame(
        sharedSecret: ByteArray, pNonce: ByteArray, vNonce: ByteArray, phoneId: UUID,
        sequenceNum: Int, txid1: Int = 1, txid2: Int = 3, modelRaw: String = "",
    ): ByteArray {
        val payload = payload(txid1, txid2, modelRaw)
        val mac = sign(sharedSecret, pNonce, vNonce, phoneId, sequenceNum, payload)
        val c2 = varintField(1, ALGO_HMAC_SHA256) + lenField(2, mac)              // c2 { type=1; data=mac }
        val e2 = lenField(1, payload) + varintField(2, sequenceNum) + lenField(5, c2)  // bools 3,4 default-false → omitted
        return lenField(1, e2)                                                    // n1 { signed_msg = 1 }
    }

    /** `b2` Algorithm enum: HMAC_SHA256 = 1. */
    const val ALGO_HMAC_SHA256 = 1
}

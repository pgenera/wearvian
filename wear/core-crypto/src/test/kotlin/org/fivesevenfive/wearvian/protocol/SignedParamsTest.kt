package org.fivesevenfive.wearvian.protocol

import org.fivesevenfive.wearvian.crypto.RivianCrypto
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Structural + self-consistency tests for the signed-params (Q-char) bond
 * authorization. These validate that our hand-rolled protobuf encodes the
 * reconstructed schema and that the embedded HMAC covers the payload exactly as
 * `s60/b0.e` specifies. Byte-exact match to the official app is locked separately
 * against a reprovision btsnoop / dex2jar oracle (see docs).
 */
class SignedParamsTest {

    private val sharedSecret = ByteArray(32) { (it * 7 + 1).toByte() }
    private val pNonce = ByteArray(16) { it.toByte() }
    private val vNonce = ByteArray(16) { (it + 0x40).toByte() }
    private val phoneId = UUID.fromString("aa49565a-4d4f-424b-4559-5f5752495445")

    // ---- minimal protobuf reader for assertions ----
    private data class PF(val num: Int, val wire: Int, val bytes: ByteArray?, val v: Long)
    private fun parse(b: ByteArray): List<PF> {
        val out = ArrayList<PF>()
        var p = 0
        fun vint(): Long {
            var r = 0L; var s = 0
            while (true) { val x = b[p++].toInt() and 0xFF; r = r or ((x and 0x7F).toLong() shl s); if (x < 0x80) break; s += 7 }
            return r
        }
        while (p < b.size) {
            val tag = vint().toInt(); val num = tag ushr 3
            when (val wire = tag and 7) {
                0 -> out.add(PF(num, 0, null, vint()))
                2 -> { val n = vint().toInt(); out.add(PF(num, 2, b.copyOfRange(p, p + n), 0)); p += n }
                else -> error("unexpected wire type $wire")
            }
        }
        return out
    }
    private fun field(b: ByteArray, num: Int): PF? = parse(b).firstOrNull { it.num == num }
    private fun fields(b: ByteArray, num: Int): List<PF> = parse(b).filter { it.num == num }

    @Test
    fun frameDecodesToEnvelopeSchema() {
        val frame = SignedParams.frame(sharedSecret, pNonce, vNonce, phoneId, sequenceNum = 4)
        // n1 { e2 signed_msg = 1 }
        val e2 = assertNotNull(field(frame, 1)?.bytes, "n1.signed_msg(1) present")
        // e2 { payload=1, seq=2, sig=5 }  (bools 3,4 default-false omitted)
        val payload = assertNotNull(field(e2, 1)?.bytes, "e2.payload(1)")
        assertEquals(4L, field(e2, 2)?.v, "e2.sequence_num(2)")
        val c2 = assertNotNull(field(e2, 5)?.bytes, "e2.signature(5)")
        // c2 { type=1 [HMAC_SHA256=1], data=2 (32-byte mac) }
        assertEquals(SignedParams.ALGO_HMAC_SHA256.toLong(), field(c2, 1)?.v, "c2.type(1)=HMAC_SHA256")
        val mac = assertNotNull(field(c2, 2)?.bytes, "c2.data(2) mac")
        assertEquals(32, mac.size, "HMAC-SHA256 is 32 bytes")
        assertContentEquals(SignedParams.payload(), payload, "e2.payload == p1 payload")
    }

    @Test
    fun payloadDecodesToTwoL1WithCorrectOneofCases() {
        val payload = SignedParams.payload(txid1 = 1, txid2 = 3)
        val msgs = fields(payload, 1) // p1 { repeated l1 msgs = 1 }
        assertEquals(2, msgs.size, "two l1 messages")
        val l1a = msgs[0].bytes!!; val l1b = msgs[1].bytes!!
        assertEquals(1L, field(l1a, 1)?.v, "l1#1.transactionId(1) = 1")
        assertEquals(3L, field(l1b, 1)?.v, "l1#2.transactionId(1) = 3")
        assertNotNull(field(l1a, 5)?.bytes, "l1#1.content oneof case 5 (r)")
        assertNotNull(field(l1b, 9)?.bytes, "l1#2.content oneof case 9 (f0)")
        // r { x = 1 } ; x {} empty
        val r = field(l1a, 5)!!.bytes!!
        assertContentEquals(ByteArray(0), field(r, 1)!!.bytes!!, "r.x(1) is empty")
    }

    @Test
    fun embeddedMacCoversPayloadPerSpec() {
        val seq = 7
        val frame = SignedParams.frame(sharedSecret, pNonce, vNonce, phoneId, sequenceNum = seq)
        val e2 = field(frame, 1)!!.bytes!!
        val payload = field(e2, 1)!!.bytes!!
        val mac = field(field(e2, 5)!!.bytes!!, 2)!!.bytes!!

        // Independently recompute: HMAC-SHA256(sessionSecret,
        //   payload ‖ counter(4,LE) ‖ phoneId(16,BE) ‖ pNonce ‖ vNonce)
        val preimage = payload + ActiveCommandFrames.le32(seq) +
            SignedParams.phoneIdBigEndian(phoneId) + pNonce + vNonce
        val mac2 = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(RivianCrypto.deriveSecretKey(sharedSecret), "HmacSHA256"))
            doFinal(preimage)
        }
        assertContentEquals(mac2, mac, "embedded mac == independent HMAC over the spec preimage")
    }

    @Test
    fun phoneIdIsBigEndian16() {
        val be = SignedParams.phoneIdBigEndian(phoneId)
        assertEquals(16, be.size)
        // aa49565a... big-endian => first byte 0xaa
        assertEquals(0xAA.toByte(), be[0])
        assertEquals(0x45.toByte(), be[15]) // ...49565445 -> last byte 0x45
    }

    @Test
    fun varintEncodesMultiByte() {
        assertContentEquals(byteArrayOf(0x00), SignedParams.varint(0))
        assertContentEquals(byteArrayOf(0x7F), SignedParams.varint(127))
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x01), SignedParams.varint(128))
        assertContentEquals(byteArrayOf(0xAC.toByte(), 0x02), SignedParams.varint(300))
    }
}

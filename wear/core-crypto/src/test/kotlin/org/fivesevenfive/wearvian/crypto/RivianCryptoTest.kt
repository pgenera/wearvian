package org.fivesevenfive.wearvian.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Parity test: these vectors were produced by the reference Python implementation
 * (`bretterer/rivian-python-client`, `utils.py`) so a match proves our JVM crypto
 * is byte-for-byte interoperable with the community-tested signer that the vehicle
 * accepts. See docs/passive-entry-protocol.md for the crypto details.
 */
class RivianCryptoTest {

    // Fixed phone private key (base64 of PEM PKCS#8) and vehicle public key (hex X9.62).
    private val phonePrivPemB64 =
        "LS0tLS1CRUdJTiBQUklWQVRFIEtFWS0tLS0tCk1JR0hBZ0VBTUJNR0J5cUdTTTQ5QWdFR0NDcUdTTTQ5QXdFSEJHMHdhd0lCQVFRZ2c1WUE3aFJiRkZmVEZ0dkMKNTM4VVAzSmtZa1VLSHZDaGpScDFLZDk3dC9DaFJBTkNBQVNYbmxwQWgxNFN2Y0ZqeVp3WlhaeWFYaWQrUXAyNgp2MnB2eE5TaVlYTWZhajdyN3MwN3crQS9WK1ZTRFdPaExUTzVTcGFUcHZiM2c2VGR6TzhjOTJzdQotLS0tLUVORCBQUklWQVRFIEtFWS0tLS0tCg=="
    private val vehiclePubHex =
        "04461afba329dff52046c2b896b627b1d45380f6c02fc0f1877b6bcb1853d6c30cf459ff5aec8a5a6525befd04d5b2ad780fab03f6d7143bc384b5f2a8bec544c5"
    private val nonceHex = "000102030405060708090a0b0c0d0e0f"

    private val expectedDerivedKeyHex =
        "7b0b49aa5ec28aca8fc58989669fad620099f72a8260705a502a5c2af2fa328c"
    private val expectedPairingHmacHex =
        "c99f12da36b6c1718bd71eb331471e2dd12d171a5859e74b37b83babe4c0f5d4"
    private val expectedCommandHmacHex =
        "ebb02d388be9685724840a5c3024c5eccd8c7a3041a2d029e0ffcac81bbd9f31"

    private fun sharedSecret(): ByteArray {
        val priv = RivianKeys.decodePrivateKeyPemBase64(phonePrivPemB64)
        val pub = RivianKeys.decodePublicKeyHex(vehiclePubHex)
        return RivianKeys.ecdh(priv, pub)
    }

    @Test
    fun derivedKeyMatchesReference() {
        val derived = RivianCrypto.deriveSecretKey(sharedSecret())
        assertEquals(expectedDerivedKeyHex, RivianCrypto.toHex(derived))
    }

    @Test
    fun pairingHmacMatchesReference() {
        val hmac = RivianCrypto.signNonce(sharedSecret(), RivianCrypto.fromHex(nonceHex))
        assertEquals(expectedPairingHmacHex, RivianCrypto.toHex(hmac))
    }

    @Test
    fun commandHmacMatchesReference() {
        val hmac = RivianCrypto.signCommand(sharedSecret(), "UNLOCK_ALL_CLOSURES", "1700000000")
        assertEquals(expectedCommandHmacHex, RivianCrypto.toHex(hmac))
    }

    @Test
    fun publicKeyHexRoundTrips() {
        val pub = RivianKeys.decodePublicKeyHex(vehiclePubHex)
        assertEquals(vehiclePubHex, RivianKeys.encodePublicKeyHex(pub))
    }

    @Test
    fun hkdfExpandsBeyondOneBlock() {
        // Sanity check the multi-block expand path (length > 32).
        val out = RivianCrypto.hkdfSha256(ByteArray(32) { 1 }, 48)
        assertEquals(48, out.size)
    }
}

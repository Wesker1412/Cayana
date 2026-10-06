package com.cayana.backup.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Standard RFC 5869 HKDF implementation using HMAC-SHA256.
 */
object HkdfSha256 {

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32

    /**
     * Derives a cryptographic key of [length] bytes (up to 255 * 32 bytes) from input key material.
     *
     * @param ikm Input Keying Material (e.g. 256-bit root key)
     * @param salt Optional salt (if empty, 32 zero bytes are used)
     * @param info Application-specific context/info string
     * @param length Desired output key length in bytes (default 32)
     */
    fun deriveKey(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 32
    ): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        val prk = extract(actualSalt, ikm)
        return expand(prk, info, length)
    }

    private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(salt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * HASH_LEN) { "Requested HKDF length exceeds maximum allowable output" }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        val n = (length + HASH_LEN - 1) / HASH_LEN
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var bytesCopied = 0

        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()

            val toCopy = minOf(HASH_LEN, length - bytesCopied)
            System.arraycopy(t, 0, okm, bytesCopied, toCopy)
            bytesCopied += toCopy
        }

        return okm
    }
}

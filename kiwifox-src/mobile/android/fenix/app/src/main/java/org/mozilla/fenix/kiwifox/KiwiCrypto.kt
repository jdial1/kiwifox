/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads the ".kfex" container produced by the desktop exporter (browser-export/kfex.ps1). Standard-library
 * crypto only, so it needs no AES-zip library (Android's zip has no AES) and matches what .NET on Windows
 * can produce (which has no AES-GCM):
 *
 *   file   = magic(5) || salt(16) || iv(16) || ciphertext || mac(32)
 *   magic  = ASCII "KFEX1"
 *   dk     = PBKDF2-HMAC-SHA256(passphrase, salt, 200000, 64); aesKey = dk[0..31], macKey = dk[32..63]
 *   cipher = AES-256-CBC / PKCS7 of a plain (unencrypted) inner zip
 *   mac    = HMAC-SHA256(macKey, magic || salt || iv || ciphertext)   (encrypt-then-MAC, verified first)
 */
object KiwiCrypto {

    class KfexError(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val MAGIC = "KFEX1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 16
    private const val MAC_LEN = 32
    private const val ITERATIONS = 200_000
    private const val DK_LEN = 64

    /** Decrypts and verifies [container], returning the raw bytes of the inner zip. Throws [KfexError]. */
    fun openKfex(container: ByteArray, passphrase: String): ByteArray {
        val minLen = MAGIC.size + SALT_LEN + IV_LEN + MAC_LEN + 16 // + at least one cipher block
        if (container.size < minLen) throw KfexError("File is too small to be a KiwiFox export.")
        if (!regionEquals(container, 0, MAGIC)) throw KfexError("Not a KiwiFox export (bad header).")

        var p = MAGIC.size
        val salt = container.copyOfRange(p, p + SALT_LEN); p += SALT_LEN
        val iv = container.copyOfRange(p, p + IV_LEN); p += IV_LEN
        val ctStart = p
        val ctEnd = container.size - MAC_LEN
        if (ctEnd <= ctStart || (ctEnd - ctStart) % 16 != 0) throw KfexError("Corrupt file (bad length).")
        val ct = container.copyOfRange(ctStart, ctEnd)
        val mac = container.copyOfRange(ctEnd, container.size)

        val dk = pbkdf2HmacSha256(passphrase.toByteArray(Charsets.UTF_8), salt, ITERATIONS, DK_LEN)
        val aesKey = dk.copyOfRange(0, 32)
        val macKey = dk.copyOfRange(32, 64)

        // Encrypt-then-MAC: verify the tag over magic||salt||iv||ct before touching the ciphertext.
        val hmac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }
        hmac.update(container, 0, ctEnd)
        val expected = hmac.doFinal()
        if (!MessageDigest.isEqual(expected, mac)) {
            throw KfexError("Wrong passphrase, or the file is damaged.")
        }

        return try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
                doFinal(ct)
            }
        } catch (e: Exception) {
            throw KfexError("Could not decrypt the file.", e)
        }
    }

    /** Extracts a plain (unencrypted) zip's entries into a name -> bytes map. */
    fun unzip(zipBytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    out[entry.name.substringAfterLast('/')] = zis.readBytes()
                }
                zis.closeEntry()
            }
        }
        return out
    }

    /** Convenience: decrypt a .kfex container straight to its inner files. */
    fun openKfexEntries(container: ByteArray, passphrase: String): Map<String, ByteArray> =
        unzip(openKfex(container, passphrase))

    private fun regionEquals(data: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (data.size < offset + prefix.size) return false
        for (i in prefix.indices) if (data[offset + i] != prefix[i]) return false
        return true
    }

    /** PBKDF2-HMAC-SHA256, implemented on top of Mac so it works on every supported API level. */
    private fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val hLen = mac.macLength
        val out = ByteArray(dkLen)
        var offset = 0
        var block = 1
        while (offset < dkLen) {
            mac.update(salt)
            mac.update(byteArrayOf(
                (block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte(),
            ))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val n = minOf(hLen, dkLen - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            block++
        }
        return out
    }
}

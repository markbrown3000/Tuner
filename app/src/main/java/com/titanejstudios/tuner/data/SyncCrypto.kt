package com.titanejstudios.tuner.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts playlist passwords before they go into your Google Drive sync file, so the file never
 * holds them in plain text. The key comes from the Google account the devices sign in with (each
 * device signed in to the same account can read them; Flicker on the PC too).
 *
 * Plain Java only, so the PC app shares this file.
 */
object SyncCrypto {
    private const val PREFIX = "enc1:"
    private const val PEPPER = "Flicker playlists · Titan EJ Studios · v1"
    private val random = SecureRandom()

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    /** "enc1:" + salt + iv + ciphertext (hex). [account] is the Google account's email address. */
    fun encrypt(plain: String, account: String): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(account, salt), GCMParameterSpec(128, iv))
        val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + hex(salt) + ":" + hex(iv) + ":" + hex(out)
    }

    /** The plain text; a value that was never encrypted comes back as it is; null if it can't be read with this account. */
    fun decrypt(value: String, account: String?): String? {
        if (!isEncrypted(value)) return value
        return runCatching {
            val (salt, iv, body) = value.removePrefix(PREFIX).split(':').map { unhex(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(account ?: "", salt), GCMParameterSpec(128, iv))
            String(c.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun key(account: String, salt: ByteArray): SecretKeySpec {
        // SHA-1 here is only PBKDF2's building block (the variant every Android version has).
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val spec = PBEKeySpec((account.trim().lowercase() + "|" + PEPPER).toCharArray(), salt, 20_000, 256)
        return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

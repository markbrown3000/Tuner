package com.titanejstudios.tuner.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts playlist passwords kept on this device, with a key held by Android's own key store
 * (it never leaves the device). If the key store isn't working on a TV, passwords are kept as
 * before rather than lost.
 */
object LocalCrypto {
    private const val PREFIX = "ks1:"
    private const val ALIAS = "flicker_secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val body = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(c.iv + body, Base64.NO_WRAP)
        }.onFailure { AppLog.warn("Couldn't encrypt a password on this device – keeping it as before", it) }.getOrDefault(plain)
    }

    fun open(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        return runCatching {
            val all = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            String(c.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
        }.onFailure { AppLog.warn("Couldn't read a saved password – type it again in Settings › Playlists", it) }.getOrDefault("")
    }
}

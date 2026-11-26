// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore AES/GCM secret storage for API keys, Git PATs,
 * and MCP auth headers/env secrets. Never included in backups or logs.
 */
class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences("yscode_secrets_enc", Context.MODE_PRIVATE)
    private val keyAlias = "yscode_master_aes_key"

    fun putSecret(alias: String, plaintext: String) {
        if (plaintext.isBlank()) {
            deleteSecret(alias)
            return
        }
        val encoded = encrypt(plaintext)
        prefs.edit().putString(alias, encoded).apply()
    }

    fun getSecret(alias: String?): String? {
        if (alias.isNullOrBlank()) return null
        val blob = prefs.getString(alias, null) ?: return null
        return runCatching { decrypt(blob) }.getOrNull()
    }

    fun hasSecret(alias: String?): Boolean {
        if (alias.isNullOrBlank()) return false
        return prefs.contains(alias)
    }

    fun deleteSecret(alias: String?) {
        if (alias.isNullOrBlank()) return
        prefs.edit().remove(alias).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plaintext: String): String {
        val secretKey = getOrCreateKey()
        if (secretKey == null) {
            // Fallback only when AndroidKeyStore is unavailable (e.g. JVM Robolectric tests)
            return "RAW:" + Base64.encodeToString(plaintext.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(1 + iv.size + ciphertext.size)
        combined[0] = iv.size.toByte()
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
        return "GCM:" + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        if (blob.startsWith("RAW:")) {
            val raw = Base64.decode(blob.removePrefix("RAW:"), Base64.NO_WRAP)
            return String(raw, Charsets.UTF_8)
        }
        val payload = blob.removePrefix("GCM:")
        val combined = Base64.decode(payload, Base64.NO_WRAP)
        val ivLen = combined[0].toInt() and 0xFF
        val iv = combined.copyOfRange(1, 1 + ivLen)
        val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
        val secretKey = getOrCreateKey() ?: error("Keystore unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey) ?: run {
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        }
    }.getOrNull()

    companion object {
        fun maskSecret(secret: String?): String {
            if (secret.isNullOrBlank()) return ""
            return if (secret.length <= 8) "••••"
            else "${secret.take(3)}••••${secret.takeLast(4)}"
        }
    }
}

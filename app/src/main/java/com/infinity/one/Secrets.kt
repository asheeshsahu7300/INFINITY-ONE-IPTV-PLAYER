package com.infinity.one

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small texts (e.g. playlist addresses with login data) with a key from the
 * Android Keystore. The key never leaves the device. Without a keystore (some boxes) encrypt() returns null,
 * and the app then stores plain text as before – so nothing is ever lost.
 */
object Secrets {
    /** True once a value had to be stored as plain text because the keystore is unavailable. */
    @Volatile var plainFallback = false

    private const val ALIAS = "infinity_secrets_v1"
    private const val PREFIX = "v1:"

    private fun key(): SecretKey? {
        for (attempt in 0 until 2) {
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val k = (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                    init(
                        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build()
                    )
                    generateKey()
                }
                if (k != null) return k
            } catch (e: Exception) {
                if (attempt == 0) Thread.sleep(50)
            }
        }
        return null
    }

    fun encrypt(plain: String): String? = try {
        val k = key() ?: run { plainFallback = true; return null }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k)
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    fun decrypt(token: String): String? = try {
        if (!token.startsWith(PREFIX)) null
        else {
            val parts = token.removePrefix(PREFIX).split(":")
            val k = key() ?: return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Secondary obfuscated storage fallback. Ensures playlists and login configurations
 * are never lost even if Android Keystore keys are invalidated or unavailable after
 * OS updates, box reboots, or hardware keystore restarts.
 */
object SafeStore {
    private const val MASK = 0x5C.toByte()

    fun obfuscate(input: String): String {
        val b = input.toByteArray(Charsets.UTF_8)
        val out = ByteArray(b.size)
        for (i in b.indices) {
            out[i] = (b[i].toInt() xor MASK.toInt()).toByte()
        }
        return try {
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(out)
        }
    }

    fun deobfuscate(input: String?): String? {
        if (input == null) return null
        if (input.isEmpty()) return ""
        return try {
            val b = try {
                Base64.decode(input, Base64.NO_WRAP)
            } catch (_: Throwable) {
                java.util.Base64.getDecoder().decode(input)
            }
            val out = ByteArray(b.size)
            for (i in b.indices) {
                out[i] = (b[i].toInt() xor MASK.toInt()).toByte()
            }
            String(out, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

package org.recloud.music.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedPreference private constructor(context: Context, private val entry: String) {
    companion object {
        fun apiKey(context: Context) = EncryptedPreference(context, "data")
        fun googleAccount(context: Context) = EncryptedPreference(context, "account")
    }

    private val prefs = context.getSharedPreferences("youtube-config", 0)
    private val alias = "youtube-api-key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(alias, null) as? SecretKey
            ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generateKey()
            }
    }

    fun read(): String =
        runCatching {
                val bytes =
                    Base64.decode(prefs.getString(entry, null) ?: return "", Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                )
                String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            }
            .getOrDefault("")

    fun save(value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(entry).apply()
            return
        }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(value.trim().toByteArray())
        prefs.edit().putString(entry, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }
}

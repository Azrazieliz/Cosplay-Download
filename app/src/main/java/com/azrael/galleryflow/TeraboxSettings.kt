package com.azrael.galleryflow

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
 * Cloud destination is opt-in. OAuth access tokens are encrypted with the Android
 * Keystore and are never written to the source tree, Logcat or unencrypted prefs.
 * TeraBox grants Open Platform credentials to approved applications separately.
 */
object TeraboxSettings {
    private const val PREFS = "kyora_terabox"
    private const val KEY_ALIAS = "kyora-terabox-oauth-v1"
    private const val TOKEN_KEY = "encrypted_oauth_access_token"
    private const val DIRECTORY_KEY = "remote_directory"
    private const val ENABLED_KEY = "cloud_enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ENABLED_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    fun remoteDirectory(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(DIRECTORY_KEY, "").orEmpty()

    fun setRemoteDirectory(context: Context, directory: String) {
        require(validDirectory(directory)) {
            "Use your full TeraBox Open Platform application folder."
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(DIRECTORY_KEY, directory.trimEnd('/')).apply()
    }

    fun validDirectory(directory: String): Boolean {
        val s = directory.trim().trimEnd('/')
        return s.startsWith("/") && s.length > 10 && !s.contains("..") &&
            !s.contains('?') && !s.contains('#') && !s.contains('\\') &&
            !s.contains('\r') && !s.contains('\n') &&
            s != "/" && java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
                .let { normalized ->
                    normalized.startsWith("/From:", ignoreCase = true) &&
                    normalized.indexOf("/", startIndex = 7) >= 0
                }
    }

    fun isConfigured(context: Context): Boolean =
        accessToken(context) != null && validDirectory(remoteDirectory(context))

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
            }.generateKey()
    }

    fun saveAccessToken(context: Context, token: String) {
        val value = token.trim()
        require(value.isNotEmpty()) { "Access token is empty" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(
            cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(TOKEN_KEY, "$iv:$ciphertext").apply()
    }

    fun clearAccessToken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(TOKEN_KEY).putBoolean(ENABLED_KEY, false).apply()
    }

    fun accessToken(context: Context): String? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TOKEN_KEY, null) ?: return null
        return runCatching {
            val parts = encoded.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }
}

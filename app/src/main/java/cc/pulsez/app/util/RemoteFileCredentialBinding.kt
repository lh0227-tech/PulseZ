package cc.pulsez.app.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cc.pulsez.app.net.BaseUrl
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

internal const val REMOTE_FILE_BINDING_KEY_ALIAS = "pulsez_remote_file_binding_hmac_v1"

/**
 * Creates a local, non-exported credential-binding identifier for remote-file safety checks.
 * It identifies only the exact configured endpoint + API credential under this installation's local
 * HMAC key; it does not identify a DeepSeek account. The credential is only fed to the MAC in memory.
 */
internal class RemoteFileCredentialBinding(
    private val keyProvider: HmacKeyProvider = AndroidKeystoreHmacKeyProvider(),
) {
    /** Returns null on any uncertainty; callers must then keep old remote IDs unknown and skip requests. */
    fun identifier(baseUrl: String, apiKey: String, hasKnownRecords: Boolean): String? {
        val endpoint = BaseUrl.normalize(baseUrl)
        val credential = apiKey.trim() // FilesClient uses this exact credential form.
        if (endpoint.isBlank() || credential.isBlank()) return null

        return try {
            val secretKey = keyProvider.getOrCreate(hasKnownRecords) ?: return null
            val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
            mac.init(secretKey)
            mac.update(DOMAIN)
            updateLengthPrefixed(mac, endpoint.toByteArray(StandardCharsets.UTF_8))
            updateLengthPrefixed(mac, credential.toByteArray(StandardCharsets.UTF_8))
            PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal())
        } catch (_: Exception) {
            null
        }
    }

    private fun updateLengthPrefixed(mac: Mac, bytes: ByteArray) {
        mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        mac.update(bytes)
    }

    private companion object {
        const val PREFIX = "hmac-v1:"
        val DOMAIN = "pulsez.remote-file.binding.v1\u0000".toByteArray(StandardCharsets.UTF_8)
    }
}

/** Small test seam; production always uses Android Keystore. */
internal fun interface HmacKeyProvider {
    fun getOrCreate(hasKnownRecords: Boolean): SecretKey?
}

/**
 * Uses an independent Android Keystore HMAC key. If the alias disappears while bound records exist,
 * do not make a replacement key: it could never match the old records safely.
 *
 * Android Keystore HMAC-SHA256 uses non-exportable key material; official references:
 * https://developer.android.com/privacy-and-security/keystore
 * https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec
 * https://developer.android.com/reference/android/security/keystore/KeyProperties#KEY_ALGORITHM_HMAC_SHA256
 */
private class AndroidKeystoreHmacKeyProvider : HmacKeyProvider {
    override fun getOrCreate(hasKnownRecords: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(REMOTE_FILE_BINDING_KEY_ALIAS, null)
        if (entry != null) return (entry as? KeyStore.SecretKeyEntry)?.secretKey
        if (keyStore.containsAlias(REMOTE_FILE_BINDING_KEY_ALIAS) || hasKnownRecords) return null

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                REMOTE_FILE_BINDING_KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN,
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

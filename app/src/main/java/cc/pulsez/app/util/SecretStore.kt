package cc.pulsez.app.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密钥存储：用 Android Keystore 生成 AES-256-GCM 主密钥，密文落应用私有目录。
 *
 * 刻意不使用已弃用的 androidx.security:security-crypto。
 * 这里只存"用户自己填的 API Key"，不存聊天内容。
 */
class SecretStore internal constructor(
    private val file: File,
    private val deleteKeyEntry: () -> Unit,
    private val keyAliasPresent: () -> Boolean,
) {

    constructor(context: Context) : this(
        File(context.filesDir, "secrets.bin"),
        { keyStore().deleteEntry(KEY_ALIAS) },
        { keyStore().containsAlias(KEY_ALIAS) },
    )

    @Synchronized
    fun put(name: String, value: String) {
        val all = readAll().toMutableMap()
        all[name] = encrypt(value)
        writeAll(all)
    }

    @Synchronized
    fun get(name: String): String? = readAll()[name]?.let { runCatching { decrypt(it) }.getOrNull() }

    @Synchronized
    fun remove(name: String) {
        val all = readAll().toMutableMap()
        all.remove(name)
        writeAll(all)
    }

    /** 清空所有密文，并删除 Keystore 里的主密钥；任何残留或 IO 失败都向调用方抛出。 */
    @Synchronized
    fun clear() {
        var failure: Exception? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (error: Exception) {
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }

        attempt {
            if (file.exists()) {
                val deleted = file.delete()
                if (!deleted && file.exists()) throw IOException("密文文件删除失败")
            }
        }
        // 即使文件删除失败也继续删主密钥，最后统一报告未完成，避免留下可解密的密文。
        attempt(deleteKeyEntry)
        attempt {
            if (file.exists()) throw IOException("密文文件仍存在")
        }
        attempt {
            if (keyAliasPresent()) throw IOException("Keystore 主密钥仍存在")
        }

        // 依据：ExportService.clearAll（data/ExportService.kt:447-448）将密钥清理异常映射为 STORE_IO。
        failure?.let { throw IOException("密钥存储清理未完成", it) }
    }

    fun has(name: String): Boolean = readAll().containsKey(name)

    // ---------------------------------------------------------------- 内部

    private fun readAll(): Map<String, String> {
        // 依据：https://developer.android.com/reference/java/nio/file/Files#readAllLines(java.nio.file.Path,java.nio.charset.Charset)
        // 仅明确的 NoSuchFileException 视为空文件；其他读失败向调用方抛出。
        val lines = try {
            Files.readAllLines(file.toPath(), Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            return emptyMap()
        }
        return buildMap {
            lines.forEach { line ->
                val idx = line.indexOf('\t')
                if (idx > 0) put(line.substring(0, idx), line.substring(idx + 1))
            }
        }
    }

    private fun writeAll(map: Map<String, String>) {
        val text = map.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        file.writeText(text)
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = iv + body
        return Base64.getEncoder().encodeToString(packed)
    }

    private fun decrypt(encoded: String): String {
        val packed = Base64.getDecoder().decode(encoded)
        val iv = packed.copyOfRange(0, IV_SIZE)
        val body = packed.copyOfRange(IV_SIZE, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pulsez_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128

        private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }
}

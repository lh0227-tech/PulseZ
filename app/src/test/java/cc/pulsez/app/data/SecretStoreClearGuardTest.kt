package cc.pulsez.app.data

import cc.pulsez.app.util.SecretStore
import java.io.File
import java.io.IOException
import java.security.NoSuchAlgorithmException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecretStoreClearGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `密文删除失败时 clear 抛出并仍检查主密钥`() {
        val file = object : File("undeletable-secrets.bin") {
            override fun exists(): Boolean = true
            override fun delete(): Boolean = false
        }
        var deleteKeyAttempted = false
        var aliasChecked = false
        val store = SecretStore(
            file = file,
            deleteKeyEntry = { deleteKeyAttempted = true },
            keyAliasPresent = {
                aliasChecked = true
                false
            },
        )

        assertThrows(IOException::class.java) { store.clear() }
        assertTrue("文件删除失败后仍应尝试删除 Keystore alias", deleteKeyAttempted)
        assertTrue("文件删除失败后仍应核验 Keystore alias", aliasChecked)
    }

    @Test
    fun `clear 删除密文与主密钥且重复调用成功`() {
        val file = File(tempFolder.root, "secrets.bin").apply { writeText("key\tvalue") }
        var aliasPresent = true
        val store = SecretStore(
            file = file,
            deleteKeyEntry = { aliasPresent = false },
            keyAliasPresent = { aliasPresent },
        )

        store.clear()
        assertFalse("密文文件必须删除", file.exists())
        assertFalse("Keystore alias 必须删除", aliasPresent)

        store.clear()
        assertFalse("重复清空仍保持密文不存在", file.exists())
        assertFalse("重复清空仍保持 alias 不存在", aliasPresent)
    }

    @Test
    fun `Keystore alias 残留时 clear 抛出`() {
        val file = File(tempFolder.root, "secrets-remains.bin").apply { writeText("key\tvalue") }
        val store = SecretStore(
            file = file,
            deleteKeyEntry = {},
            keyAliasPresent = { true },
        )

        assertThrows(IOException::class.java) { store.clear() }
        assertFalse("alias 残留也必须先删除密文文件", file.exists())
    }

    @Test
    fun `keyAliasPresent 抛 NoSuchAlgorithmException 时 clear 抛 IOException`() {
        val store = SecretStore(
            file = File(tempFolder.root, "secrets-provider-error.bin"),
            deleteKeyEntry = {},
            keyAliasPresent = { throw NoSuchAlgorithmException("provider unavailable") },
        )

        assertThrows(IOException::class.java) { store.clear() }
    }

    @Test
    fun `密文文件读取失败时 has 向调用方抛出`() {
        val directoryAtFilePath = tempFolder.newFolder("secrets.bin")
        val store = SecretStore(
            file = directoryAtFilePath,
            deleteKeyEntry = {},
            keyAliasPresent = { false },
        )

        assertThrows(IOException::class.java) { store.has("chat-key") }
    }
}

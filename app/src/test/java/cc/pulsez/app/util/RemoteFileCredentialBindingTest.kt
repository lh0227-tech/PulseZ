package cc.pulsez.app.util

import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFileCredentialBindingTest {

    @Test
    fun `identifier is stable for the normalized endpoint and trimmed credential`() {
        val provider = FakeKeyProvider()
        val binding = RemoteFileCredentialBinding(provider)

        val first = binding.identifier("https://api.deepseek.com/v1/", " test-key ", hasKnownRecords = false)
        val same = binding.identifier("https://api.deepseek.com/v1", "test-key", hasKnownRecords = false)

        assertEquals(first, same)
        assertTrue(first!!.startsWith("hmac-v1:"))
        assertTrue("binding alias must not share SecretStore's API-key alias", REMOTE_FILE_BINDING_KEY_ALIAS != "pulsez_master_key")
        assertEquals(1, provider.createdKeys)
    }

    @Test
    fun `endpoint and credential changes produce different local bindings`() {
        val binding = RemoteFileCredentialBinding(FakeKeyProvider())
        val baseline = binding.identifier("https://api.deepseek.com", "key-one", hasKnownRecords = false)

        assertNotEquals(
            baseline,
            binding.identifier("https://other.example", "key-one", hasKnownRecords = false),
        )
        assertNotEquals(
            baseline,
            binding.identifier("https://api.deepseek.com", "key-two", hasKnownRecords = false),
        )
    }

    @Test
    fun `missing alias with known records is unknown and does not create replacement key`() {
        val provider = FakeKeyProvider()
        val binding = RemoteFileCredentialBinding(provider)
        val oldIdentifier = binding.identifier("https://api.deepseek.com", "key", hasKnownRecords = false)
        provider.loseAlias()

        val afterLoss = binding.identifier("https://api.deepseek.com", "key", hasKnownRecords = true)

        assertNull(afterLoss)
        assertNotEquals(oldIdentifier, afterLoss)
        assertEquals(1, provider.createdKeys)
    }

    @Test
    fun `first use with existing bound records fails closed`() {
        val provider = FakeKeyProvider()
        val binding = RemoteFileCredentialBinding(provider)

        assertNull(binding.identifier("https://api.deepseek.com", "key", hasKnownRecords = true))
        assertEquals(0, provider.createdKeys)
    }

    private class FakeKeyProvider : HmacKeyProvider {
        private var key: SecretKey? = null
        var createdKeys: Int = 0
            private set

        override fun getOrCreate(hasKnownRecords: Boolean): SecretKey? {
            key?.let { return it }
            if (hasKnownRecords) return null
            createdKeys += 1
            return SecretKeySpec("test-only-local-hmac-key".toByteArray(), "HmacSHA256").also { key = it }
        }

        fun loseAlias() {
            key = null
        }
    }
}

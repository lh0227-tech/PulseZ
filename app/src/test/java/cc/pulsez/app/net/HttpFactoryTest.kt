package cc.pulsez.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpFactoryTest {

    @Test
    fun `factory follows documented timeout policy`() {
        val client = HttpFactory.create()

        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(0, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertTrue(client.retryOnConnectionFailure)
        // 不装打印正文的拦截器（隐私）：应用层拦截器与网络层拦截器都应为空。
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
    }
}

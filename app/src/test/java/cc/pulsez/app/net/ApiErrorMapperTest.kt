package cc.pulsez.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class ApiErrorMapperTest {

    @Test
    fun `401 and 403 are auth`() {
        assertEquals(ApiError.Kind.AUTH, ApiErrorMapper.fromHttp(401, "{\"error\":\"bad key\"}").kind)
        assertEquals(ApiError.Kind.AUTH, ApiErrorMapper.fromHttp(403, null).kind)
    }

    @Test
    fun `402 and 429 are quota`() {
        assertEquals(ApiError.Kind.QUOTA, ApiErrorMapper.fromHttp(402, "insufficient balance").kind)
        assertEquals(ApiError.Kind.QUOTA, ApiErrorMapper.fromHttp(429, "rate limited").kind)
    }

    @Test
    fun `404 with model in body is model not found`() {
        val error = ApiErrorMapper.fromHttp(404, "{\"error\":{\"message\":\"model deepseek-x not found\"}}")
        assertEquals(ApiError.Kind.MODEL_NOT_FOUND, error.kind)
        assertEquals(404, error.httpCode)
    }

    @Test
    fun `404 without model is bad response`() {
        assertEquals(ApiError.Kind.BAD_RESPONSE, ApiErrorMapper.fromHttp(404, "not found").kind)
    }

    @Test
    fun `400 context length is context overflow`() {
        val error = ApiErrorMapper.fromHttp(
            400,
            "{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\"}}",
        )
        assertEquals(ApiError.Kind.CONTEXT_OVERFLOW, error.kind)
    }

    @Test
    fun `400 content filter is filtered`() {
        val error = ApiErrorMapper.fromHttp(400, "{\"error\":{\"message\":\"content filter triggered\"}}")
        assertEquals(ApiError.Kind.FILTERED, error.kind)
    }

    @Test
    fun `400 generic is bad response`() {
        assertEquals(ApiError.Kind.BAD_RESPONSE, ApiErrorMapper.fromHttp(400, "{\"error\":\"bad request\"}").kind)
    }

    @Test
    fun `5xx is server`() {
        assertEquals(ApiError.Kind.SERVER, ApiErrorMapper.fromHttp(500, "boom").kind)
        assertEquals(ApiError.Kind.SERVER, ApiErrorMapper.fromHttp(503, null).kind)
    }

    @Test
    fun `raw body is truncated to 200 chars`() {
        val error = ApiErrorMapper.fromHttp(500, "x".repeat(500))
        assertEquals(200, error.raw?.length)
    }

    @Test
    fun `io and timeout exceptions are network`() {
        assertEquals(ApiError.Kind.NETWORK, ApiErrorMapper.fromThrowable(IOException("connection reset")).kind)
        assertEquals(ApiError.Kind.NETWORK, ApiErrorMapper.fromThrowable(SocketTimeoutException("timeout")).kind)
    }

    @Test
    fun `serialization exception is bad response`() {
        val error = ApiErrorMapper.fromThrowable(kotlinx.serialization.SerializationException("unexpected token"))
        assertEquals(ApiError.Kind.BAD_RESPONSE, error.kind)
    }

    @Test
    fun `api exception unwraps inner error`() {
        val inner = ApiError.Auth(401, "nope")
        val mapped = ApiErrorMapper.fromThrowable(ApiException(inner))
        assertTrue(mapped === inner)
    }

    @Test
    fun `blank body yields null raw`() {
        assertNull(ApiErrorMapper.fromHttp(500, "   ").raw)
    }
}

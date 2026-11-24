package cc.pulsez.app.ui.config

/**
 * 连接测试的抽象。真实实现由 net 层提供（ModelProbe），
 * 这里只定义 UI 需要的形状，避免 UI 直接依赖网络实现。
 */
data class ConnectionTestResult(
    val chatOk: Boolean,
    val embeddingsOk: Boolean,
    val message: String,
    /**
     * 这次探测拿到的"当前模型能不能看图"（v0.5 `）。
     *
     * `null` = 服务端没返回 `input_modalities` 或模型不在列表里（门禁回退模型 id 规则）；
     * 真正持久化的能力缓存在 `SettingsStore.setVisionProbe(...)`（由 `ProbeTester` 写），
     * 这里只让 UI/测试拿到同一手结论，避免"探测了却用不上"。
     */
    val visionSupported: Boolean? = null,
)

interface ConnectionTester {
    suspend fun testChat(baseUrl: String, apiKey: String, model: String): ConnectionTestResult

    suspend fun testEmbeddings(baseUrl: String, apiKey: String, model: String): ConnectionTestResult
}

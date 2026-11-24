package cc.pulsez.app.wire

import cc.pulsez.app.data.settings.SettingsStore
import cc.pulsez.app.net.ModelProbe
import cc.pulsez.app.net.VisionCapability
import cc.pulsez.app.ui.config.ChatConfigCopy
import cc.pulsez.app.ui.config.ConnectionTestResult
import cc.pulsez.app.ui.config.ConnectionTester
import okhttp3.OkHttpClient

/**
 * 把 net 层的能力探测适配成 UI 需要的形状（的"测一下"）。
 *
 * 文案全部来自 不在这里发明新句子。
 *
 * v0.5.0 识图：
 * "测一下"成功拿到 `/models` 的 `input_modalities` 后，顺手把「当前模型能不能看图」
 * 写进 `SettingsStore` 缓存，图片入口据此显示/置灰。探测失败或服务端不给该字段时
 * **不写缓存**，门禁回退模型 id 规则（绝不回退 host）。
 */
class ProbeTester(
    private val http: OkHttpClient,
    /** 能力缓存落点；测试/旧调用点可传 null（只测连接、不写设置）。 */
    private val settings: SettingsStore? = null,
) : ConnectionTester {

    private val probe = ModelProbe(http)

    override suspend fun testChat(baseUrl: String, apiKey: String, model: String): ConnectionTestResult {
        // 聊天测试只探聊天端点：不再顺带探找旧事端点，省一次请求与最多 20s 超时等待
        // 。
        val result = probe.probe(baseUrl, apiKey, model = model, probeEmbeddings = false)
        val error = result.error
        // 能力位：只认 /models 里明确给出的结论；查不到就是 null（回退模型 id 规则）。
        // 用"探测时传入的 model"做键：用户配置里写的就是它，缓存的模型名必须与之一致。
        val vision = VisionCapability.supportsVision(model, result.modelModalities)
        if (result.chatOk && vision != null) {
            // 写缓存失败不影响连接测试结论（best-effort；失败下次测再写）。
            runCatching { settings?.setVisionProbe(model, vision) }
        }
        return when {
            // 成功文案用聊天页常量：找旧事的能力结论属于另一个页面，
            // 这里不再有"这家服务找旧事没那么准…"那条永远走不到的分支。
            result.chatOk -> ConnectionTestResult(
                chatOk = true,
                // 本次没有探测找旧事端点，不能对外声称"这家没有找旧事能力"。
                embeddingsOk = false,
                message = ChatConfigCopy.TEST_SUCCESS,
                visionSupported = vision,
            )
            error != null -> ConnectionTestResult(
                chatOk = false,
                embeddingsOk = false,
                message = ErrorCopy.of(error),
            )
            else -> ConnectionTestResult(
                chatOk = false,
                embeddingsOk = false,
                message = "没测成功。检查一下网络，或者换一个地址试试。",
            )
        }
    }

    override suspend fun testEmbeddings(baseUrl: String, apiKey: String, model: String): ConnectionTestResult {
        // 找旧事测试只探 embeddings 端点：不发 chat/completions，也不为挑模型发 /models
        // 。
        val result = probe.probe(
            baseUrl,
            apiKey,
            model = null,
            embeddingModel = model,
            probeChat = false,
        )
        return ConnectionTestResult(
            chatOk = false,
            embeddingsOk = result.embeddingsOk,
            message = if (result.embeddingsOk) {
                "找旧事这项能用了。"
            } else {
                "这个地址不帮着找旧事。不配也行，聊天不受影响。"
            },
        )
    }
}

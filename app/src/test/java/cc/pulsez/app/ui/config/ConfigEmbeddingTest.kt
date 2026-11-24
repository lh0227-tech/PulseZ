package cc.pulsez.app.ui.config

import cc.pulsez.app.ui.settings.CredentialRowCopy
import cc.pulsez.app.ui.settings.CredentialRowStatus
import cc.pulsez.app.ui.settings.FindOldThingsCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证聊天服务和找旧事服务使用独立设置页，找旧事示例的地址与模型匹配，界面文案符合用词规则。
 *
 * `ConfigViewModel` 依赖 `AppContainer` 和 `viewModelScope`，纯 JVM 测试环境无法直接构造；
 * 示例映射由纯函数覆盖，页面接线由源码扫描覆盖。
 */
class ConfigEmbeddingTest {

    companion object {
        /** 禁用词（"模型""密钥""地址"是配置页允许的例外，不在此列）。 */
        private val TECH_BANNED: List<String> = listOf(
            "token",
            "上下文",
            "窗口",
            "向量",
            "embedding",
            "Embedding",
            "记忆检索",
            "记忆系统",
            "关键词",
            "缓存",
        )

        /** 角色代词只允许 TA，不用"她/他"。 */
        private val PRONOUN_BANNED: List<String> = listOf("她", "他")

        /** 聊天示例里的聊天模型名：一个都不许出现在找旧事示例里。 */
        private val CHAT_MODEL_MARKERS: List<String> = listOf(
            "deepseek-flash",
            "deepseek-ai/",
            "qwen-plus",
            "glm-4-plus",
            "qwen2.5",
            "kimi",
            "moonshot",
        )

        /** 没有 embeddings 能力的服务商：示例里连名字都不该出现。 */
        private val NO_CAPABILITY_MARKERS: List<String> = listOf("deepseek", "kimi", "moonshot")

        /**
 * 推荐表中的地址和模型逐字匹配，**智谱 `embedding-3` 排第一**。
 * 单测锁死，防止有人凭记忆改模型名或把顺序改回去。
 *
 * 不入表的：`text-embedding-v4` / `text-embedding-v3`（官方单请求最多 10 行 < App 的 16 条批，
 *）、`embedding-2` / `nomic-embed-text`（单条上限太小）。
 *
 * 送检前：**本地 Ollama 整条移除**（Android 默认禁明文 http，手机连电脑的局域网地址
 * 做不通），只剩 4 个可用示例。
 */
        private val EXPECTED_EXAMPLES: List<Pair<String, String>> = listOf(
            // 官方接口 POST https://open.bigmodel.cn/api/paas/v4/embeddings，model 枚举 embedding-3。
            "https://open.bigmodel.cn/api/paas/v4" to "embedding-3",
            "https://api.siliconflow.cn/v1" to "BAAI/bge-m3",
            "https://dashscope.aliyuncs.com/compatible-mode/v1" to "qwen3.7-text-embedding",
            "https://api.openai.com/v1" to "text-embedding-3-small",
        )

        /**
 * chip 名逐字（服务商名，不带任何评价/未实测的连通性结论）。
 * （不标免费/推荐）、 （OpenAI 国内可用性未测）、。
 */
        private val EXPECTED_NAMES: List<String> = listOf(
            "智谱",
            "硅基流动",
            "阿里百炼",
            "OpenAI",
        )

        /** chip 名里不允许出现的宣称（免费会变、连通性未实测）。 */
        private val CLAIM_MARKERS: List<String> = listOf(
            "免费",
            "收费",
            "推荐",
            "最省",
            "最强",
            "稳定",
            "可能连不上",
            "连不上",
        )

        /** 本产出的全部用户可见文案（名称 → 文案），供批量禁用词断言。 */
        private fun allCopy(): List<Pair<String, String>> = listOf(
            // 设置页「连接」组两行（加三态：每个状态的文案都参与禁用词扫描）。
            "设置行标题" to FindOldThingsCopy.TITLE,
            "设置行未配置值" to FindOldThingsCopy.VALUE_NOT_CONFIGURED,
            "设置行只存地址值" to CredentialRowCopy.VALUE_ENDPOINT_ONLY,
            "设置行已配置兜底" to CredentialRowCopy.VALUE_CONFIGURED_FALLBACK,
            "设置行说明" to FindOldThingsCopy.DESCRIPTION,
            "设置行只存地址说明" to FindOldThingsCopy.DESCRIPTION_ADDRESS_ONLY,
            "聊天行未配置值" to CredentialRowCopy.CHAT_VALUE_NOT_CONFIGURED,
            "测试成功" to EmbedTestCopy.SUCCESS,
            "测试失败" to EmbedTestCopy.FAILURE,
        ) + EmbedConfigCopy.ALL.mapIndexed { index, text ->
            "找旧事页文案 ${index + 1}" to text
        } + ChatConfigCopy.ALL.mapIndexed { index, text ->
            "聊天页文案 ${index + 1}" to text
        }
    }

    // ------------------------------------------------------- 示例表（智谱第一； 逐字）

    @Test
    fun zhipuEmbeddingThreeIsTheFirstExample() {
        // 内置示例第一项必须是智谱，模型 embedding-3。
        val first = EmbedExamples.ALL.first()
        assertEquals("第一项必须是智谱", "智谱", first.name)
        assertEquals(
            "智谱地址逐字取自官方接口（POST /api/paas/v4/embeddings；App 再拼 /embeddings）",
            "https://open.bigmodel.cn/api/paas/v4",
            first.baseUrl,
        )
        assertEquals("智谱模型名必须是官方枚举 embedding-3", "embedding-3", first.model)
    }

    @Test
    fun embedExamplesAreTheFourVerifiedOnesVerbatim() {
        // 送检前：本地 Ollama 整条移除，只剩 4 个。
        assertEquals("找旧事示例数量", 4, EmbedExamples.ALL.size)
        assertEquals(
            "地址和模型必须完全匹配示例，包括模型名大小写和斜杠",
            EXPECTED_EXAMPLES,
            EmbedExamples.ALL.map { it.baseUrl to it.model },
        )
    }

    @Test
    fun everyEmbedExampleIsComplete() {
        EmbedExamples.ALL.forEach { example ->
            assertTrue("chip 名不能为空", example.name.isNotBlank())
            assertTrue("地址不能为空：${example.name}", example.baseUrl.isNotBlank())
            assertTrue(
                "地址要带协议头并写全到 OpenAI 兼容 base：${example.name}",
                example.baseUrl.startsWith("http://") || example.baseUrl.startsWith("https://"),
            )
            assertTrue("模型名不能为空：${example.name}", example.model.isNotBlank())
        }
    }

    @Test
    fun embedExamplesNeverReuseChatModelsOrNoCapabilityProviders() {
        EmbedExamples.ALL.forEach { example ->
            val haystack = "${example.name}|${example.baseUrl}|${example.model}".lowercase()
            CHAT_MODEL_MARKERS.forEach { marker ->
                assertFalse(
                    "找旧事示例「${example.name}」混进了聊天模型「$marker」：$haystack",
                    haystack.contains(marker.lowercase()),
                )
            }
            NO_CAPABILITY_MARKERS.forEach { marker ->
                assertFalse(
                    "找旧事示例「${example.name}」出现没有这个能力的服务商「$marker」：$haystack",
                    haystack.contains(marker),
                )
            }
        }
    }

    @Test
    fun embedExampleNamesArePlainServiceNamesWithoutClaims() {
        assertEquals("chip 名逐字（只写服务商名，不带评价）", EXPECTED_NAMES, EmbedExamples.ALL.map { it.name })
        EmbedExamples.ALL.forEach { example ->
            CLAIM_MARKERS.forEach { marker ->
                assertFalse(
                    "chip 名不能带未核实的宣称「$marker」：${example.name}",
                    example.name.contains(marker),
                )
            }
        }
    }

    @Test
    fun neitherPageOffersALocalOllamaExample() {
        // 送检前修正：Android 默认禁明文 http，手机填电脑的局域网地址
        // 连不上；所以两个设置页都不再提供 Ollama 示例，也不预填 127.0.0.1 / localhost / 11434。
        assertEquals("聊天页示例为 4 个", 4, chatExamples.size)
        assertEquals("找旧事页示例为 4 个", 4, EmbedExamples.ALL.size)

        val allExamples = chatExamples.map { Triple(it.name, it.baseUrl, it.model) } +
            EmbedExamples.ALL.map { Triple(it.name, it.baseUrl, it.model) }
        assertEquals("两页示例合计 8 个都检查到", 8, allExamples.size)
        allExamples.forEach { (name, baseUrl, model) ->
            val haystack = "$name|$baseUrl|$model"
            assertFalse("示例里不能再出现 Ollama（$name）：$haystack", haystack.contains("Ollama"))
            assertFalse("示例地址不能是回环（手机上指向手机自己）：$baseUrl", baseUrl.contains("127.0.0.1"))
            assertFalse("示例地址不能是 localhost：$baseUrl", baseUrl.contains("localhost"))
            assertFalse("示例地址不能是 Ollama 默认端口：$haystack", haystack.contains("11434"))
        }
    }

    @Test
    fun localOllamaAbsenceIsExplainedInPlainWords() {
        // 说明句逐字（送检前修正）：一句如实说明替代原来的两段 Ollama 操作指引。
        val note = OllamaCopy.NOT_BUILT_IN
        assertEquals(
            "手机暂不内置本地 Ollama 示例：系统默认不允许明文 http，填电脑的局域网地址会连不上。" +
                "如果你在电脑上给 Ollama 配了 https 反向代理，可以手动填地址。",
            note,
        )
        assertTrue("要点名是 Ollama：$note", note.contains("Ollama"))
        assertTrue("要说清手机暂不内置这个示例：$note", note.contains("暂不内置"))
        assertTrue("要说清原因是明文 http 被拦：$note", note.contains("明文 http"))
        assertTrue("要说明局域网地址连不上：$note", note.contains("局域网地址会连不上"))
        assertTrue("要给出 https 反向代理的出路：$note", note.contains("https 反向代理"))
        assertTrue("要说明可以手动填地址：$note", note.contains("手动填地址"))
        // 不再教「电脑放开监听 + 手机填局域网 IP」那套（照做也连不上）。
        listOf("OLLAMA_HOST", "11434", "127.0.0.1", "同一个 Wi-Fi").forEach { phrase ->
            assertFalse("说明里不能再出现旧操作指引「$phrase」：$note", note.contains(phrase))
        }
        // 聊天页会渲染这句；找旧事页只留 4 个可用示例，不再出现任何 Ollama 文案。
        assertTrue("聊天页文案入口要带这句如实说明：${ChatConfigCopy.ALL}", ChatConfigCopy.ALL.contains(note))
        EmbedConfigCopy.ALL.forEach { text ->
            assertFalse("找旧事页不再渲染 Ollama 文案：$text", text.contains("Ollama"))
        }
    }

    // ------------------------------------------------------- 两页字段不串（纯映射层）

    @Test
    fun applyEmbedExampleOnlyTouchesEmbedFields() {
        val before = ConfigUiState(
            baseUrl = "https://api.deepseek.com",
            apiKey = "sk-chat-should-not-move",
            model = "deepseek-flash",
            embedBaseUrl = "",
            embedApiKey = "sk-embed-stale",
            embedModel = "",
            result = "连上了。可以开始了。",
            resultOk = true,
        )
        val after = applyEmbedExampleTo(
            before,
            baseUrl = "https://api.siliconflow.cn/v1",
            model = "BAAI/bge-m3",
        )

        // 只改这两格
        assertEquals("https://api.siliconflow.cn/v1", after.embedBaseUrl)
        assertEquals("BAAI/bge-m3", after.embedModel)

        // 聊天三格 / 密钥 / 测试状态一律不动（找旧事和聊天是两套服务）
        assertEquals(before.baseUrl, after.baseUrl)
        assertEquals(before.apiKey, after.apiKey)
        assertEquals(before.model, after.model)
        assertEquals(before.embedApiKey, after.embedApiKey)
        assertEquals(before.result, after.result)
        assertEquals(before.resultOk, after.resultOk)
        assertEquals(before.testing, after.testing)
        assertEquals(before.embedTesting, after.embedTesting)
        assertEquals(before.embedResult, after.embedResult)
        assertEquals(before.embedResultOk, after.embedResultOk)
        assertEquals(before.saved, after.saved)
    }

    @Test
    fun applyingEachEmbedExampleKeepsChatFieldsUntouched() {
        EmbedExamples.ALL.forEach { example ->
            val before = ConfigUiState(
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                apiKey = "sk-chat",
                model = "qwen-plus",
            )
            val after = applyEmbedExampleTo(before, example.baseUrl, example.model)
            assertEquals("填入「${example.name}」后聊天地址被改了", before.baseUrl, after.baseUrl)
            assertEquals("填入「${example.name}」后聊天密钥被改了", before.apiKey, after.apiKey)
            assertEquals("填入「${example.name}」后聊天模型被改了", before.model, after.model)
            assertEquals(example.baseUrl, after.embedBaseUrl)
            assertEquals(example.model, after.embedModel)
        }
    }

    @Test
    fun applyChatExampleOnlyTouchesChatFields() {
        // 聊天页示例的反向回归：只改聊天地址 / 聊天模型，绝不碰找旧事三格。
        val before = ConfigUiState(
            baseUrl = "",
            model = "",
            embedBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
            embedApiKey = "sk-embed-should-not-move",
            embedModel = "embedding-3",
            embedResult = "找旧事连上了。",
            embedResultOk = true,
        )
        val after = applyChatExampleTo(
            before,
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
        )

        assertEquals("https://api.deepseek.com", after.baseUrl)
        assertEquals("deepseek-flash", after.model)
        assertEquals(before.embedBaseUrl, after.embedBaseUrl)
        assertEquals(before.embedApiKey, after.embedApiKey)
        assertEquals(before.embedModel, after.embedModel)
        assertEquals(before.embedResult, after.embedResult)
        assertEquals(before.embedResultOk, after.embedResultOk)
    }

    // ------------------------------------------------------- 「测一下」可点条件（两套互不影响）

    @Test
    fun canTestEmbedRequiresAllThreeFieldsAndNotTesting() {
        val ready = ConfigUiState(
            embedBaseUrl = "https://api.siliconflow.cn/v1",
            embedApiKey = "sk-embed",
            embedModel = "BAAI/bge-m3",
        )
        assertTrue("三格都填了就该能测", ready.canTestEmbed)

        assertFalse(
            "没地址不能测",
            ready.copy(embedBaseUrl = "").canTestEmbed,
        )
        assertFalse(
            "没密钥不能测（服务端要鉴权）",
            ready.copy(embedApiKey = "").canTestEmbed,
        )
        assertFalse(
            "没模型名不能测",
            ready.copy(embedModel = "").canTestEmbed,
        )
        assertFalse(
            "正在测时不能重复点",
            ready.copy(embedTesting = true).canTestEmbed,
        )
    }

    @Test
    fun canTestStillOnlyDependsOnChatFields() {
        // 回归：找旧事状态不能改变聊天「测一下」的语义（ConfigUiState.canTest 原样）。
        val chatReady = ConfigUiState(
            baseUrl = "https://api.deepseek.com",
            apiKey = "sk-chat",
            model = "deepseek-flash",
        )
        assertTrue(chatReady.canTest)
        assertTrue(
            "找旧事字段不该影响聊天测试",
            chatReady.copy(embedBaseUrl = "", embedApiKey = "", embedModel = "").canTest,
        )
        assertFalse("聊天在测时不能重复点", chatReady.copy(testing = true).canTest)
        assertFalse("聊天没密钥不能测", chatReady.copy(apiKey = "").canTest)
    }

    // ------------------------------------------------------- 文案：三问 + 禁用词 + 两页分离

    @Test
    fun settingsRowCopyIsVerbatimAndAnswersThreeQuestions() {
        // 逐字定稿（增加「地址已存、还没填密钥」共用文案与聊天行未配置句子）。
        // 任何改动必须先改 / docs 再改这里。
        assertEquals("让 TA 更会找旧事", FindOldThingsCopy.TITLE)
        assertEquals("没配（也能用）", FindOldThingsCopy.VALUE_NOT_CONFIGURED)
        assertEquals(
            "可选：另配一个服务，TA 会按意思找到以前的聊天；不配也能用，只是找得没那么准。",
            FindOldThingsCopy.DESCRIPTION,
        )
        // （方案丙 + 追加聊天行）：允许先存地址+模型、密钥后补；两行状态必须如实
        // 说缺的是密钥，不能再说「没配 / 还没配置」（保存成功退出后仍显示没配置正是复现的自相矛盾）。
        assertEquals("地址已存，还没填密钥", CredentialRowCopy.VALUE_ENDPOINT_ONLY)
        assertEquals("已配置", CredentialRowCopy.VALUE_CONFIGURED_FALLBACK)
        assertEquals("还没配置", CredentialRowCopy.CHAT_VALUE_NOT_CONFIGURED)
        assertEquals(
            "还没填密钥：填上之前，TA 还不能按意思找旧事，仍能按原话找。",
            FindOldThingsCopy.DESCRIPTION_ADDRESS_ONLY,
        )
        assertFalse(
            "地址已存时不能再说「没配」：${CredentialRowCopy.VALUE_ENDPOINT_ONLY}",
            CredentialRowCopy.VALUE_ENDPOINT_ONLY.contains("没配"),
        )
        assertFalse(
            "地址已存时不能再说「还没配置」：${CredentialRowCopy.VALUE_ENDPOINT_ONLY}",
            CredentialRowCopy.VALUE_ENDPOINT_ONLY.contains("还没配置"),
        )
        // 只存地址态的说明要回答「现在能做什么 / 不能做什么」。
        val addressOnlyDescription = FindOldThingsCopy.DESCRIPTION_ADDRESS_ONLY
        assertTrue("要说明缺的是密钥：$addressOnlyDescription", addressOnlyDescription.contains("还没填密钥"))
        assertTrue(
            "要说明填上之前不能按意思找旧事：$addressOnlyDescription",
            addressOnlyDescription.contains("还不能按意思找旧事"),
        )
        assertTrue("要说明仍能按原话找：$addressOnlyDescription", addressOnlyDescription.contains("仍能按原话找"))
        // 三问：这是什么（另配一个服务、按意思找到以前的聊天）/ 要不要用（可选）/ 不用会怎样（不配也能用、找得没那么准）。
        val description = FindOldThingsCopy.DESCRIPTION
        assertTrue("要说明这是什么：$description", description.contains("另配一个服务"))
        assertTrue("要说明按意思找旧聊天：$description", description.contains("按意思找到以前的聊天"))
        assertTrue("要说明是用户可选的：$description", description.contains("可选"))
        assertTrue("要说明不配也能用：$description", description.contains("不配也能用"))
        assertTrue("要说明不配的代价：$description", description.contains("找得没那么准"))
        // 不是"产品没做"的口吻（V1：正式能力，配不配由用户选）。
        listOf(description, addressOnlyDescription).forEach { text ->
            listOf("暂不支持", "没做", "仅支持", "实验").forEach { phrase ->
                assertFalse("找旧事是正式能力，不能写成「$phrase」：$text", text.contains(phrase))
            }
        }
        assertTrue("设置行标题要出现 TA", FindOldThingsCopy.TITLE.contains("TA"))
    }

    @Test
    fun credentialRowsAreThreeStateAndNeverContradictSavedEndpoint() {
        // （含 追加的聊天行同型问题）：三态判定/取值两行共用同一纯函数，
        // 逐态锁死；两行「地址已存、还没填密钥」必须是同一句（口径一致）。
        val embedBase = "https://open.bigmodel.cn/api/paas/v4"
        val chatBase = "https://api.deepseek.com"

        // ① 地址+模型已存、密钥可用 → 保持旧「已配置」值（显示模型名，空则兜底），两行一致。
        assertEquals(CredentialRowStatus.CONFIGURED, CredentialRowCopy.status(true, embedBase))
        assertEquals(CredentialRowStatus.CONFIGURED, CredentialRowCopy.status(true, chatBase))
        assertEquals("embedding-3", FindOldThingsCopy.value(CredentialRowStatus.CONFIGURED, "embedding-3"))
        assertEquals(
            "deepseek-flash",
            CredentialRowCopy.value(
                CredentialRowStatus.CONFIGURED,
                "deepseek-flash",
                CredentialRowCopy.CHAT_VALUE_NOT_CONFIGURED,
            ),
        )
        assertEquals(
            "模型名为空走既有兜底（两行同句）",
            CredentialRowCopy.VALUE_CONFIGURED_FALLBACK,
            CredentialRowCopy.value(
                CredentialRowStatus.CONFIGURED,
                "  ",
                CredentialRowCopy.CHAT_VALUE_NOT_CONFIGURED,
            ),
        )
        assertEquals(FindOldThingsCopy.DESCRIPTION, FindOldThingsCopy.description(CredentialRowStatus.CONFIGURED))

        // ② 核心回归：地址已存、密钥还没填（`NoKeyYet` 保存成功后的持久态）。
        // 两行都必须显示同一句「地址已存，还没填密钥」，不能再写「没配 / 还没配置」。
        val embedOnly = FindOldThingsCopy.value(CredentialRowStatus.ENDPOINT_ONLY, "embedding-3")
        val chatOnly = CredentialRowCopy.value(
            CredentialRowStatus.ENDPOINT_ONLY,
            "deepseek-flash",
            CredentialRowCopy.CHAT_VALUE_NOT_CONFIGURED,
        )
        assertEquals(CredentialRowStatus.ENDPOINT_ONLY, CredentialRowCopy.status(false, embedBase))
        assertEquals(CredentialRowStatus.ENDPOINT_ONLY, CredentialRowCopy.status(false, chatBase))
        assertEquals("只存地址态的值文案逐字（找旧事行）", "地址已存，还没填密钥", embedOnly)
        assertEquals("只存地址态的值文案逐字（聊天行）", "地址已存，还没填密钥", chatOnly)
        assertEquals("两行只存地址态必须是同一句（两页口径一致）", embedOnly, chatOnly)
        assertFalse("地址已存时找旧事行不能再出现「没配」", embedOnly.contains("没配"))
        assertFalse("地址已存时聊天行不能再出现「还没配置」", chatOnly.contains("还没配置"))
        assertFalse("地址已存时聊天行也不能再出现「没配」", chatOnly.contains("没配"))
        assertEquals(
            "只存地址态的说明换成如实解释（仅找旧事行有行下说明）",
            FindOldThingsCopy.DESCRIPTION_ADDRESS_ONLY,
            FindOldThingsCopy.description(CredentialRowStatus.ENDPOINT_ONLY),
        )

        // ③ 地址也没存 → 各自行保持旧句子（聊天「还没配置」/ 找旧事「没配（也能用）」）。
        assertEquals(CredentialRowStatus.NOT_CONFIGURED, CredentialRowCopy.status(false, ""))
        assertEquals(
            FindOldThingsCopy.VALUE_NOT_CONFIGURED,
            FindOldThingsCopy.value(CredentialRowStatus.NOT_CONFIGURED, "embedding-3"),
        )
        assertEquals(
            "聊天行未配置仍写「还没配置」",
            CredentialRowCopy.CHAT_VALUE_NOT_CONFIGURED,
            CredentialRowCopy.value(
                CredentialRowStatus.NOT_CONFIGURED,
                "deepseek-flash",
                CredentialRowCopy.CHAT_VALUE_NOT_CONFIGURED,
            ),
        )
        assertEquals(FindOldThingsCopy.DESCRIPTION, FindOldThingsCopy.description(CredentialRowStatus.NOT_CONFIGURED))

        // 空白地址不算「地址已存」；只有密钥标记、没有地址时也没有可用服务，保持未配置（防御性）。
        assertEquals(CredentialRowStatus.NOT_CONFIGURED, CredentialRowCopy.status(false, "   "))
        assertEquals(
            "没有地址 = 没有可用服务，不谎称「地址已存」",
            CredentialRowStatus.NOT_CONFIGURED,
            CredentialRowCopy.status(true, " "),
        )
        assertEquals(CredentialRowStatus.CONFIGURED, CredentialRowCopy.status(true, "  $embedBase  "))

        // 四种输入组合只落在三种状态上，没有第四种隐藏态（两行同一函数）。
        val allStatuses = listOf(
            CredentialRowCopy.status(true, embedBase),
            CredentialRowCopy.status(false, embedBase),
            CredentialRowCopy.status(true, ""),
            CredentialRowCopy.status(false, ""),
        )
        assertEquals(
            "三态覆盖全部输入组合",
            setOf(
                CredentialRowStatus.CONFIGURED,
                CredentialRowStatus.ENDPOINT_ONLY,
                CredentialRowStatus.NOT_CONFIGURED,
            ),
            allStatuses.toSet(),
        )
    }

    @Test
    fun findOldThingsPageCopyAnswersThreeQuestions() {
        assertEquals("页面标题用说人话的名字", "找旧事", EmbedConfigCopy.TITLE)

        // 这是什么：另配一个服务，让 TA 按意思找到以前聊过的相关片段，不用把全部旧聊天都带上。
        val description = EmbedConfigCopy.DESCRIPTION
        assertTrue("要说明是另配的一个服务：$description", description.contains("另配的一个服务"))
        assertTrue("要说明按意思找：$description", description.contains("按意思"))
        assertTrue("要说明找的是以前聊过的内容：$description", description.contains("以前聊过的相关片段"))
        assertTrue(
            "要说明用户能懂的收益（不用把全部旧聊天都带上）：$description",
            description.contains("不用把全部旧聊天都带上"),
        )
        // 要不要用：可选；同一家可以复用，但不是必须。
        assertTrue("要说明是用户可选的：$description", description.contains("可选"))
        assertTrue("要说明可以和聊天同一家：$description", description.contains("同一家"))

        // 不用会怎样 + 费用归属（V3）。
        val optional = EmbedConfigCopy.OPTIONAL_NOTE
        assertTrue("要说明不配也能用：$optional", optional.contains("不配也能用"))
        assertTrue("要说明不配时按原话找：$optional", optional.contains("按原话"))
        assertTrue("要说明不配的代价：$optional", optional.contains("找得没那么准"))
        assertTrue(
            "要说明费用由这一页填的那家服务收（S8：别和聊天服务商混淆）：$optional",
            optional.contains("费用由你填的这家服务收取"),
        )
        listOf("暂不支持", "没做", "仅支持", "实验").forEach { phrase ->
            assertFalse("找旧事是正式能力，不能写成「$phrase」：$optional", optional.contains(phrase))
        }

        // 示例区必须说清"和聊天用的不是一套，别混填"（用户反馈的混淆点）。
        val title = EmbedConfigCopy.EXAMPLES_TITLE
        assertTrue("示例区标题要出现「不是一套」：$title", title.contains("不是一套"))
        assertTrue("示例区标题要出现「别混填」：$title", title.contains("别混填"))
        assertTrue("示例区标题要说清是找旧事的模型：$title", title.contains("模型"))
        // 旧文案里的"和上面那些聊天模型"指向聊天页的字段，拆分后不再成立。
        assertFalse("拆分后没有「上面」那套聊天字段了：$title", title.contains("上面"))

        // 明确点出没有这个能力的两家，免得用户白试。
        val hint = EmbedConfigCopy.NO_CAPABILITY_HINT
        assertTrue("要出现 DeepSeek：$hint", hint.contains("DeepSeek"))
        assertTrue("要出现 Kimi：$hint", hint.contains("Kimi"))
        assertTrue("要说明它们没有这个能力：$hint", hint.contains("没有这个能力"))
        assertTrue("要给出路：另配一家：$hint", hint.contains("另配一家"))

        // 示例只是快捷方式，不是推荐。
        val note = EmbedConfigCopy.EXAMPLES_NOTE
        assertTrue("要说明只是快捷填入：$note", note.contains("快捷填入"))
        assertTrue("要说明不是推荐：$note", note.contains("不是推荐"))
        assertTrue("要说明可以自己改：$note", note.contains("自己改"))

        // 隐私提醒：只把要比较的聊天片段发给用户自己填的这家服务。
        val privacy = EmbedConfigCopy.PRIVACY_REMINDER
        assertTrue("要说清发的是什么：$privacy", privacy.contains("要比较的聊天片段"))
        assertTrue("要说清发给谁：$privacy", privacy.contains("你填的这家服务"))

        // 密钥存储说明：与聊天页同口径。
        val keyNote = EmbedConfigCopy.KEY_NOTE
        assertTrue("要说明密钥只存在手机里：$keyNote", keyNote.contains("只存在你的手机里"))
        assertTrue("要说明不会发给别人：$keyNote", keyNote.contains("不会发给任何人"))
    }

    @Test
    fun testEmbedResultCopyIsPlainAndActionable() {
        assertEquals("找旧事连上了。", EmbedTestCopy.SUCCESS)
        assertEquals("没连上。检查一下地址、密钥和模型名。", EmbedTestCopy.FAILURE)
        assertTrue(
            "失败文案要给出三个可检查的点：${EmbedTestCopy.FAILURE}",
            EmbedTestCopy.FAILURE.contains("地址") &&
                EmbedTestCopy.FAILURE.contains("密钥") &&
                EmbedTestCopy.FAILURE.contains("模型名"),
        )
        assertEquals("测一下找旧事", EmbedConfigCopy.TEST_BUTTON)
        //：省略号用「……」，不用单个「…」。
        assertEquals("正在测……", EmbedConfigCopy.TESTING)
    }

    @Test
    fun chatPageCopyNeverMentionsFindOldThings() {
        // 分离要求：聊天设置页不得出现任何找旧事说明。
        ChatConfigCopy.ALL.forEach { text ->
            assertFalse("聊天页文案不能出现「找旧事」：$text", text.contains("找旧事"))
        }
        assertEquals("聊天页标题", "连接你的 AI", ChatConfigCopy.TITLE)
        //：省略号用「……」，不用单个「…」。
        assertEquals("正在测……", ChatConfigCopy.TESTING)
        // 送检前：聊天页不再教 Ollama 局域网操作（照做也连不上），只保留一句如实说明。
        ChatConfigCopy.ALL.forEach { text ->
            assertFalse("聊天页不能再出现旧 Ollama 操作指引：$text", text.contains("OLLAMA_HOST"))
            assertFalse("聊天页不能再出现 Ollama 默认端口：$text", text.contains("11434"))
            assertFalse("聊天页不能再预填回环地址：$text", text.contains("127.0.0.1"))
        }
        assertTrue(
            "聊天页要有「为什么不内置本地 Ollama 示例」的如实说明：${ChatConfigCopy.ALL}",
            ChatConfigCopy.ALL.contains(OllamaCopy.NOT_BUILT_IN),
        )
        // 「深度思考 / 智能搜索」说明仍属于聊天页（聊天页包含该内容）。
        assertTrue(
            "聊天页要说明思考/搜索只对 DeepSeek 有效：${ChatConfigCopy.DEEPSEEK_ONLY_HINT}",
            ChatConfigCopy.DEEPSEEK_ONLY_HINT.contains("深度思考") &&
                ChatConfigCopy.DEEPSEEK_ONLY_HINT.contains("智能搜索") &&
                ChatConfigCopy.DEEPSEEK_ONLY_HINT.contains("DeepSeek"),
        )
    }

    @Test
    fun findOldThingsPageCopyNeverMentionsChatModels() {
        // 分离要求：找旧事页不得出现聊天模型名。
        // 送检前：这一页也不再出现任何 Ollama 文案/端口/回环地址（示例已移除）。
        EmbedConfigCopy.ALL.forEach { text ->
            listOf("deepseek-flash", "deepseek-ai/", "qwen-plus", "glm-4-plus", "qwen2.5").forEach { model ->
                assertFalse("找旧事页文案不能出现聊天模型「$model」：$text", text.contains(model))
            }
            assertFalse("找旧事页文案不能再出现 Ollama：$text", text.contains("Ollama"))
            assertFalse("找旧事页文案不能再出现 Ollama 默认端口：$text", text.contains("11434"))
            assertFalse("找旧事页文案不能再出现回环地址：$text", text.contains("127.0.0.1"))
        }
    }

    @Test
    fun copyAvoidsBannedWordsAndWrongPronouns() {
        allCopy().forEach { (name, text) ->
            TECH_BANNED.forEach { word ->
                assertFalse("「$name」出现技术词「$word」：$text", text.contains(word))
            }
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("「$name」出现角色代词「$pronoun」（只许 TA）：$text", text.contains(pronoun))
            }
            assertFalse("「$name」出现客服腔「我们」：$text", text.contains("我们"))
        }
    }
}

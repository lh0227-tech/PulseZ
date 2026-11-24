package cc.pulsez.app.importer.deepseek

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekRoleClustererTest {

    private fun preview(id: String, damageCode: String? = null) = DeepSeekSessionPreview(
        entryName = "chat_sessions/$id.json",
        sourceSessionId = id,
        title = "标题-$id",
        sourceStartedAt = 1_700_000_000_000L,
        messageCount = if (damageCode == null) 6 else 0,
        damageCode = damageCode,
        firstUserSnippet = "你好，$id",
        firstAssistantSnippet = "我在，$id",
        lastAssistantSnippet = "晚安，$id",
    )

    @Test
    fun `buildRequest uses temperature 0_3 and disables thinking only on DeepSeek official endpoint`() {
        val dsReq = DeepSeekRoleClusterer.buildRequest(
            model = "deepseek-chat",
            baseUrl = "https://api.deepseek.com/v1",
            systemPrompt = "sys",
            userPrompt = "usr",
        )
        assertEquals(0.3, dsReq.temperature!!, 0.0001)
        assertTrue(dsReq.jsonMode)
        assertFalse(dsReq.stream)
        assertEquals(false, dsReq.thinking)

        val thirdPartyReq = DeepSeekRoleClusterer.buildRequest(
            model = "gpt-4o-mini",
            baseUrl = "https://example.com/v1",
            systemPrompt = "sys",
            userPrompt = "usr",
        )
        assertEquals(0.3, thirdPartyReq.temperature!!, 0.0001)
        assertNull(thirdPartyReq.thinking)
    }

    @Test
    fun `single batch parses roles sanitizes forbidden names and filters unknown ids`() = runBlocking {
        val clusterer = DeepSeekRoleClusterer { _, _ ->
            """
            ```json
            {
              "roles": [
                {
                  "name": "陆沉",
                  "userName": "小满",
                  "persona": "沉稳温和，说话克制但会在细节里护着你。TA 习惯在夜深时提醒你早点休息。",
                  "styleSamples": ["先喝口温水，慢慢说。", "我在听，不用急。"],
                  "sessionIds": ["s1", "s2", "hallucinated-id"]
                },
                {
                  "name": "新对话",
                  "userName": "",
                  "persona": "爱开玩笑的朋友，TA 总能把气氛带轻松。",
                  "styleSamples": ["哈哈，这事交给我。", "走，先去透透气。"],
                  "sessionIds": ["s3"]
                }
              ],
              "noiseSessionIds": ["s4", "s1", "unknown-noise"]
            }
            ```
            """.trimIndent()
        }

        val result = clusterer.cluster(
            listOf(preview("s1"), preview("s2"), preview("s3"), preview("s4"), preview("bad", damageCode = "BAD_ZIP")),
        )

        assertEquals(2, result.roles.size)
        val first = result.roles[0]
        assertEquals("陆沉", first.name)
        assertEquals("小满", first.userName)
        assertEquals(listOf("s1", "s2"), first.sessionIds)
        assertEquals(listOf("先喝口温水，慢慢说。", "我在听，不用急。"), first.styleSamples)

        val second = result.roles[1]
        assertFalse("禁止返回新对话/未命名", second.name.contains("新对话"))
        assertNull(second.userName)
        assertEquals(listOf("s3"), second.sessionIds)

        assertEquals(listOf("s4"), result.noiseSessionIds)
    }

    @Test
    fun `multi batch chunks at 35 and merges same-name roles across batches`() = runBlocking {
        val calls = mutableListOf<String>()
        val clusterer = DeepSeekRoleClusterer { _, user ->
            calls += user
            if (calls.size == 1) {
                """
                {
                  "roles": [
                    {
                      "name": "林屿",
                      "userName": null,
                      "persona": "短人设",
                      "styleSamples": ["句一", "句二"],
                      "sessionIds": ["s1", "s2"]
                    }
                  ],
                  "noiseSessionIds": ["s3"]
                }
                """.trimIndent()
            } else {
                """
                {
                  "roles": [
                    {
                      "name": "林屿",
                      "userName": "阿昼",
                      "persona": "更完整丰富的林屿人设：TA 说话简短干脆，但每次都会记挂你的日程。",
                      "styleSamples": ["句二", "句三", "句四"],
                      "sessionIds": ["s36", "s40"]
                    }
                  ],
                  "noiseSessionIds": ["s37"]
                }
                """.trimIndent()
            }
        }

        val previews = (1..40).map { preview("s$it") } + preview("damaged", damageCode = "MALFORMED_JSON")
        val result = clusterer.cluster(previews)

        assertEquals(2, calls.size)
        assertFalse("损坏会话不应进入批次请求", calls.any { it.contains("damaged") })
        assertTrue("第二批应附带已识别角色摘要", calls[1].contains("existingRoles"))
        assertTrue(calls[1].contains("林屿"))

        assertEquals(1, result.roles.size)
        val merged = result.roles.single()
        assertEquals("林屿", merged.name)
        assertEquals("阿昼", merged.userName)
        assertTrue(merged.persona.contains("更完整丰富"))
        assertEquals(listOf("s1", "s2", "s36", "s40"), merged.sessionIds)
        assertEquals(listOf("句一", "句二", "句三", "句四"), merged.styleSamples)
        assertEquals(listOf("s3", "s37"), result.noiseSessionIds)
    }

    @Test
    fun `cancellation interrupts multi batch clustering cleanly`() = runBlocking {
        var calls = 0
        val clusterer = DeepSeekRoleClusterer { _, _ ->
            calls++
            delay(50)
            """{"roles":[],"noiseSessionIds":[]}"""
        }
        val previews = (1..40).map { preview("s$it") }
        val job = async {
            clusterer.cluster(previews)
        }
        delay(20)
        job.cancel()
        val threwCancel = try {
            job.await()
            false
        } catch (_: CancellationException) {
            true
        }
        assertTrue(threwCancel)
        assertEquals(1, calls)
    }
}

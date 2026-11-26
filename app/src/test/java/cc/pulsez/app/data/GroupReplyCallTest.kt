package cc.pulsez.app.data

import cc.pulsez.app.data.group.GroupReplyCall
import cc.pulsez.app.mem.MemMessage
import cc.pulsez.app.net.ApiError
import cc.pulsez.app.net.ApiException
import cc.pulsez.app.net.ChatRequest
import cc.pulsez.app.net.LlmEvent
import cc.pulsez.app.net.Usage
import cc.pulsez.app.wire.Pricing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 群聊生成使用**可取消**的流式路径，且无论成功 / 取消 / 已经出字后失败，
 * 已经产生的用量都要记账。
 *
 * 用 `LlmClient.complete`：取消协程不会 cancel OkHttp Call（请求跑到底、照样计费），
 * 而且 `complete` 不返回 usage，请求跑完用量被丢弃（有费用、无记录）。
 */
class GroupReplyCallTest {

    private val prompt = listOf(
        MemMessage.system("【群聊规则】在场的人：阿甲、阿乙。你的名字是 阿甲，别人问起来就说 阿甲。"),
        MemMessage.user("今天加班到九点，好累"),
    )

    @Test
    fun oneGroupTurnRecordsServerUsageExactlyOnce() = runBlocking {
        var captured: ChatRequest? = null
        val recorded = mutableListOf<Usage>()
        val usage = Usage(promptTokens = 1_200, completionTokens = 36)
        val call = GroupReplyCall(
            model = "deepseek-flash",
            stream = { request ->
                captured = request
                flow {
                    emit(LlmEvent.Delta("那先别想工作了，"))
                    emit(LlmEvent.Delta("泡个脚早点睡吧。"))
                    emit(LlmEvent.Finished(usage = usage, truncated = false))
                }
            },
            recordUsage = { recorded += it },
        )

        val output = call.invoke(messages = prompt, maxTokens = 256)

        assertEquals("那先别想工作了，泡个脚早点睡吧。", output.text)
        assertEquals("一轮一次调用只记一次账", 1, recorded.size)
        assertEquals("有服务端 usage 就用真实值，不折算", usage, recorded.single())
        assertEquals(usage, output.usage)

        // 请求口径：流式 + 要 usage + maxTokens 原样透传（主回 256）
        val request = captured
        assertTrue("必须走流式（可取消路径）", request != null)
        val actual = request!!
        assertTrue(actual.stream)
        assertTrue(actual.includeUsage)
        assertEquals("deepseek-flash", actual.model)
        assertEquals(256, actual.maxTokens)
        assertEquals(2, actual.messages.size)
    }

    @Test
    fun missingUsageFallsBackToPositiveEstimate() = runBlocking {
        val recorded = mutableListOf<Usage>()
        val call = GroupReplyCall(
            model = "deepseek-flash",
            stream = {
                flow {
                    emit(LlmEvent.Delta("嗯，我在。"))
                    emit(LlmEvent.Finished(usage = null, truncated = false))
                }
            },
            recordUsage = { recorded += it },
        )

        call.invoke(prompt, 256)

        val usage = recorded.single()
        assertTrue("prompt 估算必须为正：${usage.promptTokens}", usage.promptTokens > 0)
        assertTrue("completion 估算必须为正：${usage.completionTokens}", usage.completionTokens > 0)
        assertTrue("估算金额必须 > 0", Pricing.costOf("deepseek-flash", usage) > 0.0)
    }

    @Test
    fun cancellationStopsStreamAndRecordsEstimatedUsage() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var streamCancelled = false
        val recorded = mutableListOf<Usage>()
        val call = GroupReplyCall(
            model = "deepseek-flash",
            stream = {
                flow {
                    try {
                        emit(LlmEvent.Delta("我已经说了一半，"))
                        started.complete(Unit)
                        // 用户不点「停下」就一直挂着；取消必须让 collect 立即结束
                        awaitCancellation()
                    } finally {
                        streamCancelled = true
                    }
                }
            },
            recordUsage = { recorded += it },
        )

        val job = launch { call.invoke(prompt, 256) }
        started.await() // 确保流已经开始被消费
        job.cancel()
        job.join()

        assertTrue("取消后必须停止消费流（生产侧即 cancel OkHttp Call）", streamCancelled)
        assertEquals("取消也要记一笔：已产生的用量不能丢", 1, recorded.size)
        val usage = recorded.single()
        assertTrue(usage.promptTokens > 0)
        assertTrue("已吐出的半句也要计入 completion 估算：${usage.completionTokens}", usage.completionTokens > 0)
    }

    @Test
    fun failureAfterPartialOutputRecordsEstimateAndThrows() = runBlocking {
        val recorded = mutableListOf<Usage>()
        val call = GroupReplyCall(
            model = "deepseek-flash",
            stream = {
                flow {
                    emit(LlmEvent.Delta("说到一半断了"))
                    emit(LlmEvent.Failed(ApiError.StreamBroken(raw = "unexpected end of stream")))
                }
            },
            recordUsage = { recorded += it },
        )

        try {
            call.invoke(prompt, 256)
            fail("流以 Failed 结束时必须抛给引擎降级")
        } catch (e: ApiException) {
            assertEquals(ApiError.Kind.STREAM_BROKEN, e.error.kind)
        }
        assertEquals("已经出字 = 服务商很可能已计费，必须记账", 1, recorded.size)
        assertTrue(recorded.single().completionTokens > 0)
    }

    @Test
    fun deepSeekFilteredTerminalAfterPartialPrefixThrowsInsteadOfReturningAGroupReply() = runBlocking {
        val recorded = mutableListOf<Usage>()
        val call = GroupReplyCall(
            model = "deepseek-flash",
            stream = {
                flow {
                    emit(LlmEvent.Delta("[[whisper]] 这段前缀不能成为群聊回复"))
                    emit(LlmEvent.Reasoning("这段思考也不能变成成功结果"))
                    emit(LlmEvent.Failed(ApiError.Filtered(httpCode = 200)))
                }
            },
            recordUsage = { recorded += it },
            internalBaseUrl = "https://api.deepseek.com/v1",
        )

        try {
            call.invoke(prompt, 256)
            fail("DeepSeek 终态拒绝不能把拒绝前缀作为群聊成功回复返回")
        } catch (e: ApiException) {
            assertEquals(ApiError.Kind.FILTERED, e.error.kind)
        }

        // 延续“已经出字就估算记账”的原语义；GroupReplyCall 的结果没有返回给记忆/群聊引擎。
        assertEquals(1, recorded.size)
        assertTrue(recorded.single().completionTokens > 0)
    }

    @Test
    fun failureBeforeAnyOutputDoesNotRecordFakeCharge() = runBlocking {
        val recorded = mutableListOf<Usage>()
        val call = GroupReplyCall(
            model = "deepseek-flash",
            stream = {
                flow { emit(LlmEvent.Failed(ApiError.NoKey())) }
            },
            recordUsage = { recorded += it },
        )

        try {
            call.invoke(prompt, 256)
            fail("没有密钥时必须失败")
        } catch (e: ApiException) {
            assertEquals(ApiError.Kind.NO_KEY, e.error.kind)
        }
        assertTrue("一个字都没出、请求都没发出去时不能凭空记一笔钱", recorded.isEmpty())
    }

    @Test
    fun recordFailureDoesNotTurnReplyIntoFailure() = runBlocking {
        val call = GroupReplyCall(
            model = "deepseek-flash",
            stream = {
                flow {
                    emit(LlmEvent.Delta("回复是好的。"))
                    emit(LlmEvent.Finished(usage = Usage(10, 5), truncated = false))
                }
            },
            recordUsage = { throw IllegalStateException("disk full") },
        )

        val output = call.invoke(prompt, 256)

        assertEquals("记账失败不能把已生成的回复判成失败", "回复是好的。", output.text)
        assertFalse(output.text.isEmpty())
    }

    // ---------------------------------------------------------------- 清空代际（收口残余 2）

    /**
 * 清空所有数据已发生（[GroupReplyCall] 的 `recordAllowed` 为 false）→ **成功轮也不写用量**。
 *
 * 为什么这层守卫必要：取消轮次的记账走 `NonCancellable`（`recordSafely`），协程取消挡不住，
 * 只能由发起方在开始时取代际、写前比对；否则清空后用量页会重新出现清空前的花费。
 * 正常路径（默认 `recordAllowed = { true }`）由上面 5 个既有用例继续锁。
 */
    @Test
    fun wipeAlreadyHappenedSkipsUsageRecordButStillReturnsReply() = runBlocking {
        var recordAllowed = true
        val recorded = mutableListOf<Usage>()
        val usage = Usage(promptTokens = 800, completionTokens = 12)
        val call = GroupReplyCall(
            model = "deepseek-flash",
            stream = {
                flow {
                    emit(LlmEvent.Delta("回一句。"))
                    // 模拟「本轮开始 → 流结束之间清空了所有数据」：Finished 之前代际已变。
                    recordAllowed = false
                    emit(LlmEvent.Finished(usage = usage, truncated = false))
                }
            },
            recordUsage = { recorded += it },
            recordAllowed = { recordAllowed },
        )

        val output = call.invoke(prompt, 256)

        assertEquals("清空后不得写用量（否则用量页重新出现清空前花费）", 0, recorded.size)
        assertEquals("回复正文照常返回（本地判断不影响已生成内容的去向）", "回一句。", output.text)
        assertTrue("正常路径的记账由既有 5 个用例继续锁（本用例只锁清空分支）", recordAllowed.not())
    }

    @Test
    fun wipeAlreadyHappenedSkipsEstimatedUsageOnCancellation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var streamCancelled = false
        val recorded = mutableListOf<Usage>()
        var recordAllowed = true
        val call = GroupReplyCall(
            model = "deepseek-flash",
            stream = {
                flow {
                    try {
                        emit(LlmEvent.Delta("我先说一半，"))
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        streamCancelled = true
                    }
                }
            },
            recordUsage = { recorded += it },
            recordAllowed = { recordAllowed },
        )

        val job = launch { call.invoke(prompt, 256) }
        started.await()
        // 清空发生 → 取消轮次的 NonCancellable 估算记账也必须被挡住。
        recordAllowed = false
        job.cancel()
        job.join()

        assertTrue("取消仍必须停止消费流", streamCancelled)
        assertEquals("清空后取消轮不得补记估算用量", 0, recorded.size)
    }

    // ---------------------------------------------------------------- 开口前安全瞬时重连

    @Test
    fun transientErrorsBeforeFirstTokenReconnectOnceAndRecordUsageOnce() = runBlocking {
        val transientErrors = listOf(
            ApiError.Network(raw = "unexpected EOF"),
            ApiError.Server(httpCode = 502, raw = "bad gateway"),
            ApiError.Quota(httpCode = 429, raw = "rate limit"),
        )
        for (transient in transientErrors) {
            var attempts = 0
            val recorded = mutableListOf<Usage>()
            val serverUsage = Usage(promptTokens = 500, completionTokens = 18)
            val call = GroupReplyCall(
                model = "deepseek-flash",
                stream = {
                    flow {
                        attempts++
                        if (attempts == 1) {
                            emit(LlmEvent.Failed(transient))
                        } else {
                            emit(LlmEvent.Delta("重连成功了。"))
                            emit(LlmEvent.Finished(usage = serverUsage, truncated = false))
                        }
                    }
                },
                recordUsage = { recorded += it },
                retryDelayMs = 0L,
            )

            val output = call.invoke(prompt, 256)

            assertEquals("瞬时错误($transient)应自动重连一次", 2, attempts)
            assertEquals("重连成功了。", output.text)
            assertEquals("仅成功那次记账 1 次", 1, recorded.size)
            assertEquals(serverUsage, recorded.single())
        }
    }

    @Test
    fun failureAfterDeltaOrReasoningNeverRetries() = runBlocking {
        val progressEvents = listOf<LlmEvent>(
            LlmEvent.Delta("已吐字"),
            LlmEvent.Reasoning("已思考"),
        )
        for (progress in progressEvents) {
            var attempts = 0
            val recorded = mutableListOf<Usage>()
            val call = GroupReplyCall(
                model = "deepseek-flash",
                stream = {
                    flow {
                        attempts++
                        emit(progress)
                        emit(LlmEvent.Failed(ApiError.Server(httpCode = 502)))
                    }
                },
                recordUsage = { recorded += it },
                retryDelayMs = 0L,
            )

            try {
                call.invoke(prompt, 256)
                fail("收到 $progress 后失败必须直接抛出 ApiException")
            } catch (e: ApiException) {
                assertEquals(ApiError.Kind.SERVER, e.error.kind)
            }
            assertEquals("收到进度事件($progress)后绝不重试", 1, attempts)
            if (progress is LlmEvent.Delta) {
                assertEquals("已吐字应记账 1 次", 1, recorded.size)
            } else {
                assertTrue("未吐字不记账", recorded.isEmpty())
            }
        }
    }

    @Test
    fun nonTransientErrorsBeforeContentDoNotRetry() = runBlocking {
        val nonRetryable = listOf(
            ApiError.NoKey(),
            ApiError.Auth(httpCode = 401),
            ApiError.BadResponse(httpCode = 400),
            ApiError.Quota(httpCode = 402),
        )
        for (error in nonRetryable) {
            var attempts = 0
            val recorded = mutableListOf<Usage>()
            val call = GroupReplyCall(
                model = "deepseek-flash",
                stream = {
                    flow {
                        attempts++
                        emit(LlmEvent.Failed(error))
                    }
                },
                recordUsage = { recorded += it },
                retryDelayMs = 0L,
            )

            try {
                call.invoke(prompt, 256)
                fail("非瞬时错误($error)必须直接失败")
            } catch (e: ApiException) {
                assertEquals(error.kind, e.error.kind)
            }
            assertEquals("非瞬时错误($error)不得重试", 1, attempts)
            assertTrue("未吐字不得记假账", recorded.isEmpty())
        }
    }

    @Test
    fun cancellationDuringRetryBackoffThrowsImmediatelyWithoutSecondAttemptOrFakeCharge() = runBlocking {
        var attempts = 0
        val recorded = mutableListOf<Usage>()
        val firstFailed = CompletableDeferred<Unit>()
        val call = GroupReplyCall(
            model = "deepseek-flash",
            stream = {
                flow {
                    attempts++
                    emit(LlmEvent.Failed(ApiError.Server(httpCode = 502)))
                    firstFailed.complete(Unit)
                }
            },
            recordUsage = { recorded += it },
            retryDelayMs = 10_000L,
        )

        var caughtCancellation = false
        val job = launch {
            try {
                call.invoke(prompt, 256)
            } catch (cancelled: CancellationException) {
                caughtCancellation = true
                throw cancelled
            }
        }
        firstFailed.await()
        // 让协程进入 retryDelayMs 的挂起等待窗口
        delay(30L)
        job.cancel()
        job.join()

        assertTrue("退避等待期间取消必须抛出 CancellationException", caughtCancellation)
        assertEquals("取消后绝不发第二次请求", 1, attempts)
        assertTrue("退避等待期间取消不得记假账", recorded.isEmpty())
    }
}

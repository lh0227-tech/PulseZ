package cc.pulsez.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动跟随滚动的纯判定（ 11； /）。
 *
 * 纯 JVM：`ChatScrollSnapshot`/`ChatAutoScroll` 是 `ChatScreen.kt` 末尾的顶层纯 Kotlin 声明，
 * 不引用任何 Compose 类型，因此不需要 Robolectric。
 *
 * 覆盖 A–B 判据：上翻不抢位置、贴底跟随、用户主动发送无视离底必回底、前插不跳底、
 * 群聊「正在写」辅助行、首次载入贴底、无变化不滚。
 *
 * 辅助行变化只在**新增**方向跟随（`itemCount` 增加），消失 / 减少方向一律不滚；
 * 锁住两个方向与布局新旧组合，保持既有滚动行为。
 *
 * ：贴底判据加**视口底边偏移**``–``：末条独自占满整屏时，
 * 用户在条内上翻 → 索引判据仍真但偏移判据判离底 → 不跟随；真贴底 → 跟随；单聊/群聊同形状；
 * 不传偏移参数时回退旧索引判据（既有 24 例依赖该回退）。
 *
 * 残余②：**粘性贴底**``–``：用户停在底部时流式增长会把末项
 * 底边顶出视口底（偏移判据为 false），只靠偏移判据会停止追流。粘性状态机只在用户**自己**
 * 滚动停手时刷新——停在底部持续跟随、上翻停手后不被拽回、滚动中不抢手势、群聊同形状。
 */
class ChatAutoScrollTest {

    private fun snapshot(
        id: String? = "m1",
        textLength: Int = 4,
        messageCount: Int = 3,
        itemCount: Int = 3,
        reasoningLength: Int = 0,
        citationsCount: Int = 0,
        isStreaming: Boolean = false,
    ) = ChatScrollSnapshot(
        lastMessageId = id,
        lastMessageTextLength = textLength,
        messageCount = messageCount,
        itemCount = itemCount,
        lastReasoningLength = reasoningLength,
        lastCitationsCount = citationsCount,
        isStreaming = isStreaming,
    )

    /**
 * 贴底：视口正好铺满到末条（`first + visible == total`）。
 *
 * 新增四个可选偏移参数：传了就再走一层「末项底边到视口底」判据，
 * 不传（默认 null）就是旧的纯索引判据——既有用例全部不传，语义不变。
 */
    private fun follow(
        previous: ChatScrollSnapshot?,
        current: ChatScrollSnapshot,
        firstVisible: Int = 0,
        visible: Int = 3,
        total: Int = 3,
        followAuxiliaryRow: Boolean = false,
        lastVisibleItemIndex: Int? = null,
        lastVisibleItemOffset: Int? = null,
        lastVisibleItemSize: Int? = null,
        viewportEndOffset: Int? = null,
        stickyAtBottom: Boolean? = null,
        userScrolling: Boolean? = null,
        userSent: Boolean = false,
    ): Boolean = ChatAutoScroll.shouldFollow(
        previous = previous,
        current = current,
        firstVisibleItemIndex = firstVisible,
        visibleItemCount = visible,
        totalItemCount = total,
        followAuxiliaryRow = followAuxiliaryRow,
        lastVisibleItemIndex = lastVisibleItemIndex,
        lastVisibleItemOffset = lastVisibleItemOffset,
        lastVisibleItemSize = lastVisibleItemSize,
        viewportEndOffset = viewportEndOffset,
        stickyAtBottom = stickyAtBottom,
        userScrolling = userScrolling,
        userSent = userSent,
    )

    @Test
    fun `长末条计算贴底所需偏移`() {
        assertEquals(
            400,
            tailOffsetForFollow(
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = 0,
                lastVisibleItemSize = 2400,
                viewportEndOffset = 2000,
                targetItemIndex = 9,
            ),
        )
    }

    @Test
    fun `短末条不需要额外偏移`() {
        assertEquals(
            0,
            tailOffsetForFollow(
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = 100,
                lastVisibleItemSize = 300,
                viewportEndOffset = 2000,
                targetItemIndex = 9,
            ),
        )
    }

    @Test
    fun `目标不是当前可见末项时偏移为零`() {
        assertEquals(
            0,
            tailOffsetForFollow(
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = 0,
                lastVisibleItemSize = 2600,
                viewportEndOffset = 2000,
                targetItemIndex = 10,
            ),
        )
    }

    @Test
    fun `未知或空布局偏移为零`() {
        assertEquals(
            0,
            tailOffsetForFollow(
                lastVisibleItemIndex = -1,
                lastVisibleItemOffset = 0,
                lastVisibleItemSize = 2600,
                viewportEndOffset = 2000,
                targetItemIndex = 9,
            ),
        )
        assertEquals(
            0,
            tailOffsetForFollow(
                lastVisibleItemIndex = -1,
                lastVisibleItemOffset = 0,
                lastVisibleItemSize = 0,
                viewportEndOffset = 0,
                targetItemIndex = -1,
            ),
        )
    }

    @Test
    fun `末条底边正好到视口底时偏移为零`() {
        assertEquals(
            0,
            tailOffsetForFollow(
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = -400,
                lastVisibleItemSize = 2400,
                viewportEndOffset = 2000,
                targetItemIndex = 9,
            ),
        )
    }

    @Test
    fun `尾部偏移中间计算溢出时限制到Int上限`() {
        assertEquals(
            Int.MAX_VALUE,
            tailOffsetForFollow(
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = Int.MAX_VALUE,
                lastVisibleItemSize = Int.MAX_VALUE,
                viewportEndOffset = Int.MIN_VALUE,
                targetItemIndex = 9,
            ),
        )
    }

    // ---------------------------------------------------------------- 末条占满整屏

    // 几何（与 LazyListLayoutInfo / LazyListItemInfo 同一坐标系）：
    // - lastItemOffset 是末项相对列表容器主轴的偏移，viewportEndOffset 是可滚动到的最远偏移；
    // - 视口底边取 2000、末项主轴高取 2400（末项比视口高 = 「独自占满整屏」）；
    // - 真贴底时末项底边 = 1968（= viewportEnd - 32px 底部内容留白，12dp @ ~2.7x 密度），
    // 即 lastItemOffset = 1968 - 2400 = -432；
    // - 用户在末项内部上翻 200px → 底边 2168，lastItemOffset 变成 -432 + 200 = -232，
    // 超过「2000 + 2px 容差」→ 偏移判据判「还能继续向下滚」。
    private val viewportEnd = 2000
    private val longLastSize = 2400
    private val atBottomLastOffset = (viewportEnd - 32) - longLastSize // = -432

    @Test
    fun `长末条内部上滚 索引判据仍为真但底边未到视口底 不跟随`() {
        // 的反例形状：total=10、first=9、visible=1 → 9+1>=10 恒真。
        // 只靠索引判据会把「用户在末项内部上翻」误判成贴底，流式增量把 TA 拽回去。
        val previous = snapshot(id = "m10", textLength = 5, messageCount = 10, itemCount = 10)
        val current = snapshot(id = "m10", textLength = 12, messageCount = 10, itemCount = 10)
        assertFalse(
            "末条独自占满整屏、用户在该条内部上翻时，流式增量不得把 TA 拽回",
            follow(
                previous,
                current,
                firstVisible = 9,
                visible = 1,
                total = 10,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = atBottomLastOffset + 200,
                lastVisibleItemSize = longLastSize,
                viewportEndOffset = viewportEnd,
            ),
        )
    }

    @Test
    fun `长末条真贴底 流式增量跟随`() {
        val previous = snapshot(id = "m10", textLength = 5, messageCount = 10, itemCount = 10)
        val current = snapshot(id = "m10", textLength = 12, messageCount = 10, itemCount = 10)
        assertTrue(
            "末项底边已经在视口底（底部内容留白以内）→ 真贴底，流式增量必须跟随",
            follow(
                previous,
                current,
                firstVisible = 9,
                visible = 1,
                total = 10,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = atBottomLastOffset,
                lastVisibleItemSize = longLastSize,
                viewportEndOffset = viewportEnd,
            ),
        )
    }

    @Test
    fun `群聊同形状 末条内部上滚不跟随 真贴底跟随`() {
        // 群聊页传 followAuxiliaryRow=true，但贴底判定与单聊共用同一纯函数（明确「群聊共享」）。
        val previous = snapshot(id = "g10", textLength = 5, messageCount = 10, itemCount = 10)
        val current = snapshot(id = "g10", textLength = 12, messageCount = 10, itemCount = 10)
        assertFalse(
            "群聊：末条独自占满整屏、用户在条内上翻时，流式增量不得跟随",
            follow(
                previous,
                current,
                firstVisible = 9,
                visible = 1,
                total = 10,
                followAuxiliaryRow = true,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = atBottomLastOffset + 200,
                lastVisibleItemSize = longLastSize,
                viewportEndOffset = viewportEnd,
            ),
        )
        assertTrue(
            "群聊：真贴底时仍跟随（与单聊同一判据）",
            follow(
                previous,
                current,
                firstVisible = 9,
                visible = 1,
                total = 10,
                followAuxiliaryRow = true,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = atBottomLastOffset,
                lastVisibleItemSize = longLastSize,
                viewportEndOffset = viewportEnd,
            ),
        )
    }

    @Test
    fun `偏移参数为 null 回退旧索引判据`() {
        // 兼容口径（要求）：不传偏移信息时保持既有语义不变——两侧参数都为 null 的
        // 当前 24 例依赖这一点；新调用点必须传真实值（见 ChatScreen/GroupChatScreen 接线）。
        val previous = snapshot(id = "m10", textLength = 5, messageCount = 10, itemCount = 10)
        val current = snapshot(id = "m10", textLength = 12, messageCount = 10, itemCount = 10)
        assertTrue(
            "不传偏移参数 = 旧索引判据（9+1>=10 判贴底）",
            follow(previous, current, firstVisible = 9, visible = 1, total = 10),
        )
    }

    @Test
    fun `追加同帧 末项还没进视口 偏移判据不适用 仍按 slack 跟随`() {
        // 布局已含新 item（total=3）但视口还停在旧末项（lastVisible 下标 1 < total-1 = 2）：
        // 偏移判据「最后可见项必须是末项」不成立时，交回索引判据与 slack。
        // 这里故意让末项（旧末项）底边远超视口底，证明没有误用偏移判据把它判成离底。
        val previous = snapshot(id = "m2", messageCount = 2, itemCount = 2)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        assertTrue(
            "布局已更新、视口还没铺到新 item：偏移判据不适用，索引 + slack 判贴底",
            follow(
                previous,
                current,
                firstVisible = 0,
                visible = 2,
                total = 3,
                lastVisibleItemIndex = 1,
                lastVisibleItemOffset = 1500,
                lastVisibleItemSize = 600, // 底边 2100 > 2002，若误用偏移判据会返回 false
                viewportEndOffset = viewportEnd,
            ),
        )
    }

    @Test
    fun `追加末条且末项可见 偏移判据同时生效 贴底跟随`() {
        // TA 追加新消息（id 变化）；布局已更新到 10 项，末项（新消息）已在视口里且底边贴底。
        val previous = snapshot(id = "m9", messageCount = 9, itemCount = 9)
        val current = snapshot(id = "m10", messageCount = 10, itemCount = 10)
        assertTrue(
            "追加新消息 + 末项底边贴底 → 跟随",
            follow(
                previous,
                current,
                firstVisible = 1,
                visible = 9,
                total = 10,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = viewportEnd - 32 - longLastSize,
                lastVisibleItemSize = longLastSize,
                viewportEndOffset = viewportEnd,
            ),
        )
    }

    @Test
    fun `偏移判据边界与 isAtBottom 收紧`() {
        // isAtBottomWithOffsets 直接边界：底边 = 视口底 + 容差(2) 内算贴底，再多 1px 就不算。
        assertFalse("total=0 不算贴底", ChatAutoScroll.isAtBottomWithOffsets(0, 0, 0, 0, viewportEnd))
        assertTrue("尚未布局（index=-1）按贴底处理", ChatAutoScroll.isAtBottomWithOffsets(-1, 0, 0, 5, viewportEnd))
        assertFalse(
            "末项不在视口里（index < total-1）→ 还能向下滚",
            ChatAutoScroll.isAtBottomWithOffsets(8, 0, 100, 10, viewportEnd),
        )
        assertTrue(
            "底边 = 视口底 + 2px 容差内 → 贴底",
            ChatAutoScroll.isAtBottomWithOffsets(9, viewportEnd - 100 + 2, 100, 10, viewportEnd),
        )
        assertFalse(
            "底边 = 视口底 + 3px（超出 2px 容差）→ 不贴底",
            ChatAutoScroll.isAtBottomWithOffsets(9, viewportEnd - 100 + 3, 100, 10, viewportEnd),
        )
        // isAtBottom 在索引判据为真时，偏移判据可再收紧；不传偏移参数时不受影响（上面既有用例）。
        assertTrue(
            "索引判据真 + 底边贴底 → 贴底",
            ChatAutoScroll.isAtBottom(
                9, 1, 10,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = viewportEnd - 100,
                lastVisibleItemSize = 100,
                viewportEndOffset = viewportEnd,
            ),
        )
        assertFalse(
            "索引判据真（9+1>=10）但底边离视口底 200px → 不贴底",
            ChatAutoScroll.isAtBottom(
                9, 1, 10,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = viewportEnd - 100 + 200,
                lastVisibleItemSize = 100,
                viewportEndOffset = viewportEnd,
            ),
        )
    }

    // ---------------------------------------------------------------- 粘性贴底（残余②）

    // 场景：用户停在底部时流式正文增长，布局里末项底边已经被顶出视口底（raw=false），
    // 但这不是「用户上翻」，粘性贴底必须让自动跟随继续（否则回复越长越看不到新字）。
    // 上翻 300px：底边 = 1968 + 300 = 2268 > 2002 → 偏移判据为 false。
    private val scrolledUpLastOffset = atBottomLastOffset + 300

    @Test
    fun `粘性贴底 停在底部连续多帧流式增长 持续跟随`() {
        // 内容增长不是用户滚动，不能刷新粘性。
        assertTrue(
            "内容增长/布局变化不刷新粘性（scrollStopped=false 时 sticky 保持）",
            StickyBottom.update(previous = true, scrollStopped = false, rawAtBottom = false),
        )
        // 连续 4 帧：每帧正文变长、每帧布局里末项底边都超出视口底（raw=false）。
        val lengths = listOf(12, 20, 33, 47)
        var previousLength = 5
        for ((frame, length) in lengths.withIndex()) {
            val previous = snapshot(id = "m10", textLength = previousLength, messageCount = 10, itemCount = 10)
            val current = snapshot(id = "m10", textLength = length, messageCount = 10, itemCount = 10)
            assertTrue(
                "第 ${frame + 1} 帧：粘性贴底 + 流式增长必须持续跟随（否则长回复会停在半截）",
                follow(
                    previous,
                    current,
                    firstVisible = 9,
                    visible = 1,
                    total = 10,
                    lastVisibleItemIndex = 9,
                    lastVisibleItemOffset = scrolledUpLastOffset, // 偏移判据 raw=false
                    lastVisibleItemSize = longLastSize,
                    viewportEndOffset = viewportEnd,
                    stickyAtBottom = true,
                    userScrolling = false,
                ),
            )
            previousLength = length
        }
    }

    @Test
    fun `长末条内部上翻停手 粘性翻为不贴底 后续流式不跟随`() {
        // 用户上翻 300px 后松手：滚动停手边沿用真实偏移刷新粘性 → false。
        val afterStop = StickyBottom.update(previous = true, scrollStopped = true, rawAtBottom = false)
        assertFalse("用户上翻后停手：停手边沿把粘性刷新成 false", afterStop)
        assertFalse(
            "非停手边沿的变化不得把粘性翻回 true",
            StickyBottom.update(previous = afterStop, scrollStopped = false, rawAtBottom = true),
        )
        val previous = snapshot(id = "m10", textLength = 5, messageCount = 10, itemCount = 10)
        val current = snapshot(id = "m10", textLength = 12, messageCount = 10, itemCount = 10)
        assertFalse(
            "sticky=false 且当前偏移不贴底 → 流式增长不跟随（用户阅读位置不被抢）",
            follow(
                previous,
                current,
                firstVisible = 9,
                visible = 1,
                total = 10,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = scrolledUpLastOffset,
                lastVisibleItemSize = longLastSize,
                viewportEndOffset = viewportEnd,
                stickyAtBottom = false,
                userScrolling = false,
            ),
        )
    }

    @Test
    fun `用户滚回底部停手 粘性恢复 流式增长重新跟随`() {
        val afterStop = StickyBottom.update(previous = false, scrollStopped = true, rawAtBottom = true)
        assertTrue("用户滚回底部停手：停手边沿把粘性刷新成 true", afterStop)
        val previous = snapshot(id = "m10", textLength = 5, messageCount = 10, itemCount = 10)
        val current = snapshot(id = "m10", textLength = 12, messageCount = 10, itemCount = 10)
        assertTrue(
            "sticky=true 时即使本帧末项底边已被增长顶出视口底，也要继续跟随",
            follow(
                previous,
                current,
                firstVisible = 9,
                visible = 1,
                total = 10,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = scrolledUpLastOffset,
                lastVisibleItemSize = longLastSize,
                viewportEndOffset = viewportEnd,
                stickyAtBottom = true,
                userScrolling = false,
            ),
        )
    }

    @Test
    fun `用户滚动中不跟随 避免抢手势`() {
        val previous = snapshot(id = "m10", textLength = 5, messageCount = 10, itemCount = 10)
        val current = snapshot(id = "m10", textLength = 12, messageCount = 10, itemCount = 10)
        assertFalse(
            "用户拖拽/fling 中即使粘性与偏移都说贴底，也不自动跟随",
            follow(
                previous,
                current,
                firstVisible = 9,
                visible = 1,
                total = 10,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = atBottomLastOffset, // 偏移判据 raw=true
                lastVisibleItemSize = longLastSize,
                viewportEndOffset = viewportEnd,
                stickyAtBottom = true,
                userScrolling = true,
            ),
        )
        // 停手后（userScrolling=false）同样条件恢复跟随。
        assertTrue(
            "用户停手后恢复跟随",
            follow(
                previous,
                current,
                firstVisible = 9,
                visible = 1,
                total = 10,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = atBottomLastOffset,
                lastVisibleItemSize = longLastSize,
                viewportEndOffset = viewportEnd,
                stickyAtBottom = true,
                userScrolling = false,
            ),
        )
    }

    @Test
    fun `他人新消息 不在底部 不跟随`() {
        val previous = snapshot(id = "m9", messageCount = 9, itemCount = 9)
        val current = snapshot(id = "m10", messageCount = 10, itemCount = 10)
        assertFalse(
            "TA 追加新消息：用户上翻停手（sticky=false）且末项底边不贴底 → 不跟随",
            follow(
                previous,
                current,
                firstVisible = 9,
                visible = 1,
                total = 10,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = scrolledUpLastOffset,
                lastVisibleItemSize = longLastSize,
                viewportEndOffset = viewportEnd,
                stickyAtBottom = false,
                userScrolling = false,
            ),
        )
    }

    @Test
    fun `群聊同形状 粘性贴底持续跟随 上翻停手不跟随`() {
        // 群聊与单聊共用同一纯函数（followAuxiliaryRow=true 只影响辅助行分支）。
        val streamPrevious = snapshot(id = "g10", textLength = 5, messageCount = 10, itemCount = 10)
        val streamCurrent = snapshot(id = "g10", textLength = 12, messageCount = 10, itemCount = 10)
        assertTrue(
            "群聊：粘性贴底 + 流式增长（底边已顶出视口底）→ 继续跟随",
            follow(
                streamPrevious,
                streamCurrent,
                firstVisible = 9,
                visible = 1,
                total = 10,
                followAuxiliaryRow = true,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = scrolledUpLastOffset,
                lastVisibleItemSize = longLastSize,
                viewportEndOffset = viewportEnd,
                stickyAtBottom = true,
                userScrolling = false,
            ),
        )
        assertFalse(
            "群聊：上翻停手后 sticky=false → 流式增长不跟随",
            follow(
                streamPrevious,
                streamCurrent,
                firstVisible = 9,
                visible = 1,
                total = 10,
                followAuxiliaryRow = true,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = scrolledUpLastOffset,
                lastVisibleItemSize = longLastSize,
                viewportEndOffset = viewportEnd,
                stickyAtBottom = false,
                userScrolling = false,
            ),
        )
        // 群聊「正在写」辅助行新增（messageCount 不变、itemCount 10→11）：同样走粘性判据。
        val auxPrevious = snapshot(id = "g10", messageCount = 10, itemCount = 10)
        val auxCurrent = snapshot(id = "g10", messageCount = 10, itemCount = 11)
        assertTrue(
            "群聊：辅助行新增 + 粘性贴底 → 跟随（底边已顶出视口底）",
            follow(
                auxPrevious,
                auxCurrent,
                firstVisible = 9,
                visible = 2,
                total = 11,
                followAuxiliaryRow = true,
                lastVisibleItemIndex = 10,
                lastVisibleItemOffset = 1900,
                lastVisibleItemSize = 200, // 底边 2100 > 2002
                viewportEndOffset = viewportEnd,
                stickyAtBottom = true,
                userScrolling = false,
            ),
        )
        assertFalse(
            "群聊：辅助行新增但上翻停手 → 不跟随",
            follow(
                auxPrevious,
                auxCurrent,
                firstVisible = 9,
                visible = 2,
                total = 11,
                followAuxiliaryRow = true,
                lastVisibleItemIndex = 10,
                lastVisibleItemOffset = 1900,
                lastVisibleItemSize = 200,
                viewportEndOffset = viewportEnd,
                stickyAtBottom = false,
                userScrolling = false,
            ),
        )
    }

    @Test
    fun `粘性刷新几何判据 未知时不清粘性`() {
        assertTrue(
            "真贴底 → 粘性刷新为 true",
            ChatAutoScroll.isAtBottomByGeometry(9, atBottomLastOffset, longLastSize, 10, viewportEnd),
        )
        assertFalse(
            "上翻后底边超出视口底 → 粘性刷新为 false",
            ChatAutoScroll.isAtBottomByGeometry(9, scrolledUpLastOffset, longLastSize, 10, viewportEnd),
        )
        assertFalse(
            "末项不在视口里 → 还能向下滚",
            ChatAutoScroll.isAtBottomByGeometry(8, 0, 100, 10, viewportEnd),
        )
        assertTrue(
            "尚未布局（total=0）按未知贴底处理，不清粘性",
            ChatAutoScroll.isAtBottomByGeometry(null, null, null, 0, null),
        )
        assertTrue(
            "没有可见项按未知贴底处理，不清粘性",
            ChatAutoScroll.isAtBottomByGeometry(null, null, null, 10, viewportEnd),
        )
    }

    // ---------------------------------------------------------------- 首次载入

    @Test
    fun `首次载入 无上一快照 无条件贴底`() {
        assertTrue(follow(previous = null, current = snapshot(), firstVisible = 0, visible = 1, total = 1))
    }

    @Test
    fun `空到第一条 上一快照没有末条 贴底`() {
        val previous = snapshot(id = null, textLength = 0, messageCount = 0, itemCount = 0)
        assertTrue(follow(previous = previous, current = snapshot(), firstVisible = -1, visible = 0, total = 0))
    }

    // ---------------------------------------------------------------- 流式增长

    @Test
    fun `流式增长 用户贴底 跟随`() {
        val previous = snapshot(textLength = 5)
        val current = snapshot(textLength = 12)
        // 贴底：0 + 3 == 3。
        assertTrue(follow(previous, current, firstVisible = 0, visible = 3, total = 3))
    }

    @Test
    fun `流式增长 用户上翻 不跟随`() {
        val previous = snapshot(textLength = 5)
        val current = snapshot(textLength = 12)
        // 上翻：视口离底 ≥2 条（末条不可）。
        assertFalse(follow(previous, current, firstVisible = 0, visible = 1, total = 6))
    }

    // ---------------------------------------------------------------- 追加新消息（TA）

    @Test
    fun `TA 追加新消息 贴底时跟随 布局尚未更新也算贴底`() {
        val previous = snapshot(id = "m2", messageCount = 2, itemCount = 2)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        // 时序 A：item 已追加但 layoutInfo 还是旧总数（2）。slack=1 允许少算一条。
        assertTrue(follow(previous, current, firstVisible = 0, visible = 2, total = 2))
        // 时序 B：layoutInfo 已更新到 3。
        assertTrue(follow(previous, current, firstVisible = 0, visible = 3, total = 3))
    }

    @Test
    fun `TA 追加新消息 用户上翻 不跟随`() {
        val previous = snapshot(id = "m2", messageCount = 2, itemCount = 2)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        // 视口离底 ≥2 条：0 + 1 + slack(1) < 6。
        assertFalse(follow(previous, current, firstVisible = 0, visible = 1, total = 6))
    }

    // ---------------------------------------------------------------- 用户主动发送 vs 外部新消息

    @Test
    fun `用户主动发送 无论是否贴底都必回底 外部新消息离底时不抢位`() {
        val previous = snapshot(id = "m2", messageCount = 2, itemCount = 2)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        // 离底时用户主动发送（userSent=true）必返回 true，强制滚回底部并恢复跟随。
        assertTrue(
            "离底时用户主动发送必须回到底部",
            follow(
                previous,
                current,
                firstVisible = 0,
                visible = 1,
                total = 6,
                stickyAtBottom = false,
                userScrolling = false,
                userSent = true,
            ),
        )
        // 在底部发送同样跟随。
        assertTrue(
            follow(
                previous,
                current,
                firstVisible = 0,
                visible = 2,
                total = 2,
                userSent = true,
            ),
        )
        // 外部新消息（userSent=false）在离底时不得把用户拽回末尾。
        assertFalse(
            "外部新增消息在离底时不得抢位",
            follow(
                previous,
                current,
                firstVisible = 0,
                visible = 1,
                total = 6,
                stickyAtBottom = false,
                userScrolling = false,
                userSent = false,
            ),
        )
    }

    @Test
    fun `有粘性状态时 双条同步插入或键盘缩视口不再被索引门禁误杀`() {
        val previous = snapshot(id = "m10", messageCount = 10, itemCount = 10)
        // 双条同步插入（user + empty assistant）：itemCount=12，但视口还只铺到第 10 项（0+10+slack < 12）。
        val current = snapshot(id = "m12", textLength = 0, messageCount = 12, itemCount = 12)
        assertTrue(
            "stickyAtBottom=true 且未在拖动时，双条插入或视口缩小时仍必须跟随",
            follow(
                previous,
                current,
                firstVisible = 0,
                visible = 10,
                total = 12,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = 1800,
                lastVisibleItemSize = 300,
                viewportEndOffset = viewportEnd,
                stickyAtBottom = true,
                userScrolling = false,
            ),
        )
        assertFalse(
            "用户拖动中不抢手势",
            follow(
                previous,
                current,
                firstVisible = 0,
                visible = 10,
                total = 12,
                lastVisibleItemIndex = 9,
                lastVisibleItemOffset = 1800,
                lastVisibleItemSize = 300,
                viewportEndOffset = viewportEnd,
                stickyAtBottom = true,
                userScrolling = true,
            ),
        )
    }

    @Test
    fun `思考内容增长 来源引用出现与流式开始 在贴底时跟随 离底时不抢位`() {
        val base = snapshot(id = "m10", textLength = 0, messageCount = 10, itemCount = 10, reasoningLength = 0, citationsCount = 0, isStreaming = false)
        val started = base.copy(isStreaming = true)
        assertTrue(
            "开始流式时贴底跟随",
            follow(base, started, firstVisible = 0, visible = 10, total = 10, lastVisibleItemIndex = 9, lastVisibleItemOffset = 1800, lastVisibleItemSize = 150, viewportEndOffset = viewportEnd, stickyAtBottom = true, userScrolling = false),
        )
        assertFalse(
            "开始流式时离底不抢位",
            follow(base, started, firstVisible = 0, visible = 5, total = 10, lastVisibleItemIndex = 4, lastVisibleItemOffset = 1800, lastVisibleItemSize = 150, viewportEndOffset = viewportEnd, stickyAtBottom = false, userScrolling = false),
        )
        val reasoned = started.copy(lastReasoningLength = 24)
        assertTrue(
            "深度思考内容增长时贴底跟随",
            follow(started, reasoned, firstVisible = 0, visible = 10, total = 10, lastVisibleItemIndex = 9, lastVisibleItemOffset = 1850, lastVisibleItemSize = 200, viewportEndOffset = viewportEnd, stickyAtBottom = true, userScrolling = false),
        )
        assertFalse(
            "深度思考内容增长时离底不抢位",
            follow(started, reasoned, firstVisible = 0, visible = 5, total = 10, lastVisibleItemIndex = 4, lastVisibleItemOffset = 1850, lastVisibleItemSize = 200, viewportEndOffset = viewportEnd, stickyAtBottom = false, userScrolling = false),
        )
        val cited = reasoned.copy(lastCitationsCount = 2, isStreaming = false)
        assertTrue(
            "引用来源新增时贴底跟随",
            follow(reasoned, cited, firstVisible = 0, visible = 10, total = 10, lastVisibleItemIndex = 9, lastVisibleItemOffset = 1850, lastVisibleItemSize = 200, viewportEndOffset = viewportEnd, stickyAtBottom = true, userScrolling = false),
        )
    }

    // ---------------------------------------------------------------- 前插更早消息

    @Test
    fun `前插更早消息不跳底`() {
        // 末条 id/正文都没变，只是前面多插了 5 条（messageCount 20→25，itemCount→26 含「看看更早的」）。
        val previous = snapshot(id = "m20", textLength = 4, messageCount = 20, itemCount = 20)
        val current = snapshot(id = "m20", textLength = 4, messageCount = 25, itemCount = 26)
        assertFalse(
            "加载更早消息是前插，不能把正在看顶部的用户拽回底部",
            follow(previous, current, firstVisible = 0, visible = 3, total = 26),
        )
    }

    // ---------------------------------------------------------------- 群聊「正在写」辅助行

    @Test
    fun `群聊正在写行出现 贴底时跟随`() {
        val previous = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 4)
        // typing 行出现，layoutInfo 可能仍是旧总数（3）→ slack 允许。
        assertTrue(
            follow(previous, current, firstVisible = 0, visible = 3, total = 3, followAuxiliaryRow = true),
        )
        // 已布局到 4（贴底）。
        assertTrue(
            follow(previous, current, firstVisible = 0, visible = 4, total = 4, followAuxiliaryRow = true),
        )
    }

    @Test
    fun `群聊正在写行出现 上翻时不跟随`() {
        val previous = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 4)
        assertFalse(
            follow(previous, current, firstVisible = 0, visible = 1, total = 6, followAuxiliaryRow = true),
        )
    }

    @Test
    fun `单聊辅助行变化 不跟随`() {
        val previous = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 4)
        assertFalse(
            "单聊传 followAuxiliaryRow=false，辅助行变化不触发滚动",
            follow(previous, current, firstVisible = 0, visible = 3, total = 3, followAuxiliaryRow = false),
        )
    }

    // ---------------------------------------------------------------- 无变化 / 空 / 未布局

    @Test
    fun `无变化不滚 末条为空不滚 有上一快照但尚未布局不滚`() {
        val same = snapshot()
        assertFalse("无变化不滚", follow(same, same, firstVisible = 0, visible = 3, total = 3))
        assertFalse(
            "末条为 null 不滚",
            follow(previous = null, current = snapshot(id = null, messageCount = 0, itemCount = 0)),
        )
        assertFalse(
            "已有上一快照且 layout 尚未布局（total=0）→ 不滚",
            follow(snapshot(id = "m1"), snapshot(id = "m2"), firstVisible = -1, visible = 0, total = 0),
        )
    }

    @Test
    fun `首次载入优先于 layout 判断 尚未布局也要贴底`() {
        // 进聊天页首帧：LazyColumn 还没布局（total=0），但必须落到最新一条。
        assertTrue(
            "首次载入必须贴底，否则进聊天页会停在顶部",
            follow(previous = null, current = snapshot(), firstVisible = -1, visible = 0, total = 0),
        )
    }

    // ---------------------------------------------------------------- slack 只在布局已含新 item 时给

    // 四格：用户位置（追加前） × layout 是否已反映新 item。
    // 场景：m2 是旧末项，状态里已追加 m3（current.itemCount=3），layoutInfo.totalItemsCount 可能还是 2。
    // 「贴底」= 视口铺到旧末项（visible=2）；「距末项一行」= 视口停在旧末项前一行（visible=1）。

    @Test
    fun `距末项一行 布局还停在追加前 不跟随`() {
        val previous = snapshot(id = "m2", messageCount = 2, itemCount = 2)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        // total=2 < itemCount=3 → slack 必须为 0；旧规则 slack=1 会把 0+1 判成贴底并拽到底部。
        assertFalse(
            "布局未含新 item 时给 slack，会把「距末项一行」误判成贴底",
            follow(previous, current, firstVisible = 0, visible = 1, total = 2),
        )
    }

    @Test
    fun `贴底 布局还停在追加前 跟随`() {
        val previous = snapshot(id = "m2", messageCount = 2, itemCount = 2)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        // total=2 且视口铺满（0+2>=2）：真贴底，slack=0 也跟随。
        assertTrue(
            "布局未更新但视口已铺到旧末项 = 贴底，必须跟随",
            follow(previous, current, firstVisible = 0, visible = 2, total = 2),
        )
    }

    @Test
    fun `贴底 布局已含新 item 跟随`() {
        val previous = snapshot(id = "m2", messageCount = 2, itemCount = 2)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        // total=3 已含 m3；追加前贴底的用户滚动位置没动，视口仍只铺到旧末项 → 0+2 == total-1，
        // slack=1 才能认出「贴底」（去掉 slack 本条会变 false，保证该容差仍被测试锁住）。
        assertTrue(
            "布局已更新时，追加前贴底的用户会少算新 item 一行，需要 slack=1",
            follow(previous, current, firstVisible = 0, visible = 2, total = 3),
        )
    }

    @Test
    fun `距末项一行 布局已含新 item 不跟随`() {
        val previous = snapshot(id = "m2", messageCount = 2, itemCount = 2)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        // total=3 已含 m3，视口仍停在旧末项前一行（0+1）→ 加 slack 也只有 2 < 3，不能跟随。
        assertFalse(
            "布局已更新也不能把「距末项一行」的用户当贴底",
            follow(previous, current, firstVisible = 0, visible = 1, total = 3),
        )
    }

    @Test
    fun `群聊正在写行 布局未更新时距末项一行不跟随 已更新时贴底跟随`() {
        // 第二个 slack 调用点（群聊辅助行）：同一收紧规则必须同样生效。
        val previous = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 4) // typing 行已进状态
        assertFalse(
            "布局还停在追加前（total=3 < 4）：视口只铺到旧末项前一行，不能给 slack",
            follow(previous, current, firstVisible = 0, visible = 2, total = 3, followAuxiliaryRow = true),
        )
        assertTrue(
            "布局已含 typing 行（total=4）：追加前行贴底的用户少算一行，slack=1 才跟随",
            follow(previous, current, firstVisible = 0, visible = 3, total = 4, followAuxiliaryRow = true),
        )
    }

    // ---------------------------------------------------------------- 辅助行只在新增方向跟随

    // 场景：群聊「正在写」行出现 / 消失——`messageCount` 不变（没有新消息），只有 `itemCount` 变。
    // 「贴底」= 视口铺到旧末项；「距末项一行」= 视口停在旧末项前一行。

    @Test
    fun `辅助行新增 布局未更新 贴底跟随 距末项一行不跟随`() {
        val previous = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 4) // typing 行已进状态
        assertTrue(
            "新增方向、布局未更新、视口已铺到旧末项，视为贴底并跟随",
            follow(previous, current, firstVisible = 0, visible = 3, total = 3, followAuxiliaryRow = true),
        )
        assertFalse(
            "新增方向、布局未更新、视口停在旧末项前一行时不跟随",
            follow(previous, current, firstVisible = 0, visible = 2, total = 3, followAuxiliaryRow = true),
        )
    }

    @Test
    fun `辅助行新增 布局已更新 贴底跟随 距末项一行不跟随`() {
        val previous = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 4)
        assertTrue(
            "新增方向、布局已含 typing 行（total=4）：追加前贴底的用户少算一行，slack=1 才跟随",
            follow(previous, current, firstVisible = 0, visible = 3, total = 4, followAuxiliaryRow = true),
        )
        assertFalse(
            "新增方向、布局已含 typing 行（total=4）：视口只到前 3 项（0+2+slack=3 < 4）不跟随",
            follow(previous, current, firstVisible = 0, visible = 2, total = 4, followAuxiliaryRow = true),
        )
    }

    @Test
    fun `辅助行消失 布局已更新 不跟随`() {
        // typing 行消失：itemCount 5→4，layoutInfo 已回到 4，视口只到前 4 项。
        // 旧规则 slack=(4>=4)=1 → 0+4+1>=4 判贴底并主动滚； 起减少方向一律不滚
        // （行消失没有新内容要展示，视口已铺满时滚不滚用户看到的都一样，不滚更不打扰）。
        val previous = snapshot(id = "m4", messageCount = 4, itemCount = 5)
        val current = snapshot(id = "m4", messageCount = 4, itemCount = 4)
        assertFalse(
            "辅助行消失不能触发滚动",
            follow(previous, current, firstVisible = 0, visible = 4, total = 4, followAuxiliaryRow = true),
        )
    }

    @Test
    fun `辅助行消失 布局还停在消失前 不跟随`() {
        // 第三方 原场景：itemCount 5→4，layoutInfo 仍是 5（旧）；视口只到前 4 项。
        // 旧规则 slack=(5>=4)=1 → 0+4+1>=5 判贴底并主动滚到末项，把用户拽到底部。
        val previous = snapshot(id = "m4", messageCount = 4, itemCount = 5)
        val current = snapshot(id = "m4", messageCount = 4, itemCount = 4)
        assertFalse(
            "「正在写」行消失时，距末项一行（视口只到前 4 项）的用户不得被拽到底部",
            follow(previous, current, firstVisible = 0, visible = 4, total = 5, followAuxiliaryRow = true),
        )
    }

    // ---------------------------------------------------------------- isAtBottom 边界

    @Test
    fun `isAtBottom 边界`() {
        assertFalse("total=0 不算贴底", ChatAutoScroll.isAtBottom(0, 0, 0))
        assertTrue("first + visible == total 贴底", ChatAutoScroll.isAtBottom(0, 3, 3))
        assertFalse("少一条且无 slack 不算贴底", ChatAutoScroll.isAtBottom(0, 2, 3))
        assertTrue("少一条但 slack=1 算贴底（追加消息同一帧）", ChatAutoScroll.isAtBottom(0, 2, 3, trailingSlack = 1))
        assertTrue("尚未布局（firstVisible=-1）按贴底处理", ChatAutoScroll.isAtBottom(-1, 0, 5))
    }

    @Test
    fun `追加新消息的同一帧 单聊 slack 也生效`() {
        // 单聊：TA 追加一条，layoutInfo 仍是旧总数 → 末条不可见但 slack 允许，判定贴底。
        val previous = snapshot(id = "m2", messageCount = 2, itemCount = 2)
        val current = snapshot(id = "m3", messageCount = 3, itemCount = 3)
        assertTrue(follow(previous, current, firstVisible = 1, visible = 1, total = 2))
    }
}

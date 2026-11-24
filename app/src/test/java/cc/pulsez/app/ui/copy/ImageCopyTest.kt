package cc.pulsez.app.ui.copy

import cc.pulsez.app.net.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识图文案回归（纯 JVM，不碰 Android / Compose / Room）。
 *
 *
 * -「识图（首次带图发送前，AlertDialog）」——标题 / 三段正文 /
 * 按钮**逐字**；第 4 行隐私提示是对 的补充；
 * -（禁用词表，含「模型」）、 三问；（只用 TA）；
 * - ///////；
 * - （上限口径）； //。
 *
 * 只测常量：Compose 页面里的字面量没法在纯 JVM 里反射收集（同 `ReadCopyTest` 做法）。
 */
class ImageCopyTest {

    companion object {
        /**
 * 禁用词。
 *
 * ：「模型」也是禁用词（仅配置页例外），聊天页/
 * 识图文案一律说人话「AI」；「deepseek-flash」这类模型 id 也不出现在聊天页文案里。
 * 此前本测试为 `VISION_MODEL_DISABLED` 开了唯一例外，与 冲突，现已删除。
 */
        private val TECH_BANNED: List<String> = listOf(
            "模型", "deepseek-flash",
            "token", "上下文", "窗口", "向量", "embedding", "Embedding",
            "记忆检索", "关键词", "缓存", "TTS", "语音合成", "合成引擎",
        )

        /** 角色代词只允许 TA。 */
        private val PRONOUN_BANNED: List<String> = listOf("她", "他")

        /** 绝对承诺 / 制造不存在功能 / 客服腔。 */
        private val PROMISE_BANNED: List<String> = listOf(
            "免费",
            "不额外收费",
            "永久",
            "绝对",
            "本版未提供",
            "我们",
            "全新升级",
        )

        /** 本产出的全部用户可见文案（名称 → 文案），供批量断言。 */
        private fun allCopy(): List<Pair<String, String>> = listOf(
            "首次说明标题" to ImageCopy.DIALOG_TITLE,
            "首次说明第 1 句" to ImageCopy.INTRO_LINE_1,
            "首次说明第 2 句" to ImageCopy.INTRO_LINE_2,
            "首次说明第 3 句" to ImageCopy.INTRO_LINE_3,
            "首次说明第 4 句（隐私补充）" to ImageCopy.INTRO_LINE_4,
            "按钮·知道了" to ImageCopy.CONFIRM,
            "按钮·以后再说" to ImageCopy.DISMISS,
            "按钮·关闭" to ImageCopy.CLOSE,
            "入口读屏" to ImageCopy.ADD,
            "入口读屏（禁用）" to ImageCopy.ADD_DISABLED,
            "面板标题" to ImageCopy.SOURCE_TITLE,
            "拍照" to ImageCopy.SOURCE_CAMERA,
            "相册" to ImageCopy.SOURCE_GALLERY,
            "面板说明" to ImageCopy.SOURCE_DESCRIPTION,
            "模型不支持看图" to ImageCopy.VISION_MODEL_DISABLED,
            "无相机提示" to ImageCopy.NO_CAMERA,
            "非 DeepSeek 说明" to ImageCopy.OTHER_PROVIDER,
            "纯图占位" to ImageCopy.IMAGE_PLACEHOLDER,
            "图片读屏" to ImageCopy.IMAGE_CONTENT_DESCRIPTION,
            "图片已删除" to ImageCopy.IMAGE_MISSING,
            "预览关闭" to ImageCopy.PREVIEW_CLOSE,
            "预览重发" to ImageCopy.PREVIEW_RESEND,
            "草稿处理中" to ImageCopy.DRAFT_PREPARING,
            "草稿失败" to ImageCopy.DRAFT_FAILED,
            "草稿删除读屏" to ImageCopy.DRAFT_REMOVE,
            "动图第一帧" to ImageCopy.GIF_FIRST_FRAME,
            "上限 3 张" to ImageCopy.LIMIT_REACHED,
            "准备中禁止发送" to ImageCopy.SEND_BLOCKED_PREPARING,
            "单图太大" to ImageCopy.SINGLE_TOO_LARGE,
            "合计太大" to ImageCopy.TOTAL_TOO_LARGE,
            "内容被拦" to ImageCopy.CONTENT_FILTER,
            "搜索冲突" to ImageCopy.SEARCH_CONFLICT,
            "导入失败" to ImageCopy.IMPORT_FAILED,
            "OOM" to ImageCopy.IMPORT_OOM,
            "输入太大" to ImageCopy.IMPORT_TOO_LARGE,
            "回看标题" to ImageCopy.INTRO_ENTRY_TITLE,
            "回看值" to ImageCopy.INTRO_ENTRY_VALUE,
        )
    }

    // ------------------------------------------------------------ 逐字定稿

    @Test
    fun introDialogCopyIsVerbatimPerPlan() {
        assertEquals("发图片", ImageCopy.DIALOG_TITLE)
        assertEquals(
            "TA 这次能看图了。图片会发给你填的 AI 服务商。",
            ImageCopy.INTRO_LINE_1,
        )
        assertEquals(
            "每张图按大约 1000 字算，每次对话都会重新算钱；只带当前和上一轮的图。",
            ImageCopy.INTRO_LINE_2,
        )
        assertEquals("不想看图，就不加图；不影响打字聊天。", ImageCopy.INTRO_LINE_3)
        // 第 4 行是对图片功能的隐私补充：
        // 同步 docs），逐字锁定。
        assertEquals(
            "图里有别人的名字、电话或聊天记录，先遮掉再发；身份证、银行卡别发。",
            ImageCopy.INTRO_LINE_4,
        )
        assertEquals("知道了", ImageCopy.CONFIRM)
        assertEquals("DISMISS 逐字", "以后再说", ImageCopy.DISMISS)
        assertEquals("知道了", ImageCopy.CLOSE)
    }

    @Test
    fun introHasExactlyFourLinesInOrder() {
        assertEquals(
            "说明正文为三段内容，另有一句隐私补充",
            4,
            ImageCopy.INTRO_LINES.size,
        )
        assertEquals(
            listOf(
                ImageCopy.INTRO_LINE_1,
                ImageCopy.INTRO_LINE_2,
                ImageCopy.INTRO_LINE_3,
                ImageCopy.INTRO_LINE_4,
            ),
            ImageCopy.INTRO_LINES,
        )
        ImageCopy.INTRO_LINES.forEach { line ->
            assertTrue("说明行不能为空", line.isNotBlank())
        }
    }

    @Test
    fun privacyLineTellsWhatToHideAndWhatNotToSend() {
        // ：别人的名字/电话/聊天记录先遮掉；身份证、银行卡别发。
        listOf("名字", "电话", "聊天记录", "遮掉", "身份证", "银行卡").forEach { word ->
            assertTrue("隐私提示缺少「$word」：${ImageCopy.INTRO_LINE_4}", ImageCopy.INTRO_LINE_4.contains(word))
        }
    }

    @Test
    fun introAnswersWhatWhoPaysAndWhatIfNotUsed() {
        // 三问 / 谁付钱 / 不用会怎样。
        assertTrue("说清能看图", ImageCopy.INTRO_LINE_1.contains("能看图"))
        assertTrue("说清发给谁", ImageCopy.INTRO_LINE_1.contains("你填的 AI 服务商"))
        assertTrue("说清按字算钱", ImageCopy.INTRO_LINE_2.contains("按大约 1000 字算"))
        assertTrue("说清每轮重算", ImageCopy.INTRO_LINE_2.contains("每次对话都会重新算钱"))
        assertTrue("说清只带当前和上一轮", ImageCopy.INTRO_LINE_2.contains("只带当前和上一轮的图"))
        assertTrue("说清不用会怎样", ImageCopy.INTRO_LINE_3.contains("不影响打字聊天"))
    }

    // ------------------------------------------------------------ 文案纪律

    @Test
    fun copyAvoidsBannedWordsAndPromises() {
        allCopy().forEach { (name, text) ->
            TECH_BANNED.forEach { word ->
                assertFalse("「$name」出现技术词「$word」：$text", text.contains(word))
            }
            PRONOUN_BANNED.forEach { pronoun ->
                assertFalse("「$name」出现角色代词「$pronoun」（只许 TA）：$text", text.contains(pronoun))
            }
            PROMISE_BANNED.forEach { phrase ->
                assertFalse("「$name」出现不该有的说法「$phrase」：$text", text.contains(phrase))
            }
        }
    }

    @Test
    fun modelDisabledCopySaysWhatAndWhere() {
        // ：聊天页用户可见文案不出现「模型」「deepseek-flash」，
        // 改成说人话：说清现状（现在这个 AI 看不了图）+ 出路（去设置里换一个能看图的 AI）。
        assertTrue(ImageCopy.VISION_MODEL_DISABLED.contains("看不了图"))
        assertTrue(ImageCopy.VISION_MODEL_DISABLED.contains("去「设置 → 聊天」"))
        assertTrue(ImageCopy.VISION_MODEL_DISABLED.contains("能看图的 AI"))
        assertFalse(ImageCopy.VISION_MODEL_DISABLED.contains("模型"))
        assertFalse(ImageCopy.VISION_MODEL_DISABLED.contains("deepseek-flash"))
        // 置灰入口的读屏描述同样不出现禁用词。
        assertTrue(ImageCopy.ADD_DISABLED.contains("看不了图"))
        assertFalse(ImageCopy.ADD_DISABLED.contains("模型"))
    }

    @Test
    fun noCameraCopyOffersAlternatives() {
        // ：无相机 App 时的轻提示说清事实 + 出路（相册 / 打字）。
        assertTrue(ImageCopy.NO_CAMERA.contains("没有可用的相机"))
        assertTrue(ImageCopy.NO_CAMERA.contains("相册"))
        assertTrue(ImageCopy.NO_CAMERA.contains("打字"))
    }

    @Test
    fun contentFilterCopyIsPlainAndOffersAlternatives() {
        // / ：人话 + 替代（换个方式说 / 换一张），不冒称危机干预。
        assertTrue(ImageCopy.CONTENT_FILTER.contains("这张图服务商不收"))
        assertTrue(ImageCopy.CONTENT_FILTER.contains("换个方式说说"))
        assertTrue(ImageCopy.CONTENT_FILTER.contains("换一张"))
        assertFalse("不冒称危机干预", ImageCopy.CONTENT_FILTER.contains("12356"))
        assertFalse("不指责用户", ImageCopy.CONTENT_FILTER.contains("违规"))
    }

    @Test
    fun limitCopyStatesTheActualNumbers() {
        // ：可触发口径（2MB / 8MB）是内部阈值，用户文案只说"太大、换一张"，不出现 MB。
        assertFalse(ImageCopy.SINGLE_TOO_LARGE.contains("MB"))
        assertFalse(ImageCopy.SINGLE_TOO_LARGE.contains("2"))
        assertFalse(ImageCopy.TOTAL_TOO_LARGE.contains("MB"))
        assertTrue(ImageCopy.SEND_BLOCKED_PREPARING.contains("等一下"))
        assertTrue(ImageCopy.LIMIT_REACHED.contains("3 张"))
    }

    // ------------------------------------------------------------ 失败映射

    @Test
    fun sendFailureMapsFilteredAndSearchConflict() {
        val filtered = ApiError.Filtered(httpCode = 200)
        assertEquals(
            "图片被拦 → 图片专属人话",
            ImageCopy.CONTENT_FILTER,
            ImageCopy.sendFailureOf(filtered, hasImages = true, webSearchOn = false),
        )
        val badRequest = ApiError.BadResponse(httpCode = 400, raw = "image not supported")
        assertEquals(
            "带图 + 联网搜索 400 → 提示可以先关搜索",
            ImageCopy.SEARCH_CONFLICT,
            ImageCopy.sendFailureOf(badRequest, hasImages = true, webSearchOn = true),
        )
        assertNull(
            "没开搜索时不乱提示",
            ImageCopy.sendFailureOf(badRequest, hasImages = true, webSearchOn = false),
        )
        assertNull(
            "不带图时交给 ErrorCopy 兜底",
            ImageCopy.sendFailureOf(filtered, hasImages = false, webSearchOn = false),
        )
        assertNull(
            "普通网络错误交给 ErrorCopy",
            ImageCopy.sendFailureOf(ApiError.Network(), hasImages = true, webSearchOn = false),
        )
    }

    @Test
    fun importFailureMapsErrorCodes() {
        assertEquals(ImageCopy.IMPORT_OOM, ImageCopy.importFailure(cc.pulsez.app.media.ImageErrorCodes.OOM))
        assertEquals(
            ImageCopy.IMPORT_TOO_LARGE,
            ImageCopy.importFailure(cc.pulsez.app.media.ImageErrorCodes.TOO_LARGE),
        )
        assertEquals(
            ImageCopy.IMPORT_FAILED,
            ImageCopy.importFailure(cc.pulsez.app.media.ImageErrorCodes.BAD_IMAGE),
        )
        assertEquals(ImageCopy.IMPORT_FAILED, ImageCopy.importFailure(null))
    }

    @Test
    fun placeholderMatchesEngineContract() {
        assertEquals(cc.pulsez.app.core.ChatEngine.IMAGE_PLACEHOLDER, ImageCopy.IMAGE_PLACEHOLDER)
    }
}

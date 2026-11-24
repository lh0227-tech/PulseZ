package cc.pulsez.app.ui.chat

import cc.pulsez.app.media.DraftImage
import cc.pulsez.app.media.DraftRules

/**
 * 输入条右下角主按钮的状态机（输入条还原）。
 *
 * 官方 App（Android 2.9.0）把「发送 / 停止」放在同一个圆钮位置：
 * 有字 = 实心深色圆 + 向上箭头；生成中 = 实心深色圆 + 停止方块。空输入时官方那个位置
 * 是「语音 / 键盘切换」按钮；PulseZ 的语音输入还没做（等 v0.5.1 真正做了再接），按用户拍板
 * 「不做假按钮」，空输入保持**禁用**的向上箭头（研究 `
 * §5.3 方案 A / §5.4：诚实、没有死按钮、TalkBack 语义一致）。
 *
 * 抽成纯 Kotlin 的原因：状态分支（空 / 有字 / 有可发图 / 图片准备中 / 生成中）要能用
 * JVM 单测锁死，而且必须与发送链路共用同一份判定（复用 [DraftRules.sendBlock]），
 * 避免漂移成「按钮能点、发送却被拒」（v0.5.0 `）。
 *
 * 依据：`（官方同一位置四态）、§5.3（PulseZ 落地版）。
 */
enum class ComposerPrimaryIcon {
    /** 向上箭头（发送）。不用字符 `↑`，由 Canvas 自绘，避免字体缺字形/粗细不可控。 */
    ARROW_UP,

    /** 停止方块。 */
    STOP,
}

/** 点下去做什么。 */
enum class ComposerPrimaryAction {
    SEND,
    STOP,
}

/**
 * 主按钮要画成什么样、能不能点、点了做什么。
 *
 * @param enabled false 时 Compose 会带 disabled 语义，TalkBack 会念「已停用」。
 * @param contentDescription 读屏名称（说人话：发送 / 停下）。
 */
data class ComposerPrimaryState(
    val icon: ComposerPrimaryIcon,
    val action: ComposerPrimaryAction,
    val enabled: Boolean,
    val contentDescription: String,
)

/**
 * 主按钮读屏文案。
 *
 * 「停下」不是随口定的：` 规定「停止生成按钮就叫「停下」，
 * 不叫"停止生成"」，群聊输入条也用的「停下」。
 */
internal object ComposerPrimaryCopy {
    const val SEND = "发送"
    const val STOP = "停下"
}

internal object ComposerPrimary {

    /**
     * 算主按钮状态。
     *
     * 判定顺序：
     * 1. 生成中 → 停止方块，永远可点（对应现有「流式中发送键变停下」的行为）；
     * 2. 其余看 [DraftRules.sendBlock]：空输入、或图片还在处理中 → 禁用箭头；
     *    有字或有可发图片 → 实心箭头可点。
     *
     * @param drafts 输入条上方的草稿图（准备中会阻塞发送；失败态不参与发送）
     * @param hasText 输入框是否有非空文字
     * @param streaming 是否正在生成
     */
    fun resolve(
        drafts: List<DraftImage>,
        hasText: Boolean,
        streaming: Boolean,
    ): ComposerPrimaryState {
        if (streaming) {
            return ComposerPrimaryState(
                icon = ComposerPrimaryIcon.STOP,
                action = ComposerPrimaryAction.STOP,
                enabled = true,
                contentDescription = ComposerPrimaryCopy.STOP,
            )
        }
        val blocked = DraftRules.sendBlock(drafts, hasText, streaming = false) != null
        return ComposerPrimaryState(
            icon = ComposerPrimaryIcon.ARROW_UP,
            action = ComposerPrimaryAction.SEND,
            enabled = !blocked,
            contentDescription = ComposerPrimaryCopy.SEND,
        )
    }
}

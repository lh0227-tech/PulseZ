package cc.pulsez.app.ui.config

/**
 * 「本地 Ollama」为什么在手机上没有示例（v0.5.1 送检前修正）。
 *
 * 结论：示例列表里不再提供本地 Ollama，也不再给「电脑放开监听 + 手机填局域网 IP」的操作指引。
 *
 * 事实：
 * - Android 9（API 28）起系统默认禁止明文 http。官方文档原文「Starting with Android 9
 *   (API level 28), cleartext support is disabled by default.」（2026-11-21 实访：
 *   https://developer.android.com/privacy-and-security/security-config#CleartextTraffic）；
 * - 本应用 `res/xml/network_security_config.xml` 也只放行 `localhost` / `127.0.0.1` /
 *   `10.0.2.2`，没有「允许局域网明文」的开关（`Settings.allowCleartext` 死字段已删除）。
 *
 * 所以手机填 `http://192.168.x.x:11434/v1` 会被系统拦下（CLEARTEXT_NOT_PERMITTED），旧说明
 * 「两步就能连上」不可执行（` 高风险
 * 第七条）。不用本地 Ollama 的用户完全不受影响；自建了 https 反向代理的用户仍可手填自己的地址。
 * 依据「不内置本地 Ollama 示例」。
 */
internal object OllamaCopy {

    /**
     * 聊天页示例 chip 区下方的一句如实说明（替代原来的两段 Ollama 操作说明）。
     *
     * 说清三件事，避免用户以为「示例里没有 = 这个应用不支持」：① 手机暂不内置这个示例；
     * ② 原因是系统默认不允许明文 http、手机填电脑的局域网地址连不上；③ 有 https 反向代理时
     * 仍可以手动填地址。不写 `OLLAMA_HOST` 这类让用户改电脑环境变量的指引（按旧说明改完也连不上，
     * 不要再教一遍）。逐字锁定：`ConfigEmbeddingTest`。
     */
    const val NOT_BUILT_IN =
        "手机暂不内置本地 Ollama 示例：系统默认不允许明文 http，填电脑的局域网地址会连不上。" +
            "如果你在电脑上给 Ollama 配了 https 反向代理，可以手动填地址。"
}

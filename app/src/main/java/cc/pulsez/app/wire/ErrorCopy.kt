package cc.pulsez.app.wire

import cc.pulsez.app.net.ApiError

/**
 * 网络错误 → 用户文案。唯一来源是 。
 * 网络层只给分类，文案在这里落地，避免散落各处。
 */
object ErrorCopy {

    /**
     * 内容超出模型能收的长度（`CONTEXT_OVERFLOW`）。
     *
     * v0.4.1 文案修复（` 三·#5）：原句「这次说得有点长。我整理一下记忆再试。」
     * 承诺了全仓并不存在的「自动整理重试」，且用 App 第一人称「我」。改为说事实 + 两条用户能做的出路。
     */
    internal const val CONTEXT_OVERFLOW_TEXT: String =
        "这次带的内容太多，没装下。把上一句说短一点，或者先删几条很早的消息再试？"

    fun of(error: ApiError): String = when (error.kind) {
        ApiError.Kind.NO_KEY ->
            "还没填密钥。去设置里填一下？"
        ApiError.Kind.AUTH ->
            "密钥好像不对。复制的时候是不是少了一截？"
        ApiError.Kind.QUOTA ->
            "这个密钥的额度用完了，或者被限流了。去服务商那边看看余额，或者等一会儿再试。"
        ApiError.Kind.MODEL_NOT_FOUND ->
            "这个地址里没找到能用的 AI。把服务商文档里那整行地址复制过来（一般末尾带 /v1），也核对一下选的 AI 名称。"
        ApiError.Kind.CONTEXT_OVERFLOW ->
            CONTEXT_OVERFLOW_TEXT
        ApiError.Kind.FILTERED ->
            "这条被挡住了。换个说法，或者换一家 AI 试试。"
        ApiError.Kind.SERVER ->
            "对面好像有点忙。等会儿再试一次？"
        ApiError.Kind.NETWORK ->
            "现在连不上服务。检查一下手机网络，或者核对填写的地址。"
        ApiError.Kind.STREAM_BROKEN ->
            "回答到一半断了。已经写出来的还在这里。"
        ApiError.Kind.BAD_RESPONSE ->
            "对面回了个看不懂的东西。再试一次？"
    }
}

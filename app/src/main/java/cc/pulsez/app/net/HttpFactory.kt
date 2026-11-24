package cc.pulsez.app.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局共享的 OkHttpClient 工厂。
 *
 * 关键点：
 * - `callTimeout = 0`：长回答不能被 60s 腰斩；
 * - **不能** readTimeout = 0 就了事——代理静默断连会半开，所以读取超时交给
 *   [LlmClient] 的首字 / 心跳双计时器；这里 readTimeout 置 0 只是不叠加第二套超时；
 * - 不装日志拦截器：请求和响应正文都含聊天内容，打印即泄密；
 * - 除 OkHttp 默认的透明 gzip 外不启用其他压缩/编码花样；
 * - **不跟随重定向（安全约束）**：这个客户端只承载用户内容——聊天正文与随请求上传的图片、
 *   找旧事的检索片段、语音录音。协议承诺这些内容只发给用户自己填写的服务商
 *   （`app/src/main/res/raw/agreement.html`:31–33；`147
 *   「只有你自己填的服务商（聊天服务、向量服务）会收到内容」）。
 *   OkHttp 默认会自动跟随 3xx：307/308 保留原 method 与请求体，跨 origin 时只摘掉
 *   `Authorization`，正文仍会发到用户没有配置过的地址（第三方 v0.5.4 报告
 *   `81–85）。关掉后 3xx 原样返回，由各调用方
 *   既有的 `isSuccessful` 检查映射成可见失败——重定向变成一次用户看得见的失败，
 *   不再静默跨域转发。依据 修复决定。
 *
 *   **更新检查不走这个客户端**：`update/UpdateConfig.newClient()` 自建轻量客户端，
 *   保留默认跟随重定向（version.json 允许 CDN 重定向）。
 */
object HttpFactory {

    const val CONNECT_TIMEOUT_SECONDS: Long = 15L
    const val WRITE_TIMEOUT_SECONDS: Long = 30L
    const val READ_TIMEOUT_MILLIS: Long = 0L
    const val CALL_TIMEOUT_MILLIS: Long = 0L

    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        // 用户内容只发往用户填写的服务商：3xx 不跟随，改成可见失败（见类注释与）。
        // followSslRedirects 一并关掉：防止 https→http 降级这类只换 scheme 的重定向被跟随。
        .followRedirects(false)
        .followSslRedirects(false)
        // 不添加 addInterceptor / addNetworkInterceptor（隐私：不打印、不落盘请求正文）。
        .build()
}

package cc.pulsez.app.update

/**
 * 测试用的合法清单构造器（纯 JVM；不发请求、不碰 Android）。
 *
 * 只服务于 `update/` 下的单测，避免每个用例手拼一长串 JSON。
 */
internal object TestManifests {

    /** 合法 sha256（64 位小写 hex）。 */
    const val SHA256: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    /**
 * 拼一份最小合法 `version.json` 文本；参数原样拼进 JSON（调用方自己带引号），
 * 便于构造「类型错 / 缺字段 / 非法值」等用例。
 */
    fun json(
        schema: String = "1",
        channel: String = "\"stable\"",
        versionCode: String = "8",
        versionName: String = "\"0.5.1\"",
        apkUrl: String = "\"https://releases.pulsez.cc/stable/pulsez-0.5.1.apk\"",
        sha256: String = "\"$SHA256\"",
        sizeBytes: String = "3481024",
        /** 追加字段（记得以逗号开头），例如 `,"mandatory":true`。 */
        extra: String = "",
    ): String = buildString {
        append('{')
        append("\"schema\":").append(schema).append(',')
        append("\"channel\":").append(channel).append(',')
        append("\"versionCode\":").append(versionCode).append(',')
        append("\"versionName\":").append(versionName).append(',')
        append("\"apkUrl\":").append(apkUrl).append(',')
        append("\"sha256\":").append(sha256).append(',')
        append("\"sizeBytes\":").append(sizeBytes)
        append(extra)
        append('}')
    }

    /** 删掉某个必填字段的一份 JSON（测「缺字段 → 拒绝」）。 */
    fun jsonWithout(field: String): String {
        val fields = linkedMapOf(
            "schema" to "\"schema\":1",
            "channel" to "\"channel\":\"stable\"",
            "versionCode" to "\"versionCode\":8",
            "versionName" to "\"versionName\":\"0.5.1\"",
            "apkUrl" to "\"apkUrl\":\"https://releases.pulsez.cc/stable/pulsez-0.5.1.apk\"",
            "sha256" to "\"sha256\":\"$SHA256\"",
            "sizeBytes" to "\"sizeBytes\":3481024",
        )
        fields.remove(field)
        return "{" + fields.values.joinToString(",") + "}"
    }

    /** 直接构造一个已校验过的清单对象（给 checker/repository 测试当「服务端返回」）。 */
    fun manifest(
        versionCode: Long = 8L,
        versionName: String = "0.5.1",
        changelog: List<String> = emptyList(),
        mandatory: Boolean = false,
    ): VersionManifest = VersionManifest(
        schema = 1,
        channel = "stable",
        versionCode = versionCode,
        versionName = versionName,
        minSdk = 31,
        apkUrl = "https://releases.pulsez.cc/stable/pulsez-$versionName.apk",
        apkMirrors = emptyList(),
        sha256 = SHA256,
        sizeBytes = 3_481_024L,
        apkCertSha256 = null,
        publishedAt = null,
        mandatory = mandatory,
        minSupportedVersionCode = 0L,
        changelog = changelog,
        notes = null,
    )
}

package cc.pulsez.app.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 发布方声明的版本清单（`version.json`，schema 1）。
 *
 * 字段与校验规则逐条对齐
 * - `schema` 必须 == 1；`channel` 只接受 `stable`；
 * - `versionCode` 是**唯一比较依据**（`Long`，与 `PackageInfo.longVersionCode` 对齐，
 *   API 28+ 已是 `Long` 类型约定）；
 * - `versionName` 只用于显示，格式 `^[0-9]+(\.[0-9]+){1,2}(-[a-z0-9]+)?$`；
 * - `minSdk` 必须 ≤ 本项目 minSdk（31），否则这份清单对该设备不可用；
 * - `apkUrl`/`apkMirrors` 必须 https + 域名白名单（**只校验清单里声明的 URL**，
 *   GitHub 资产 302 后的最终地址不参与校验，否则会误杀）；
 * - `sha256`/`apkCertSha256` 64 位小写 hex；`sizeBytes` > 0 且 < 20 MB；
 * - `mandatory` 只记录、**不做阻断**（不做强制更新）；
 * - `changelog` 兼容 的字符串数组，也兼容口径的单条字符串（两种都收）。
 *
 * 任何一条不满足 → [parse] 返回 null，调用方把这一源当作「失败」并降级/静默
 * （解析失败/字段非法 ≠ App 异常）。
 *
 * 只做「检查 + 提示 + 跳浏览器」：清单里的 `sha256`/`sizeBytes`/`apkCertSha256`
 * 本轮**不用于下载校验**（下载/安装在浏览器侧完成），但字段要能解析、类型要对，
 * 为将来 的校验流水线留好协议。
 */
data class VersionManifest(
    val schema: Int,
    val channel: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkUrl: String,
    val apkMirrors: List<String>,
    val sha256: String,
    val sizeBytes: Long,
    val apkCertSha256: String?,
    val publishedAt: String?,
    val mandatory: Boolean,
    val minSupportedVersionCode: Long,
    val changelog: List<String>,
    val notes: String?,
) {

    /**
     * 这份清单是不是比当前安装的版本新。
     *
     * 只认「大于」：等于 = 已是最新；小于 = 发布侧回滚指针，客户端**不降级**。
     */
    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode > installedVersionCode

    companion object {

        /** `versionName` 允许的格式（逐字）。*/
        private val VERSION_NAME_PATTERN = Regex("^[0-9]+(\\.[0-9]+){1,2}(-[a-z0-9]+)?$")

        /** 64 位小写 hex（sha256 / 证书指纹）。 */
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

        private val json: Json = Json {
            ignoreUnknownKeys = true
            // 不做类型宽松：字段类型错就是「这份清单坏了」，换下一源。
            explicitNulls = false
        }

        /**
         * 解析并校验一份清单文本；任何问题都返回 null（不抛异常、不写日志）。
         *
         * 空串/空白/非 JSON/JSON 不是对象/缺必填字段/类型错/字段值非法 → null。
         *
         * 为什么先 `parseToJsonElement` 再做 [JsonObject.hasExpectedTypes]：kotlinx.serialization
         * 的 JSON 解码对「字符串里写数字」（如 `"versionCode":"8"`）比预期宽，会把引号里的数字
         * 当数字收下；协议要求**字段类型必须对**，所以这里显式检查 JSON 原始
         * 类型，不依赖解码器行为。
         */
        fun parse(text: String?): VersionManifest? {
            if (text.isNullOrBlank()) return null
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
            val obj = element as? JsonObject ?: return null
            if (!obj.hasExpectedTypes()) return null
            val dto = runCatching {
                json.decodeFromJsonElement(VersionManifestDto.serializer(), obj)
            }.getOrNull() ?: return null
            return dto.validated()
        }

        /**
         * 清单原始 JSON 的类型检查（必填字段必须在、类型必须与 一致）。
         *
         * 只检查会影响安全/比较的字段；`changelog` 有意宽松（字符串或字符串数组都收，
         * 见 [changelogLines]），`notes`/`publishedAt` 只做展示，也允许缺省。
         */
        private fun JsonObject.hasExpectedTypes(): Boolean {
            fun valueOf(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

            fun isNumber(key: String, required: Boolean): Boolean {
                val value = valueOf(key) ?: return !required
                return value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null
            }

            fun isString(key: String, required: Boolean): Boolean {
                val value = valueOf(key) ?: return !required
                return value is JsonPrimitive && value.isString
            }

            fun isBoolean(key: String, required: Boolean): Boolean {
                val value = valueOf(key) ?: return !required
                if (value !is JsonPrimitive || value.isString) return false
                return value.content == "true" || value.content == "false"
            }

            fun isStringArray(key: String): Boolean {
                val value = valueOf(key) ?: return true
                return value is JsonArray && value.all { it is JsonPrimitive && it.isString }
            }

            return isNumber("schema", required = true) &&
                isString("channel", required = true) &&
                isNumber("versionCode", required = true) &&
                isString("versionName", required = true) &&
                isNumber("minSdk", required = false) &&
                isString("apkUrl", required = true) &&
                isStringArray("apkMirrors") &&
                isString("sha256", required = true) &&
                isNumber("sizeBytes", required = true) &&
                isString("apkCertSha256", required = false) &&
                isString("publishedAt", required = false) &&
                isBoolean("mandatory", required = false) &&
                isNumber("minSupportedVersionCode", required = false) &&
                isString("notes", required = false)
        }

        private fun VersionManifestDto.validated(): VersionManifest? {
            if (schema != UpdateConfig.MANIFEST_SCHEMA) return null
            if (channel != UpdateConfig.CHANNEL_STABLE) return null
            if (versionCode <= 0L) return null
            if (!VERSION_NAME_PATTERN.matches(versionName)) return null

            // minSdk 在 是必填，但列「至少字段」时没列它；缺省按本项目 minSdk 处理：
            // 发布方不写 = 不做额外限制（保守，不会误杀清单）。
            val declaresMinSdk = minSdk
            if (declaresMinSdk != null && declaresMinSdk > UpdateConfig.PROJECT_MIN_SDK) return null

            if (!isAllowedDownloadUrl(apkUrl)) return null
            val mirrors = apkMirrors.orEmpty()
            if (mirrors.size > UpdateConfig.MAX_APK_MIRRORS) return null
            if (mirrors.any { !isAllowedDownloadUrl(it) }) return null

            if (!SHA256_PATTERN.matches(sha256)) return null
            if (sizeBytes <= 0L || sizeBytes > UpdateConfig.MAX_APK_BYTES) return null
            if (apkCertSha256 != null && !SHA256_PATTERN.matches(apkCertSha256)) return null

            val minSupported = minSupportedVersionCode ?: 0L
            if (minSupported < 0L) return null

            return VersionManifest(
                schema = schema,
                channel = channel,
                versionCode = versionCode,
                versionName = versionName,
                minSdk = declaresMinSdk ?: UpdateConfig.PROJECT_MIN_SDK,
                apkUrl = apkUrl,
                apkMirrors = mirrors,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                apkCertSha256 = apkCertSha256,
                publishedAt = publishedAt,
                mandatory = mandatory ?: false,
                minSupportedVersionCode = minSupported,
                changelog = changelogLines(changelog),
                notes = notes,
            )
        }

        /**
         * 下载地址校验：https + 白名单 host + 不带 userinfo。
         *
         * 用 OkHttp 的 `HttpUrl` 解析（不是字符串 `startsWith`）：能挡住
         * `https://github.com.evil.com/...`、`https://github.com@evil.com/...` 这类伪装。
         * 依据 白名单口径。
         */
        private fun isAllowedDownloadUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            if (parsed.scheme != "https") return false
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
            return parsed.host in UpdateConfig.ALLOWED_DOWNLOAD_HOSTS
        }

        /**
         * `changelog` 归一化成纯文本行。
         *
         * 兼容两种写法（不因为发布脚本形状不同就整份清单失败）：
         * - 字符串数组（定稿形状）；
         * - 单条字符串（「changelog（字符串，可空）」）。
         * 其它形状/空串一律当「没有更新说明」，不影响清单其余字段生效。
         */
        private fun changelogLines(element: JsonElement?): List<String> = when (element) {
            null -> emptyList()
            is JsonArray -> element
                .mapNotNull { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.content }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            is JsonPrimitive -> listOfNotNull(element.takeIf { it.isString }?.content?.trim())
                .filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }
}

/**
 * `version.json` 的线上形状（反序列化用）。
 *
 * 必填字段刻意**不给默认值**：缺字段 → 反序列化失败 → 整份清单拒绝（必填列）。
 * 可选字段给 null 默认值，由 [VersionManifest.parse] 统一归一化（DataStore 无 null 类型的问题
 * 不存在，这里只是解析）。
 */
@Serializable
internal data class VersionManifestDto(
    val schema: Int,
    val channel: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSdk: Int? = null,
    val apkMirrors: List<String>? = null,
    val apkCertSha256: String? = null,
    val publishedAt: String? = null,
    val mandatory: Boolean? = null,
    val minSupportedVersionCode: Long? = null,
    val changelog: JsonElement? = null,
    val notes: String? = null,
)

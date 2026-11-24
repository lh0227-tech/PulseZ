package cc.pulsez.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `version.json` 解析与校验（-A）。
 *
 * （schema 字段与规则）、（解析失败/字段非法按「这一源失败」
 * 处理，不是 App 异常）、（真实链路未核实→解析必须对坏输入稳）。
 *
 * 全部纯 JVM、离线：不请求真实域名。
 */
class VersionManifestTest {

    /** 在最小合法 JSON 上追加字段（每个元素是一段 `"name":value` 的 JSON 文本）。 */
    private fun jsonWith(vararg extraFields: String): String =
        TestManifests.json(extra = extraFields.joinToString(separator = ",", prefix = ","))

    /** 一段 `"name":<原始 JSON 值>`。 */
    private fun field(name: String, rawJson: String): String = "\"$name\":$rawJson"

    // ---------------------------------------------------------------- 合法清单

    @Test
    fun `合法清单解析出全部必填字段`() {
        val manifest = VersionManifest.parse(TestManifests.json())
        assertNotNull("合法清单不能是 null", manifest)
        manifest!!
        assertEquals(1, manifest.schema)
        assertEquals("stable", manifest.channel)
        assertEquals(8L, manifest.versionCode)
        assertEquals("0.5.1", manifest.versionName)
        assertEquals(31, manifest.minSdk)
        assertEquals("https://releases.pulsez.cc/stable/pulsez-0.5.1.apk", manifest.apkUrl)
        assertEquals(TestManifests.SHA256, manifest.sha256)
        assertEquals(3_481_024L, manifest.sizeBytes)
        assertEquals(emptyList<String>(), manifest.apkMirrors)
        assertNull(manifest.apkCertSha256)
        assertNull(manifest.publishedAt)
        assertFalse(manifest.mandatory)
        assertEquals(0L, manifest.minSupportedVersionCode)
        assertEquals(emptyList<String>(), manifest.changelog)
        assertNull(manifest.notes)
    }

    @Test
    fun `可选字段都能解析`() {
        val mirror = "https://github.com/lh0227-tech/PulseZ/releases/download/v0.5.1/pulsez-0.5.1.apk"
        val cert = "a78badba912a8d4b955210cf1e772bf73ad4acf5d26becbc2e5fd422597268fe"
        val json = jsonWith(
            field("minSdk", "31"),
            field("apkMirrors", "[\"$mirror\"]"),
            field("apkCertSha256", "\"$cert\""),
            field("publishedAt", "\"2026-11-28T12:00:00Z\""),
            field("mandatory", "true"),
            field("minSupportedVersionCode", "3"),
            field("changelog", "[\"修了图片发不出去的问题\",\"设置里多了检查更新\"]"),
            field("notes", "\"发布备注\""),
        )
        val manifest = VersionManifest.parse(json)
        assertNotNull(manifest)
        manifest!!
        assertEquals(listOf(mirror), manifest.apkMirrors)
        assertEquals(cert, manifest.apkCertSha256)
        assertEquals("2026-11-28T12:00:00Z", manifest.publishedAt)
        assertTrue("mandatory 只解析记录，本轮不做阻断", manifest.mandatory)
        assertEquals(3L, manifest.minSupportedVersionCode)
        assertEquals(listOf("修了图片发不出去的问题", "设置里多了检查更新"), manifest.changelog)
        assertEquals("发布备注", manifest.notes)
    }

    @Test
    fun `未知字段被忽略而不是拒绝`() {
        val json = jsonWith(
            field("futureField", "{\"a\":1}"),
            field("other", "[1,2,3]"),
        )
        assertNotNull("未知字段不能导致整份清单失败（将来加字段不该打死旧客户端）", VersionManifest.parse(json))
    }

    @Test
    fun `changelog 兼容字符串数组与单条字符串`() {
        val array = VersionManifest.parse(
            jsonWith(field("changelog", "[\" 第一条 \",\"\",6,\"第二条\"]")),
        )
        assertEquals("数组里的空白/非字符串条目被过滤", listOf("第一条", "第二条"), array?.changelog)

        val single = VersionManifest.parse(jsonWith(field("changelog", "\"只改了一处\"")))
        assertEquals("单条字符串也接受", listOf("只改了一处"), single?.changelog)

        val empty = VersionManifest.parse(jsonWith(field("changelog", "[]")))
        assertEquals(emptyList<String>(), empty?.changelog)
    }

    // ---------------------------------------------------------------- 缺字段 / 类型错

    @Test
    fun `缺任一必填字段都拒绝`() {
        listOf("schema", "channel", "versionCode", "versionName", "apkUrl", "sha256", "sizeBytes")
            .forEach { missing ->
                assertNull(
                    "缺 $missing 必须拒绝整份清单",
                    VersionManifest.parse(TestManifests.jsonWithout(missing)),
                )
            }
    }

    @Test
    fun `字段类型错都拒绝`() {
        assertNull("versionCode 是字符串 → 拒绝", VersionManifest.parse(TestManifests.json(versionCode = "\"8\"")))
        assertNull("schema 是字符串 → 拒绝", VersionManifest.parse(TestManifests.json(schema = "\"1\"")))
        assertNull("sizeBytes 是字符串 → 拒绝", VersionManifest.parse(TestManifests.json(sizeBytes = "\"3481024\"")))
        assertNull("channel 是数字 → 拒绝", VersionManifest.parse(TestManifests.json(channel = "1")))
        assertNull(
            "mandatory 是字符串 → 拒绝",
            VersionManifest.parse(jsonWith(field("mandatory", "\"yes\""))),
        )
    }

    @Test
    fun `空文本 坏 JSON 非对象都拒绝且不崩`() {
        listOf("", "   ", "not json", "[]", "null", "{}", "{\"schema\":1}").forEach { text ->
            assertNull("坏输入必须返回 null：$text", VersionManifest.parse(text))
        }
        assertNull(VersionManifest.parse(null))
    }

    // ---------------------------------------------------------------- schema / channel

    @Test
    fun `schema 只接受 1`() {
        assertEquals(1, VersionManifest.parse(TestManifests.json(schema = "1"))?.schema)
        assertNull(
            "schema 2 是更高协议，旧客户端必须拒绝",
            VersionManifest.parse(TestManifests.json(schema = "2")),
        )
        assertNull("schema 0 不是合法协议", VersionManifest.parse(TestManifests.json(schema = "0")))
        assertNull("schema 负数不是合法协议", VersionManifest.parse(TestManifests.json(schema = "-1")))
    }

    @Test
    fun `channel 只接受 stable`() {
        assertNotNull(VersionManifest.parse(TestManifests.json(channel = "\"stable\"")))
        assertNull(
            "beta 渠道不提示，客户端只读 stable",
            VersionManifest.parse(TestManifests.json(channel = "\"beta\"")),
        )
        assertNull("大小写不同不是 stable", VersionManifest.parse(TestManifests.json(channel = "\"STABLE\"")))
    }

    // ---------------------------------------------------------------- versionCode / versionName

    @Test
    fun `versionCode 必须为正`() {
        assertEquals(8L, VersionManifest.parse(TestManifests.json(versionCode = "8"))?.versionCode)
        assertEquals(
            "Long 范围的大版本号也能解析",
            9_000_000_000L,
            VersionManifest.parse(TestManifests.json(versionCode = "9000000000"))?.versionCode,
        )
        assertNull("0 不是合法版本号", VersionManifest.parse(TestManifests.json(versionCode = "0")))
        assertNull("负数不是合法版本号", VersionManifest.parse(TestManifests.json(versionCode = "-1")))
    }

    @Test
    fun `versionName 格式校验`() {
        listOf("1.0", "0.5.1", "10.20.30", "1.2.3-beta").forEach { name ->
            assertNotNull(
                "合法 versionName 应接受：$name",
                VersionManifest.parse(TestManifests.json(versionName = "\"$name\"")),
            )
        }
        listOf("", "abc", "1", "1.2.3.4", "v1.2.3", "1.2.3-BETA", "1.2.3.4.5").forEach { name ->
            assertNull(
                "非法 versionName 应拒绝：$name",
                VersionManifest.parse(TestManifests.json(versionName = "\"$name\"")),
            )
        }
    }

    // ---------------------------------------------------------------- minSdk

    @Test
    fun `minSdk 大于本项目 minSdk 时拒绝 缺省按 31`() {
        assertEquals(
            31,
            VersionManifest.parse(jsonWith(field("minSdk", "31")))?.minSdk,
        )
        assertNull(
            "minSdk 32 的清单在本项目（minSdk 31）上不能装",
            VersionManifest.parse(jsonWith(field("minSdk", "32"))),
        )
        assertEquals(
            "缺省 minSdk 按本项目 31 处理（不放额外限制）",
            31,
            VersionManifest.parse(TestManifests.json())?.minSdk,
        )
    }

    // ---------------------------------------------------------------- apkUrl / apkMirrors 白名单

    @Test
    fun `apkUrl 必须 https 且在白名单域名`() {
        listOf(
            TestManifests.json(apkUrl = "\"http://releases.pulsez.cc/stable/pulsez-0.5.1.apk\""),
            TestManifests.json(apkUrl = "\"ftp://releases.pulsez.cc/stable/pulsez-0.5.1.apk\""),
            TestManifests.json(apkUrl = "\"not a url\""),
            TestManifests.json(apkUrl = "\"https://evil.example.com/pulsez.apk\""),
            TestManifests.json(apkUrl = "\"https://github.com.evil.example.com/pulsez.apk\""),
            TestManifests.json(apkUrl = "\"https://github.com@evil.example.com/pulsez.apk\""),
            TestManifests.json(apkUrl = "\"https://releases.pulsez.cc.evil.example.com/pulsez.apk\""),
        ).forEach { json ->
            assertNull("非法/白名单外 apkUrl 必须拒绝：$json", VersionManifest.parse(json))
        }

        assertNotNull(
            "github.com 是允许的备源域名",
            VersionManifest.parse(
                TestManifests.json(
                    apkUrl = "\"https://github.com/lh0227-tech/PulseZ/releases/download/v0.5.1/pulsez-0.5.1.apk\"",
                ),
            ),
        )
    }

    @Test
    fun `apkMirrors 最多 4 个且必须 https 白名单`() {
        val ok = "https://github.com/lh0227-tech/PulseZ/releases/download/v0.5.1/pulsez-0.5.1.apk"
        assertNotNull(
            "1 个合法镜像可以",
            VersionManifest.parse(jsonWith(field("apkMirrors", "[\"$ok\"]"))),
        )

        val five = (1..5).joinToString(",") { "\"$ok\"" }
        assertNull(
            "超过 4 个镜像拒绝",
            VersionManifest.parse(jsonWith(field("apkMirrors", "[$five]"))),
        )
        assertNull(
            "http 镜像拒绝",
            VersionManifest.parse(
                jsonWith(field("apkMirrors", "[\"http://releases.pulsez.cc/pulsez.apk\"]")),
            ),
        )
        assertNull(
            "白名单外镜像拒绝",
            VersionManifest.parse(
                jsonWith(field("apkMirrors", "[\"https://evil.example.com/pulsez.apk\"]")),
            ),
        )
    }

    // ---------------------------------------------------------------- sha256 / sizeBytes / 证书

    @Test
    fun `sha256 必须是 64 位小写 hex`() {
        val valid = TestManifests.SHA256
        assertNotNull(VersionManifest.parse(TestManifests.json(sha256 = "\"$valid\"")))
        assertNull("63 位拒绝", VersionManifest.parse(TestManifests.json(sha256 = "\"${valid.dropLast(1)}\"")))
        assertNull("65 位拒绝", VersionManifest.parse(TestManifests.json(sha256 = "\"${valid}a\"")))
        assertNull(
            "大写拒绝，hex 必须为小写",
            VersionManifest.parse(TestManifests.json(sha256 = "\"${valid.uppercase()}\"")),
        )
        assertNull("非 hex 字符拒绝", VersionManifest.parse(TestManifests.json(sha256 = "\"${"g".repeat(64)}\"")))
    }

    @Test
    fun `sizeBytes 必须大于 0 且小于 20MB`() {
        assertNotNull(VersionManifest.parse(TestManifests.json(sizeBytes = "1")))
        assertNotNull("20MB 边界（含）可以", VersionManifest.parse(TestManifests.json(sizeBytes = "20971520")))
        assertNull("0 拒绝", VersionManifest.parse(TestManifests.json(sizeBytes = "0")))
        assertNull("负数拒绝", VersionManifest.parse(TestManifests.json(sizeBytes = "-1")))
        assertNull("超过 20MB 拒绝", VersionManifest.parse(TestManifests.json(sizeBytes = "20971521")))
    }

    @Test
    fun `apkCertSha256 可选 出现时必须是 64 位小写 hex`() {
        assertNull(
            "格式错的证书指纹拒绝（将来 D1-B 要靠它防换签，不能带坏值进流水线）",
            VersionManifest.parse(jsonWith(field("apkCertSha256", "\"abc\""))),
        )
        val cert = "a78badba912a8d4b955210cf1e772bf73ad4acf5d26becbc2e5fd422597268fe"
        assertEquals(
            cert,
            VersionManifest.parse(jsonWith(field("apkCertSha256", "\"$cert\"")))?.apkCertSha256,
        )
    }

    // ---------------------------------------------------------------- 版本比较

    @Test
    fun `只有更大的 versionCode 才算有新版本`() {
        val manifest = TestManifests.manifest(versionCode = 8L)
        assertTrue("8 > 7", manifest.isNewerThan(7L))
        assertFalse("8 == 8 已是最新", manifest.isNewerThan(8L))
        assertFalse("8 < 9 不降级，回滚指针不升级", manifest.isNewerThan(9L))
    }
}

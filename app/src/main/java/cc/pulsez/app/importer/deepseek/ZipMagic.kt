package cc.pulsez.app.importer.deepseek

/**
 * ZIP 魔数嗅探（不依赖 Android，纯 Kotlin/JVM）。
 *
 * 依据：PKWARE APPNOTE 4.3.7（本地文件头 `PK\x03\x04`）与 4.3.12（空归档的
 * End of Central Directory `PK\x05\x06`）；落点见 `
 * 与 `「魔数」。
 *
 * 只认这两种签名：`PK\x01\x02`（中央目录）、`PK\x07\x08`（spanned 分卷）都不是
 * 一个可以正常打开的归档开头，交给后续 `ZipFile` 报错更诚实。
 */
object ZipMagic {

    /** 本地文件头签名 `50 4B 03 04`（有内容的标准 ZIP）。 */
    private const val LOCAL_HEADER = 0x50_4B_03_04

    /** 空归档 EOCD 签名 `50 4B 05 06`（没有条目的 ZIP）。 */
    private const val EMPTY_ARCHIVE_END = 0x50_4B_05_06

    /**
     * [head] 是文件开头若干字节（至少 4 字节才可能为 true）。
     * 只在开头嗅探，不做偏移搜索：调用方传 `ImportCache.snapshotHead(file)` 的结果即可。
     */
    fun isZip(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val signature = (byteAt(head, 0) shl 24) or
            (byteAt(head, 1) shl 16) or
            (byteAt(head, 2) shl 8) or
            byteAt(head, 3)
        return signature == LOCAL_HEADER || signature == EMPTY_ARCHIVE_END
    }

    private fun byteAt(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF
}

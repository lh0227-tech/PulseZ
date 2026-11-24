package cc.pulsez.app.mem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BigramIndex] 的中文二元组、英文分词、停用词与命中打分测试。
 *
 * 对应文档：02 chunk_term、03、02 DAO 关键词召回。
 */
class BigramIndexTest {

    @Test
    fun chineseBigramsPerDocExample() {
        // 02："杭州的雨" → 杭州 / 州的 / 的雨
        assertEquals(setOf("杭州", "州的", "的雨"), BigramIndex.terms("杭州的雨"))
    }

    @Test
    fun englishWordsLowercasedAndDeduped() {
        assertEquals(setOf("hello", "world", "abc123"), BigramIndex.terms("Hello, World! ABC123"))
        // 重复词只留一个
        assertEquals(setOf("hello"), BigramIndex.terms("Hello HELLO hello"))
    }

    @Test
    fun mixedTextProducesBothKinds() {
        val terms = BigramIndex.terms("在杭州 hello")
        assertTrue(terms.contains("杭州"))
        assertTrue(terms.contains("hello"))
    }

    @Test
    fun singleChineseCharKept() {
        assertEquals(setOf("好"), BigramIndex.terms("好"))
    }

    @Test
    fun stopWordsDropped() {
        val terms = BigramIndex.terms("我们是朋友")
        assertFalse("停用词不应出现", terms.contains("我们"))
        assertTrue(terms.contains("是朋"))
        assertTrue(terms.contains("朋友"))
    }

    @Test
    fun punctuationAndWhitespaceProduceNoTerms() {
        assertTrue(BigramIndex.terms("，。！？ … ---").isEmpty())
        assertTrue(BigramIndex.terms("").isEmpty())
    }

    @Test
    fun scoreCountsHits() {
        val query = setOf("杭州", "的雨")
        assertEquals(2, BigramIndex.score(query, setOf("杭州", "的雨", "雨天")))
        assertEquals(1, BigramIndex.score(setOf("杭州", "上海"), setOf("杭州", "的雨")))
        assertEquals(0, BigramIndex.score(query, setOf("上海", "出差")))
        assertEquals(0, BigramIndex.score(emptySet(), setOf("杭州")))
    }

    @Test
    fun scoreSupportsOptionalIdf() {
        val query = setOf("杭州", "的雨")
        val doc = setOf("杭州", "的雨")
        val idf = mapOf("杭州" to 2.0, "的雨" to 1.0)
        assertEquals(3, BigramIndex.score(query, doc, idf))
        // 缺省 IDF 权重按 1 计：2.0（杭州）+ 1.0（的雨）= 3
        assertEquals(3, BigramIndex.score(query, doc, mapOf("杭州" to 2.0)))
    }

    @Test
    fun deduplicatesRepeatedBigrams() {
        val terms = BigramIndex.terms("杭州杭州")
        assertTrue(terms.contains("杭州"))
        assertEquals(2, terms.size) // 杭州 / 州杭
    }
}

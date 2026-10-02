package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片下载的内部判据：Range 解析、分片切分、以及「同一实体」校验。
 *
 * 这些函数原先都是 private，导致分片下载最容易出错的三处逻辑一直没有测试：
 *  - `Content-Range` 解析错 → 分片偏移写错，合并出来的文件长度对、内容错乱；
 *  - `buildRanges` 切分有洞/重叠 → 同上；
 *  - 实体标识校验被跳过 → 镜像换文件后各分片来自不同 revision，静默产出坏 JAR。
 *
 * 每个用例都按「删掉判据就红」的标准写：断言的是判据本身，不是「流程跑通」。
 */
class MultiThreadDownloaderInternalsTest {

    // ── parseContentRange ──────────────────────────────────────

    @Test
    fun `解析标准 Content-Range`() {
        assertEquals(
            Triple(0L, 999L, 1000L),
            MultiThreadDownloader.parseContentRange("bytes 0-999/1000")
        )
    }

    @Test
    fun `解析非零起点的 Content-Range`() {
        // 分片请求的偏移必须原样带回来，否则第 2 片会被当成第 1 片写入。
        assertEquals(
            Triple(500L, 999L, 2000L),
            MultiThreadDownloader.parseContentRange("bytes 500-999/2000")
        )
    }

    @Test
    fun `total 为星号时视为不可信`() {
        // 服务端不知道总长度时必须回退单流，而不是把 * 当成 0。
        assertNull(MultiThreadDownloader.parseContentRange("bytes 0-0/*"))
    }

    @Test
    fun `缺失或畸形响应头一律返回 null 而不抛异常`() {
        // 返回 null 会让调用方按不可信处理并回退单流；抛异常则会中断整次下载。
        listOf(
            null,
            "",
            "bytes",
            "items 0-1/2",
            "bytes 0-999",
            "bytes abc-def/ghi",
            "bytes 0-999/"
        ).forEach { header ->
            assertNull("不该解析成功: $header", MultiThreadDownloader.parseContentRange(header))
        }
    }

    // ── buildRanges ───────────────────────────────────────────

    @Test
    fun `分片切分覆盖整个文件且首尾相接`() {
        val ranges = MultiThreadDownloader.buildRanges(1000L, 4)

        assertEquals(listOf(0L to 249L, 250L to 499L, 500L to 749L, 750L to 999L), ranges)
        assertEquals("必须从 0 开始", 0L, ranges.first().first)
        assertEquals("必须覆盖到最后一个字节", 999L, ranges.last().second)
        // 相邻分片必须首尾相接：空洞会下出全 0 区域，重叠会互相覆盖。
        ranges.zipWithNext().forEach { (a, b) ->
            assertEquals("分片之间不能有空洞或重叠", a.second + 1, b.first)
        }
    }

    @Test
    fun `除不尽时最后一片吸收余数`() {
        val ranges = MultiThreadDownloader.buildRanges(1000L, 3)

        // 不能简单地用 chunk 长度乘以片数——余数必须落在最后一片，否则尾部字节丢失。
        assertEquals(3, ranges.size)
        assertEquals(999L, ranges.last().second)
        assertEquals(1000L, ranges.sumOf { it.second - it.first + 1 })
        ranges.zipWithNext().forEach { (a, b) ->
            assertEquals(a.second + 1, b.first)
        }
    }

    @Test
    fun `分片长度之和等于文件长度`() {
        listOf(4L to 4, 1_048_576L to 8, 1_048_577L to 4, 999L to 1).forEach { (total, count) ->
            val ranges = MultiThreadDownloader.buildRanges(total, count)
            assertEquals("total=$total count=$count", count, ranges.size)
            assertEquals("total=$total count=$count", total, ranges.sumOf { it.second - it.first + 1 })
        }
    }

    // ── EntityTag.matches ─────────────────────────────────────

    private fun tag(etag: String? = null, lastModified: String? = null) =
        MultiThreadDownloader.EntityTag(etag, lastModified)

    @Test
    fun `ETag 相同判定为同一实体`() {
        assertTrue(tag(etag = "abc").matches(tag(etag = "abc")))
    }

    @Test
    fun `ETag 不同判定为换了文件`() {
        // 这是整条护栏的核心：长度一样、偏移也都对，只有实体标识能发现
        // 「探测与取分片之间镜像回源换了一次文件」。
        assertFalse(tag(etag = "abc").matches(tag(etag = "def")))
    }

    @Test
    fun `没有 ETag 时退回比较 Last-Modified`() {
        val t1 = "Mon, 01 Jan 2024 00:00:00 GMT"
        val t2 = "Tue, 02 Jan 2024 00:00:00 GMT"
        assertTrue(tag(lastModified = t1).matches(tag(lastModified = t1)))
        assertFalse(tag(lastModified = t1).matches(tag(lastModified = t2)))
    }

    @Test
    fun `ETag 优先于 Last-Modified`() {
        // Last-Modified 相同不代表是同一实体（一秒内可能多次改动），
        // 所以 ETag 不一致时不能因为时间戳相同就放行。
        assertFalse(tag(etag = "a", lastModified = "t").matches(tag(etag = "b", lastModified = "t")))
    }

    @Test
    fun `两边都取不到标识时视为无法判断并放行`() {
        assertTrue(tag().matches(tag()))
        assertTrue(tag().matches(tag(etag = "x")))
        assertTrue(tag(etag = "x").matches(tag()))
    }

    @Test
    fun `只有单侧提供标识时退回无法判断`() {
        // 一侧只有 ETag、另一侧只有 Last-Modified：没有可比维度，按放行处理
        // （不能把「无法比较」误判成「不一致」，否则所有无 ETag 的镜像都会被拒）。
        assertTrue(tag(etag = "a").matches(tag(lastModified = "t")))
    }
}

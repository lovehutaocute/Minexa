package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 解压预算护栏：体积与条目数上限。
 *
 * 背景：解压路径原先只有 canonical 前缀校验（防目录穿越），**没有体积/条目上限**。
 * 一个几百 KB 的压缩炸弹就能一路写到 `/data` 撑爆，而 App 与被托管的服务端共用同一
 * 分区——磁盘写满会让配置落盘、世界保存全部失败。这里给出一条明确预算，超限即中止。
 *
 * 断言的是「超过才拒绝」这个边界本身：
 *  - 去掉 `if` 判断 → 该红的用例会变绿（即上限失效）；
 *  - 把 `>` 写成 `>=` → 「正好等于上限」的用例会红。
 */
class ServerImporterExtractBudgetTest {

    @Test
    fun `条目数正好等于上限时放行，超过即中止`() {
        val budget = ServerImporter.ExtractBudget()
        repeat(ServerImporter.MAX_ENTRIES.toInt()) { budget.addEntry() }
        assertEquals(ServerImporter.MAX_ENTRIES, budget.entries)

        val thrown = runCatching { budget.addEntry() }.exceptionOrNull()
        assertTrue("超过 MAX_ENTRIES 必须中止导入（疑似压缩炸弹）", thrown is IOException)
    }

    @Test
    fun `体积正好等于上限时放行，超过即中止`() {
        val budget = ServerImporter.ExtractBudget()
        budget.addBytes(ServerImporter.MAX_TOTAL_BYTES)
        assertEquals(ServerImporter.MAX_TOTAL_BYTES, budget.bytes)

        val thrown = runCatching { budget.addBytes(1L) }.exceptionOrNull()
        assertTrue("超过 MAX_TOTAL_BYTES 必须中止导入", thrown is IOException)
    }

    @Test
    fun `单次超大体积直接拒绝`() {
        val budget = ServerImporter.ExtractBudget()
        val thrown = runCatching {
            budget.addBytes(ServerImporter.MAX_TOTAL_BYTES + 1)
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
    }

    @Test
    fun `预算是按累计量判断而不是按单个条目`() {
        // 压缩炸弹的典型形态就是「很多个都不算大的条目」，逐条看每条都无害。
        val budget = ServerImporter.ExtractBudget()
        val chunk = ServerImporter.MAX_TOTAL_BYTES / 4
        repeat(4) { budget.addBytes(chunk) }
        assertEquals(ServerImporter.MAX_TOTAL_BYTES, budget.bytes)

        val thrown = runCatching { budget.addBytes(2L) }.exceptionOrNull()
        assertTrue("累计超限必须中止", thrown is IOException)
    }

    @Test
    fun `上限取值本身是宽松的，不会误伤真实整合包`() {
        // 现代模组整合包解压后可达 10+ GiB，几十万小文件也常见；
        // 上限必须比这宽裕，否则正常导入会被误杀。
        assertTrue(
            "体积上限应 ≥ 32 GiB，实际 ${ServerImporter.MAX_TOTAL_BYTES}",
            ServerImporter.MAX_TOTAL_BYTES >= 32L * 1024 * 1024 * 1024
        )
        assertTrue(
            "条目数上限应 ≥ 40 万，实际 ${ServerImporter.MAX_ENTRIES}",
            ServerImporter.MAX_ENTRIES >= 400_000L
        )
    }
}

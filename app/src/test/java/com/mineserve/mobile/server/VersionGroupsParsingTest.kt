package com.mineserve.mobile.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PaperMC v3 的 `versions` 字段结构解析。
 *
 * 用真实响应片段做输入：Paper 与 Velocity 现在都返回两层对象，
 * 早期是扁平数组。只认一种就会在另一种上崩掉或返回空列表。
 */
class VersionGroupsParsingTest {

    private fun el(raw: String) = Json.parseToJsonElement(raw)

    @Test
    fun nestedObjectIsFlattened() {
        // 实际抓取自 https://fill.papermc.io/v3/projects/velocity 的片段
        val raw = """
            {"project":{"id":"velocity","name":"Velocity"},
             "versions":{"4.0.0":["4.3.2-SNAPSHOT","4.3.1","4.3.0"],
                         "3.0.0":["3.5.1","3.5.0"],
                         "1.1.0":["1.1.9"]}}
        """.trimIndent()
        val out = parseVersionGroups(el(raw).jsonObject["versions"])
        assertEquals(listOf("4.3.2-SNAPSHOT", "4.3.1", "4.3.0", "3.5.1", "3.5.0", "1.1.9"), out)
    }

    @Test
    fun flatArrayStillWorks() {
        val out = parseVersionGroups(el("""["1.20.4","1.20.2","1.20.1"]"""))
        assertEquals(listOf("1.20.4", "1.20.2", "1.20.1"), out)
    }

    @Test
    fun realPaperPayloadContainsCurrentVersions() {
        // 2026-10 实测片段：26.x 与 1.21 并存
        val raw = """{"versions":{"26.3":["26.3","26.3-rc-3"],"1.21":["1.21.11","1.21.11-rc3"]}}"""
        val out = parseVersionGroups(el(raw).jsonObject["versions"])
        assertTrue("26.3" in out)
        assertTrue("1.21.11" in out)
        // rc 由调用方过滤，解析层必须原样保留，否则调用方无法按需取舍
        assertTrue("26.3-rc-3" in out)
    }

    @Test
    fun missingOrWrongShapeYieldsEmptyInsteadOfThrowing() {
        assertEquals(emptyList<String>(), parseVersionGroups(el("""{"a":1}""").jsonObject["versions"]))
        assertEquals(emptyList<String>(), parseVersionGroups(el("""{"versions":null}""").jsonObject["versions"]))
        // 对象里混入非数组值：跳过而不是崩
        assertEquals(listOf("1.0"), parseVersionGroups(el("""{"versions":{"x":["1.0"],"y":{"k":1}}}""").jsonObject["versions"]))
    }
}

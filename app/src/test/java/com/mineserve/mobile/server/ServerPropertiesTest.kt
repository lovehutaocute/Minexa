package com.mineserve.mobile.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ServerProperties] 的保真读写测试。
 *
 * 核心不变量：**未修改的文档，解析后序列化必须与原文逐字节一致**。
 * 这正是原实现（读成 Map 后整份重写）破坏用户注释的回归点。
 */
class ServerPropertiesTest {

    private val sample = """
        #Minecraft server properties
        #Mon Sep 22 10:00:00 CST 2026
        enable-command-block=false

        # 端口与监听
        server-port=25565
        server-ip=

        motd=A Minecraft Server
        max-players=20

        # 玩家
        white-list=false
        online-mode=true
        resource-pack=https://example.com/?a=1&b=2
    """.trimIndent() + "\n"

    @Test
    fun `未修改时序列化与原文逐字节一致`() {
        assertEquals(sample, ServerProperties.parse(sample).toText())
    }

    @Test
    fun `结尾无换行也能逐字节还原`() {
        val text = "a=1\nb=2"
        assertEquals(text, ServerProperties.parse(text).toText())
    }

    @Test
    fun `空文件还原为空`() {
        assertEquals("", ServerProperties.parse("").toText())
    }

    @Test
    fun `CRLF 文件还原为标准换行且不残留回车`() {
        val text = "a=1\r\n# c\r\nb=2\r\n"
        val rewritten = ServerProperties.parse(text).toText()
        assertEquals("a=1\n# c\nb=2\n", rewritten)
        assertFalse(rewritten.contains('\r'))
    }

    @Test
    fun `修改已有键时保持行位置与前后注释`() {
        val doc = ServerProperties.parse(sample)
        doc["server-port"] = "25566"
        val out = doc.toText()

        // 注释一句不少
        assertTrue(out.contains("# 端口与监听"))
        assertTrue(out.contains("#Minecraft server properties"))
        // 位置没变：仍在 "# 端口与监听" 之后
        assertTrue(out.indexOf("# 端口与监听") < out.indexOf("server-port=25566"))
        // 空行分组保留
        assertEquals(sample.substringBefore("server-port=25565").count { it == '\n' },
            out.substringBefore("server-port=25566").count { it == '\n' })
        // 其它键原样
        assertTrue(out.contains("max-players=20"))
        assertTrue(out.contains("white-list=false"))
    }

    @Test
    fun `修改键不改变总行数`() {
        val doc = ServerProperties.parse(sample)
        doc["motd"] = "Hello"
        doc["max-players"] = "30"
        assertEquals(sample.count { it == '\n' }, doc.toText().count { it == '\n' })
    }

    @Test
    fun `新增键追加到末尾`() {
        val doc = ServerProperties.parse("a=1\n")
        doc["b"] = "2"
        assertEquals("a=1\nb=2\n", doc.toText())
    }

    @Test
    fun `merge 保留文件中未被提及的键`() {
        val doc = ServerProperties.parse(sample)
        doc.merge(mapOf("server-port" to "25570"))
        val out = doc.toText()
        assertTrue(out.contains("server-port=25570"))
        // 未提及的键与注释都还在
        assertTrue(out.contains("enable-command-block=false"))
        assertTrue(out.contains("motd=A Minecraft Server"))
        assertTrue(out.contains("# 玩家"))
    }

    @Test
    fun `值中可以包含等号`() {
        val doc = ServerProperties.parse(sample)
        assertEquals("https://example.com/?a=1&b=2", doc["resource-pack"])
    }

    @Test
    fun `没有等号的行按注释保留`() {
        val text = "a=1\n这一行没有等号\nb=2\n"
        val doc = ServerProperties.parse(text)
        assertEquals("1", doc["a"])
        assertEquals("2", doc["b"])
        assertEquals(text, doc.toText())
    }

    @Test
    fun `无冒号的空值键可正常读取`() {
        val doc = ServerProperties.parse("server-ip=\nmotd=x\n")
        assertEquals("", doc["server-ip"])
        assertEquals("x", doc["motd"])
    }

    @Test
    fun `布尔读取兼容 truefalse 与 onoff`() {
        val doc = ServerProperties.parse("a=true\nb=off\nc=TRUE\nd=maybe\n")
        assertEquals(true, doc.getBool("a"))
        assertEquals(false, doc.getBool("b"))
        assertEquals(true, doc.getBool("c"))
        assertNull(doc.getBool("d"))
        assertNull(doc.getBool("missing"))
    }

    @Test
    fun `布尔写入按指定格式序列化`() {
        val doc = ServerProperties.parse("x=false\n")
        doc.setBool("x", true)
        doc.setBool("y", false, BoolFormat.OnOff)
        // 末尾换行不丢，且不会多出一个空行
        assertEquals("x=true\ny=off\n", doc.toText())
        assertEquals("true", doc["x"])
        assertEquals("off", doc["y"])
    }

    @Test
    fun `remove 删除整行`() {
        val doc = ServerProperties.parse("a=1\n# c\nb=2\n")
        doc.remove("a")
        assertEquals("# c\nb=2\n", doc.toText())
    }

    @Test
    fun `of 生成带标记与结尾换行的新文件`() {
        val doc = ServerProperties.of(mapOf("server-port" to "25565"))
        val out = doc.toText()
        assertTrue(out.startsWith("# Minecraft server properties\n"))
        assertTrue(out.contains("server-port=25565"))
        assertTrue(out.endsWith("\n"))
        assertEquals(25565, doc.getInt("server-port"))
    }

    @Test
    fun `toMap 保持文件出现顺序`() {
        val doc = ServerProperties.parse("z=1\na=2\nm=3\n")
        assertEquals(listOf("z", "a", "m"), doc.toMap().keys.toList())
    }

    @Test
    fun `未知键不会因 merge 而丢失`() {
        val doc = ServerProperties.parse("custom-plugin-option=keep-me\nserver-port=25565\n")
        doc.merge(mapOf("server-port" to "25566"))
        val out = doc.toText()
        assertTrue(out.contains("custom-plugin-option=keep-me"))
        assertTrue(out.contains("server-port=25566"))
    }
}

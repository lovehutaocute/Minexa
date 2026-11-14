package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 「本次启动」硬边界的守卫。
 *
 * 用户侧要求：崩溃弹窗的「关键日志」「全部日志」**完全排除历史启动**。
 * 这里的用例用同一个文件模拟两次启动：第一段写入后记录偏移，第二段追加，
 * 断言读取结果里连一个旧字符都不出现；并验证「退出时冻结终点」后，
 * 之后追加的内容（自动重启）也不会渗进已冻结的快照。
 */
class SessionLogReaderTest {

    private fun tempFile(): File = Files.createTempFile("session-log", ".log").toFile().also { it.deleteOnExit() }

    @Test
    fun `只读取本次启动的字节区间`() {
        val file = tempFile()
        file.writeText("OLD-LAUNCH-1\nOLD-LAUNCH-2\n")
        val startOffset = file.length()
        file.appendText("NEW-LAUNCH-1\nNEW-LAUNCH-2\n")

        val slice = SessionLogReader.read(file, startOffset, file.length())
        assertNotNull(slice)
        val text = slice!!.text
        assertTrue("不得包含历史启动内容：$text", !text.contains("OLD-LAUNCH"))
        assertTrue("应包含本次启动内容：$text", text.contains("NEW-LAUNCH-1"))
        assertTrue(text.contains("NEW-LAUNCH-2"))
    }

    @Test
    fun `冻结终点后追加的内容不会进入快照`() {
        val file = tempFile()
        file.writeText("OLD\n")
        val start = file.length()
        file.appendText("RUN-A-1\nRUN-A-2\n")
        val frozenEnd = file.length()
        // 模拟自动重启：同一文件继续追加新一轮输出
        file.appendText("RUN-B-1\n")

        val slice = SessionLogReader.read(file, start, frozenEnd)
        val text = slice!!.text
        assertTrue(text.contains("RUN-A-2"))
        assertTrue("冻结后新增的内容必须被排除：$text", !text.contains("RUN-B-1"))
        assertTrue(!text.contains("OLD"))
    }

    @Test
    fun `超出上限时保留尾部且丢弃残行`() {
        val file = tempFile()
        val body = buildString {
            repeat(500) { appendLine("line-$it-padding-padding-padding") }
        }
        file.writeText(body)
        val slice = SessionLogReader.read(file, 0, file.length(), maxBytes = 1024)
        assertNotNull(slice)
        assertTrue("应标记为截断", slice!!.truncated)
        assertTrue("必须保留尾部", slice.text.contains("line-499-padding"))
        assertTrue("首行不应是半截行：${slice.text.lineSequence().first()}", slice.text.lineSequence().first().startsWith("line-"))
    }

    @Test
    fun `文件不存在时返回 null`() {
        assertEquals(null, SessionLogReader.read(File("/definitely/not/here.log"), 0, null))
    }
}

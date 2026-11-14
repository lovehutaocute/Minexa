package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 递归删除的安全实现。
 *
 * 回归点：**路径不是目录时不能崩**。
 * 2026-11-13 实机崩溃（`McViewModel.deleteCore`）就是 `File.deleteRecursively()`
 * 在根不是目录时抛 `AssertionError` —— 它是 `Error` 不是 `Exception`，
 * `catch (e: Exception)` 拦不住，整页闪退。公共存储是 FUSE，
 * `exists()` 通过之后目录被并发删掉/换成文件是常态。
 */
class FileOpsTest {

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("fileops").toFile().also { it.deleteOnExit() }

    @Test
    fun deletesNestedTree() {
        val root = tempDir()
        val deep = File(root, "a/b/c").apply { mkdirs() }
        File(deep, "world.dat").writeText("x")
        File(root, "a/level.txt").writeText("y")

        assertTrue(FileOps.deleteRecursively(root))
        assertFalse(FileOps.existsNoFollow(root))
    }

    /** 崩溃现场：路径根本不存在 / 已经不是目录，绝不能抛 */
    @Test
    fun missingPathAndPlainFileAreSafe() {
        val root = tempDir()
        assertTrue(FileOps.deleteRecursively(File(root, "not-there")))

        val file = File(root, "same-name").apply { writeText("i am a file") }
        assertTrue(FileOps.deleteRecursively(file))
        assertFalse(file.exists())
    }

    /** 幂等：已删过再删一次仍算成功（并发删除后重试不该报失败） */
    @Test
    fun repeatedDeleteStaysSuccessful() {
        val root = tempDir()
        File(root, "inner/file.bin").apply { parentFile?.mkdirs() }.writeText("1")
        assertTrue(FileOps.deleteRecursively(File(root, "inner")))
        assertTrue(FileOps.deleteRecursively(File(root, "inner")))
    }

    @Test
    fun emptyDirectoryIsReportedAsDirectory() {
        val root = tempDir()
        assertTrue(FileOps.isRealDirectory(root))
        assertTrue(FileOps.existsNoFollow(root))
        val file = File(root, "f").apply { writeText("z") }
        assertFalse(FileOps.isRealDirectory(file))
        assertEquals(1, root.listFiles()!!.size)
    }
}

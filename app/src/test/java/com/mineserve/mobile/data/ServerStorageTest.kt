package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 存储位置规范化：服务器只认统一存储目录，旧记录要等迁移。
 *
 * 这些断言对应「完全不兼容旧目录」这条规则：旧目录里的文件**不会**被自动当成
 * 可运行的服务器，只有已经出现在统一目录里的才能用。
 */
class ServerStorageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun core(dirName: String, location: StorageLocation) = InstalledCore(
        name = dirName,
        core = ServerCore.Paper,
        version = "1.21.8",
        dirName = dirName,
        serverFile = "server.jar",
        location = location
    )

    @Test
    fun legacyRecordWithoutInternalCopyStaysLegacy() {
        // 还没迁移：记录保持 Legacy（列表里不显示、不可启动），等用户确认迁移
        val internal = temp.newFolder("Minexa")
        val (cores, changed) = ServerStorage.normalizeCores(
            listOf(core("survival", StorageLocation.Legacy)), internal
        )
        assertEquals(StorageLocation.Legacy, cores.single().location)
        assertFalse(changed)
    }

    @Test
    fun legacyRecordWithEmptyInternalShellStaysLegacy() {
        // 之前错误版本可能在新目录里留下空壳：不能因为「目录存在」就当成可用
        val internal = temp.newFolder("Minexa")
        File(internal, "survival").mkdirs()
        val (cores, changed) = ServerStorage.normalizeCores(
            listOf(core("survival", StorageLocation.Legacy)), internal
        )
        assertEquals(StorageLocation.Legacy, cores.single().location)
        assertFalse(changed)
    }

    @Test
    fun legacyRecordWithMigratedCopyBecomesInternal() {
        // 用户手工把目录搬过来或迁移刚完成：以磁盘为准，自动纠正为 Internal
        val internal = temp.newFolder("Minexa")
        File(internal, "survival").mkdirs()
        File(internal, "survival/server.jar").writeText("x")
        val (cores, changed) = ServerStorage.normalizeCores(
            listOf(core("survival", StorageLocation.Legacy)), internal
        )
        assertEquals(StorageLocation.Internal, cores.single().location)
        assertTrue(changed)
    }

    @Test
    fun internalRecordIsUntouched() {
        val internal = temp.newFolder("Minexa")
        File(internal, "paper1").mkdirs()
        val (cores, changed) = ServerStorage.normalizeCores(
            listOf(core("paper1", StorageLocation.Internal)), internal
        )
        assertEquals(StorageLocation.Internal, cores.single().location)
        assertFalse(changed)
    }

    @Test
    fun normalizeIsIdempotent() {
        val internal = temp.newFolder("Minexa")
        File(internal, "moved").mkdirs()
        File(internal, "moved/server.jar").writeText("x")
        val (first, changedOnce) = ServerStorage.normalizeCores(
            listOf(core("moved", StorageLocation.Legacy)), internal
        )
        assertTrue(changedOnce)
        val (second, changedTwice) = ServerStorage.normalizeCores(first, internal)
        assertFalse(changedTwice)
        assertEquals(first, second)
    }

    @Test
    fun emptyDirectoryDetectionIgnoresNestedShells() {
        val empty = temp.newFolder("shell")
        File(empty, "world/region").mkdirs()
        assertTrue(ServerStorage.isEmptyDirectory(empty))

        File(empty, "world/level.dat").writeText("x")
        assertFalse(ServerStorage.isEmptyDirectory(empty))
    }

    @Test
    fun storageLocationDefaultsToInternalForUnknownValues() {
        assertEquals(StorageLocation.Internal, StorageLocation.of(null))
        assertEquals(StorageLocation.Internal, StorageLocation.of("SomethingElse"))
        assertEquals(StorageLocation.Legacy, StorageLocation.of("legacy"))
    }
}

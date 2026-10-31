package com.mineserve.mobile.server

import com.mineserve.mobile.data.InstalledCore
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.StorageLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 旧版目录迁移：搬运必须无损，重名必须可预测，配置必须跟着改。
 *
 * 这里锁住的是「用户世界存档不能被搬坏」的那些前提：复制 → 校验 → 改名 → 删源，
 * 目标重名时加后缀而不是覆盖。
 */
class LegacyServerMigrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun makeServer(dir: File, files: Int = 3) {
        dir.mkdirs()
        File(dir, "server.jar").writeText("jar")
        File(dir, "world").mkdirs()
        for (i in 1 until files) {
            File(dir, "world/region_$i.mca").writeText("region-$i")
        }
        File(dir, "server.properties").writeText("server-port=25565\n")
    }

    @Test
    fun migrateCopiesEverythingThenRemovesSource() {
        val source = File(temp.newFolder("legacy"), "666")
        makeServer(source)
        val internal = temp.newFolder("internal")
        val taken = mutableSetOf<String>()

        val outcome = LegacyServerMigration.migrateOne(source, internal, taken)

        assertTrue(outcome.error, outcome.success)
        assertEquals("666", outcome.targetDirName)
        assertEquals(4, outcome.fileCount)
        val target = File(internal, "666")
        assertTrue(File(target, "server.jar").isFile)
        assertTrue(File(target, "world/region_1.mca").isFile)
        assertEquals(
            "server-port=25565\n",
            File(target, "server.properties").readText()
        )
        // 源目录在校验通过后才删除
        assertFalse(source.exists())
    }

    @Test
    fun emptyTargetShellIsReusedInsteadOfRenaming() {
        // 之前错误版本会在新目录里建出空壳：迁移应直接复用它，不产生 666_migrated
        val source = File(temp.newFolder("legacy"), "6666")
        makeServer(source)
        val internal = temp.newFolder("internal")
        File(internal, "6666").mkdirs()
        val taken = mutableSetOf("6666")

        val outcome = LegacyServerMigration.migrateOne(source, internal, taken)

        assertTrue(outcome.error, outcome.success)
        assertEquals("6666", outcome.targetDirName)
        assertTrue(File(internal, "6666/server.jar").isFile)
        assertFalse(File(internal, "6666_migrated").exists())
    }

    @Test
    fun occupiedTargetGetsSuffixAndKeepsExistingData() {
        val source = File(temp.newFolder("legacy"), "world")
        makeServer(source)
        val internal = temp.newFolder("internal")
        File(internal, "world").mkdirs()
        File(internal, "world/keep.txt").writeText("existing")
        val taken = mutableSetOf("world")

        val outcome = LegacyServerMigration.migrateOne(source, internal, taken)

        assertTrue(outcome.error, outcome.success)
        assertEquals("world_migrated", outcome.targetDirName)
        // 新目录里原有的同名服务器没有被覆盖
        assertEquals("existing", File(internal, "world/keep.txt").readText())
        assertTrue(File(internal, "world_migrated/server.jar").isFile)
    }

    @Test
    fun secondCollisionGetsNumberedSuffix() {
        val internal = temp.newFolder("internal")
        File(internal, "srv").mkdirs()
        File(internal, "srv/x").writeText("x")
        File(internal, "srv_migrated").mkdirs()
        File(internal, "srv_migrated/y").writeText("y")

        val name = LegacyServerMigration.planTargetDirName("srv", internal, setOf("srv", "srv_migrated"))
        assertEquals("srv_migrated_2", name)
    }

    @Test
    fun missingSourceFailsWithoutTouchingAnything() {
        val internal = temp.newFolder("internal")
        val outcome = LegacyServerMigration.migrateOne(File(internal, "ghost"), internal, mutableSetOf())
        assertFalse(outcome.success)
        assertNotNull(outcome.error)
    }

    @Test
    fun applyOutcomesMovesRegisteredCoreToInternal() {
        val internal = temp.newFolder("internal")
        File(internal, "666").mkdirs()
        File(internal, "666/server.jar").writeText("jar")
        val config = McConfig(
            installedCores = listOf(
                InstalledCore("666", ServerCore.Paper, "1.21.8", "666", "server.jar", StorageLocation.Legacy)
            ),
            activeCoreName = "666"
        )
        val outcomes = listOf(
            LegacyServerMigration.Outcome("666", "666", success = true, fileCount = 1)
        )

        val updated = LegacyServerMigration.applyOutcomes(config, outcomes, internal)

        assertEquals(1, updated.installedCores.size)
        assertEquals(StorageLocation.Internal, updated.installedCores.single().location)
        assertEquals("666", updated.activeCoreName)
    }

    @Test
    fun applyOutcomesFollowsRenamedDirectory() {
        val internal = temp.newFolder("internal")
        File(internal, "srv_migrated").mkdirs()
        File(internal, "srv_migrated/server.jar").writeText("jar")
        val config = McConfig(
            installedCores = listOf(
                InstalledCore("srv", ServerCore.Paper, "1.21.8", "srv", "server.jar", StorageLocation.Legacy)
            ),
            activeCoreName = "srv"
        )
        val outcomes = listOf(
            LegacyServerMigration.Outcome("srv", "srv_migrated", success = true, fileCount = 1)
        )

        val updated = LegacyServerMigration.applyOutcomes(config, outcomes, internal)

        val core = updated.installedCores.single()
        assertEquals("srv_migrated", core.dirName)
        assertEquals(StorageLocation.Internal, core.location)
        // 当前选中的核心名要跟着改，否则概览会显示「未选择核心」
        assertEquals("srv", updated.activeCoreName)
    }

    @Test
    fun applyOutcomesRegistersUnregisteredMigration() {
        val internal = temp.newFolder("internal")
        File(internal, "old-one").mkdirs()
        File(internal, "old-one/server.jar").writeText("jar")
        val config = McConfig(installedCores = emptyList(), activeCoreName = null)
        val outcomes = listOf(
            LegacyServerMigration.Outcome("old-one", "old-one", success = true, fileCount = 1)
        )

        val updated = LegacyServerMigration.applyOutcomes(config, outcomes, internal)

        assertEquals(1, updated.installedCores.size)
        assertEquals("old-one", updated.installedCores.single().dirName)
        assertEquals(StorageLocation.Internal, updated.installedCores.single().location)
    }

    @Test
    fun failedOutcomeLeavesConfigUntouched() {
        val internal = temp.newFolder("internal")
        val config = McConfig(
            installedCores = listOf(
                InstalledCore("srv", ServerCore.Paper, "1.21.8", "srv", "server.jar", StorageLocation.Legacy)
            ),
            activeCoreName = "srv"
        )
        val outcomes = listOf(
            LegacyServerMigration.Outcome("srv", null, success = false, error = "复制校验失败")
        )

        val updated = LegacyServerMigration.applyOutcomes(config, outcomes, internal)

        assertEquals(config, updated)
    }
}

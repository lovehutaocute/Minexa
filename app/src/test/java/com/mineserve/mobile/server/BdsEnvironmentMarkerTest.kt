package com.mineserve.mobile.server

import com.mineserve.mobile.data.BdsContainerDistro
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 就绪标记的代次判定：老环境（没有 gen 行）必须被判成「未初始化」，
 * 引导用户重新初始化拿到带历史版本兼容库的完整环境。
 */
class BdsEnvironmentMarkerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun prefixWithRuntime(): File {
        val prefix = temp.newFolder()
        val root = BdsRuntime.rootDir(prefix)
        // isReady 的两个前置件：box64 二进制 + 7 个内置 x86 库（只造占位，内容不参与判定）
        File(root, "box64").apply { parentFile.mkdirs(); writeBytes(ByteArray(64)) }
        val libs = BdsRuntime.x86LibDir(prefix).apply { mkdirs() }
        BdsRuntime.REQUIRED_X86_LIBS.forEach { File(libs, it).writeBytes(ByteArray(64)) }
        return prefix
    }

    private fun writeMarker(prefix: File, text: String) {
        BdsEnvironmentManager.readyMarker(prefix).writeText(text)
    }

    /** 全新设备：box64 与运行库被内置副本补齐过，但从来没有就绪标记 */
    private fun prefixWithRuntimeNoMarker(): File {
        val prefix = prefixWithRuntime()
        BdsEnvironmentManager.readyMarker(prefix).delete()
        return prefix
    }

    @Test
    fun markerWithoutGenerationIsNotReady() {
        val prefix = prefixWithRuntime()
        // gen 1 时代的标记：没有 gen 行 → 未初始化
        writeMarker(prefix, "${BdsContainerDistro.dirName}\nbundled-0.3.2\n")
        assertFalse(BdsEnvironmentManager.isReady(prefix))
    }

    @Test
    fun markerWithCompatButNoGenerationIsNotReady() {
        val prefix = prefixWithRuntime()
        // 中间版本：有 compat 行但没有 gen 行
        writeMarker(prefix, "${BdsContainerDistro.dirName}\nbundled-0.3.2\ncompat=ok\n")
        assertFalse(BdsEnvironmentManager.isReady(prefix))
    }

    @Test
    fun currentGenerationMarkerIsReady() {
        val prefix = prefixWithRuntime()
        writeMarker(
            prefix,
            "${BdsContainerDistro.dirName}\nbundled-0.3.2\ncompat=ok\ngen=${BdsEnvironmentManager.ENV_GENERATION}\n"
        )
        assertTrue(BdsEnvironmentManager.isReady(prefix))
    }

    @Test
    fun oldMarkerIsReportedAsUpgradeNotFirstRun() {
        val prefix = prefixWithRuntime()
        // 装过、但代次旧了 → 界面提示「重新初始化」，不是「首次初始化」
        writeMarker(prefix, "${BdsContainerDistro.dirName}\nbundled-0.3.2\n")
        assertTrue(BdsEnvironmentManager.staleGeneration(prefix))
        // 全新设备没有标记 → 不算升级用户
        val clean = prefixWithRuntimeNoMarker()
        assertFalse(BdsEnvironmentManager.staleGeneration(clean))
    }

    @Test
    fun wrongDistroIsNotReady() {
        val prefix = prefixWithRuntime()
        writeMarker(
            prefix,
            "focal-rootfs\nbundled-0.3.2\ngen=${BdsEnvironmentManager.ENV_GENERATION}\n"
        )
        assertFalse(BdsEnvironmentManager.isReady(prefix))
    }
}

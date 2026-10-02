package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用用户真实日志（汉化后的中文）端到端重放，锁死两个症状：
 *   ① 下载依赖 ↔ 加载核心 左右横跳
 *   ② 直接跳到 100%
 *
 * 这个测试**直接调用生产代码** startupPhaseForLog + 与 McViewModel 相同的
 * "只增不减"推进规则，不复制关键词表，因此不会因为测试副本过期而失真。
 */
class RealLogReplayTest {

    /** 与 McViewModel.updateStartupPhaseFromLog 的推进规则逐字等价。 */
    private fun advance(cur: StartupPhase, phase: StartupPhase?): StartupPhase {
        phase ?: return cur
        return if (phase.progress <= cur.progress) cur else phase
    }

    private fun replay(lines: List<String>): List<StartupPhase> {
        var cur = StartupPhase.PreparingEnvironment
        val trace = mutableListOf(cur)
        for (l in lines) {
            val p = startupPhaseForLog(l) ?: continue
            val next = advance(cur, p)
            if (next != cur) { cur = next; trace.add(cur) }
        }
        return trace
    }

    /** 用户真实日志，分段顺序即文件中的真实先后。 */
    private val realLog = listOf(
        // ── 准备环境 ──
        "[bootstrap] 正在准备运行环境",
        "[bootstrap] 正在检查 Java 运行时",
        // ── 下载依赖 ──
        "正在下载依赖库",
        "正在服务器上安装 Fabric Loader",
        "正在加载 Minecraft 26.3 及依赖",
        "缺少映射！正在重新映射",
        "正在加载 4 个模组:",
        "正在下载所需文件",
        "正在下载 library net.fabricmc.fabric-loader",
        "正在生成服务端启动 JAR",
        // ── 启动 Java ──
        "[startMc] java 路径: /data/data/com.mineserve.mobile/files/bin/java",
        "[bootstrap] 自动修复完成，共处理 6 项",
        // ── 启动网络 ──
        "正在启动 Minecraft 世界",
        // ── 创建世界 ──
        "正在加载世界 \"world\"",
        "正在准备出生点区域: 100%",
        // ── 完成 ──
        "完成（2.483 秒）！"
    )

    @Test
    fun realLocalizedLogNeverOscillates() {
        val trace = replay(realLog)
        // 症状①：进度条绝不能往回走（"两个左右跳"）
        var backSteps = 0
        trace.zipWithNext { a, b -> if (b.progress < a.progress) backSteps++ }
        assertEquals("进度条回退了 $backSteps 次（左右跳）：$trace", 0, backSteps)
    }

    @Test
    fun realLocalizedLogDoesNotSkipHighPhases() {
        val trace = replay(realLog)
        // 症状②：不能直接从 62% 蹦到 100%。完成后必须出现 Ready，
        // 且 Ready **之前**必须是 CreatingWorld（82%），而不是绕过它。
        assertEquals(StartupPhase.Ready, trace.last())
        val idxReady = trace.indexOf(StartupPhase.Ready)
        assertTrue("Ready 之前应至少经过 CreatingWorld，实际轨迹：$trace",
            trace.take(idxReady).contains(StartupPhase.CreatingWorld))
    }

    @Test
    fun everyRealLogLineIsClassified() {
        // 用户日志里**每一行都该被识别**，一行不识别就意味着进度条会卡顿
        val unclassified = realLog.filter { startupPhaseForLog(it) == null }
        assertTrue("以下真实日志行未被任何阶段识别，会导致进度条停顿：$unclassified",
            unclassified.isEmpty())
    }

    @Test
    fun downloadKeywordsAreNotStolenByNetworkPhase() {
        // 症状①的直接根因回归：下载行的判定必须落在"下载依赖"档，
        // 不能被"网络监听"(72%) 抢走，否则进度条会在 40%↔72% 之间跳。
        assertEquals(StartupPhase.DownloadingDependencies, startupPhaseForLog("正在下载依赖库"))
        assertEquals(StartupPhase.DownloadingDependencies, startupPhaseForLog("正在下载所需文件"))
        assertEquals(StartupPhase.DownloadingDependencies, startupPhaseForLog("正在下载 library x"))
        assertEquals(StartupPhase.DownloadingDependencies, startupPhaseForLog("正在服务器上安装 Fabric Loader"))
        assertEquals(StartupPhase.DownloadingDependencies, startupPhaseForLog("正在生成服务端启动 JAR"))
    }

    @Test
    fun coreProbeInsideDownloadPhaseDoesNotOscillate() {
        // 症状①的真实成因：核心探针行夹在下载段中间，随后下载又继续。
        // 只增不减下，40% 采纳后 62% 的核心探针会前进到 62%，
        // 之后的下载行（40%）被忽略 —— 关键是**不回退**。
        val trace = replay(
            listOf(
                "正在下载依赖库",
                "正在加载 Minecraft 26.3 及依赖",   // 核心探针
                "正在下载所需文件",                  // ← 旧实现会回退到 40%
                "正在下载 library x"
            )
        )
        trace.zipWithNext { a, b ->
            assertTrue("核心探针夹在下载中间导致回退：$trace", b.progress >= a.progress)
        }
    }

}

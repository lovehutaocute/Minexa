package com.mineserve.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动命令完整性判定的回归测试。
 *
 * ## 背景（1.2.6 线上事故）
 * `assertLaunchCommandComplete` 的判定过严，把**合法**启动方式误判成
 * "启动命令不完整"，点启动直接弹 RuntimeException、服务端起不来，
 * 用户反馈"回退 1.2.5 就正常"。实际受害者有两类：
 *   1. Forge 1.17+ / NeoForge：启动参数是 `@libraries/.../unix_args.txt`（java argfile），
 *      命令里既没有 `-jar` 也没有 `-cp`；
 *   2. 「完全自定义启动命令」模式：命令由用户自己写，不会有 `exec `。
 *
 * 判定**只应**针对真正的截断（字符串拼接漏掉 `+`，导致
 * `cd '<serverDir>' && exec '<java>' … -jar server.jar nogui` 整段消失）报警。
 */
class LaunchCommandValidationTest {

    /** 复刻 TermuxRuntime.startMc 自动拼接出来的前缀（环境变量 → cd → exec）。 */
    private fun hostPrefix(): String =
        "export PATH='/x/bin:/system/bin'; " +
            "export LD_LIBRARY_PATH='/x/lib'; " +
            "export TMPDIR='/x/tmp'; " +
            "cd '/srv/mc' && " +
            "exec '/x/lib/jvm/java-17-openjdk/bin/java' " +
            "-Djava.io.tmpdir='/x/tmp' -Xmx2048m "

    // ---------------------------------------------------------------- 合法启动

    @Test
    fun forgeArgFileLaunchIsAccepted() {
        // Forge 1.17+：launchArgs = "@/…/unix_args.txt"
        val cmd = hostPrefix() +
            "@/srv/mc/libraries/net/minecraftforge/forge/1.20.1-47.4.10/unix_args.txt nogui"
        assertEquals("Forge argfile 是官方启动方式，不该被判为不完整", emptyList<String>(), launchCommandProblems(cmd))
    }

    @Test
    fun neoforgeArgFileLaunchIsAccepted() {
        val cmd = hostPrefix() +
            "@/srv/mc/libraries/net/neoforged/neoforge/21.1.72/unix_args.txt nogui"
        assertEquals("NeoForge 同样走 @参数文件", emptyList<String>(), launchCommandProblems(cmd))
    }

    @Test
    fun vanillaJarLaunchIsAccepted() {
        val cmd = hostPrefix() + "-jar /srv/mc/server.jar nogui"
        assertEquals(emptyList<String>(), launchCommandProblems(cmd))
    }

    @Test
    fun bukkitClasspathLaunchIsAccepted() {
        // Spigot/CraftBukkit 自包含核心：-cp <jar>:<libs> 主类
        val cmd = hostPrefix() + "-cp '/srv/mc/server.jar:/srv/mc/libs/a.jar' org.bukkit.craftbukkit.Main nogui"
        assertEquals(emptyList<String>(), launchCommandProblems(cmd))
    }

    @Test
    fun powerNukkitXClasspathLaunchIsAccepted() {
        val cmd = hostPrefix() +
            "--add-opens=java.base/java.lang=ALL-UNNAMED -cp '/srv/mc/server.jar:/srv/mc/libs/*' org.powernukkitx.Server"
        assertEquals(emptyList<String>(), launchCommandProblems(cmd))
    }

    @Test
    fun quotationInsideArgFilePathStillMatches() {
        // 路径带引号时（shellQuote 包装）也必须识别为 argfile
        val cmd = hostPrefix() + "@/srv/my mc/unix_args.txt nogui"
        assertEquals(emptyList<String>(), launchCommandProblems(cmd))
    }

    // ---------------------------------------------------------------- 真正的截断

    @Test
    fun truncatedCommandIsRejected() {
        // 真实故障形态：漏掉 `+` 后 `cd … && exec …` 整段消失，只剩 export 赋值，
        // shell 执行完赋值即退出、java 从未运行 → "点启动秒退、无任何日志"
        val cmd = "export PATH='/x/bin'; export LD_PRELOAD='/x/lib/libheaptagfix.so'; export TMPDIR='/x/tmp'; "
        val problems = launchCommandProblems(cmd)
        assertTrue("截断必须被拦下，实际: $problems", problems.any { it.contains("执行段") })
    }

    @Test
    fun commandEndingWithAndAndIsRejected() {
        val cmd = hostPrefix().trimEnd() + "&& "
        assertTrue(launchCommandProblems(cmd).isNotEmpty())
    }

    @Test
    fun missingExecPrefixIsRejected() {
        // 自动拼接路径要求 `exec `：没有它说明拼接被截断（java 不会接管进程）
        val cmd = "export PATH='/x/bin'; cd '/srv/mc' && java -jar /srv/mc/server.jar nogui"
        val problems = launchCommandProblems(cmd)
        assertTrue("缺少 exec 必须被拦下，实际: $problems", problems.any { it.contains("exec") })
    }

    @Test
    fun launcherMarkerMissingIsRejected() {
        // 有 exec 但看不出要启动什么（例如 launchArgs 被拼成空串）
        val cmd = hostPrefix() + "nogui"
        val problems = launchCommandProblems(cmd)
        assertTrue("缺少启动器标记必须被拦下，实际: $problems", problems.any { it.contains("参数文件") })
    }

    // ---------------------------------------------------------------- 自定义命令模式

    @Test
    fun customCommandWithoutExecIsAccepted() {
        // 「完全自定义启动命令」：用户写什么就是什么，不带 exec 也不能拦
        val cmd = "export PATH='/x/bin'; export HOME='/x/home'; cd '/srv/mc' && java -Xmx4G -jar server.jar nogui"
        assertEquals(
            emptyList<String>(),
            launchCommandProblems(cmd, requireExecPrefix = false, requireLauncherMarker = false)
        )
    }

    @Test
    fun customCommandWithPipeIsAccepted() {
        val cmd = "export PATH='/x/bin'; cd '/srv/mc' && ./start.sh 2>&1 | tee run.log"
        assertEquals(
            emptyList<String>(),
            launchCommandProblems(cmd, requireExecPrefix = false, requireLauncherMarker = false)
        )
    }

    @Test
    fun truncatedCustomCommandIsRejected() {
        // 自定义模式仍要兜住「cd … && $command 整段消失」
        val cmd = "export PATH='/x/bin'; export HOME='/x/home'; export TMPDIR='/x/tmp'; "
        val problems = launchCommandProblems(cmd, requireExecPrefix = false, requireLauncherMarker = false)
        assertTrue("自定义模式下的截断也必须被拦下，实际: $problems", problems.any { it.contains("执行段") })
    }

    // ──────────────────────────────────────────────────────────
    // LD_PRELOAD 片段拼装
    // ──────────────────────────────────────────────────────────

    @Test
    fun multiplePreloadLibrariesGoIntoOneExport() {
        // LD_PRELOAD 是普通环境变量：写两条 export 是**覆盖**，先写的会被静默丢掉。
        // 所以「两个库」必须出现在同一条 export 里，用 ':' 分隔。
        val fragment = preloadExportFragment(listOf("/n/libheaptagfix.so", "/n/libipv6fix.so"))
        assertEquals("export LD_PRELOAD='/n/libheaptagfix.so:/n/libipv6fix.so'; ", fragment)
        assertEquals(
            "只能出现一条 export LD_PRELOAD",
            1,
            Regex("export LD_PRELOAD=").findAll(fragment).count()
        )
    }

    @Test
    fun singlePreloadLibraryStillProducesOneExport() {
        assertEquals(
            "export LD_PRELOAD='/n/libheaptagfix.so'; ",
            preloadExportFragment(listOf("/n/libheaptagfix.so"))
        )
    }

    @Test
    fun emptyPreloadListProducesNothing() {
        // 库缺失（降级环境）时必须返回空串而不是 `export LD_PRELOAD='';`
        // —— 后者会让 linker 报一行错，属于无中生有的噪音。
        assertEquals("", preloadExportFragment(emptyList()))
        assertEquals("", preloadExportFragment(listOf("", "   ")))
    }

    @Test
    fun duplicatePreloadLibrariesAreDeduped() {
        assertEquals(
            "export LD_PRELOAD='/n/libipv6fix.so'; ",
            preloadExportFragment(listOf("/n/libipv6fix.so", "/n/libipv6fix.so"))
        )
    }

    @Test
    fun preloadFragmentKeepsCommandComplete() {
        // 拼接位置就在 `cd … && exec java` 之前，漏掉 `+` 会重现 1.2.8 的
        // 「整段执行命令消失」事故，所以这里把它塞进完整命令里再判一次。
        val cmd = "export PATH='/x/bin'; " +
            preloadExportFragment(listOf("/n/libheaptagfix.so", "/n/libipv6fix.so")) +
            "cd '/srv/mc' && exec '/x/bin/java' -Xmx2048m -jar server.jar nogui"
        assertEquals(emptyList<String>(), launchCommandProblems(cmd))
    }
}

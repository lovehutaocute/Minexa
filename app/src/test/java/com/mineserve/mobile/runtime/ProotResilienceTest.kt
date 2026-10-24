package com.mineserve.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 PRoot 容器安装失败的分类逻辑。
 *
 * 这些判断决定**要不要换兼容参数重试**，判断错了的代价很直接：
 *  - 把 OOM(137) / 源失败(13) 当成崩溃 → 白等 4 轮 × 15 分钟，最后还是失败；
 *  - 把崩溃当成普通失败 → 用户永远撞同一堵墙，而换个参数本来能过。
 *
 * 样本取自真实故障日志：`proot warning: signal 11 received from process -1`。
 */
class ProotResilienceTest {

    private val crashLog = """
        [proot] 正在恢复容器内未完成的软件包事务...
        proot warning: signal 11 received from process -1
        [proot] Ubuntu 22.04 (jammy) · Java 21 安装失败
    """.trimIndent()

    @Test
    fun `proot 崩溃日志被判为可重试`() {
        // 脚本里的 exit 1 会把信号退出码盖掉，所以必须靠输出文本认出来。
        assertTrue(isProotCrashSignal(1, crashLog))
    }

    @Test
    fun `信号类退出码被判为可重试`() {
        assertTrue(isProotCrashSignal(139, ""))  // SIGSEGV
        assertTrue(isProotCrashSignal(135, null)) // SIGBUS
        assertTrue(isProotCrashSignal(134, ""))  // SIGABRT
    }

    @Test
    fun `超时不算崩溃`() {
        // 换参数只会更慢，重试毫无意义。
        assertFalse(isProotCrashSignal(124, crashLog))
    }

    @Test
    fun `内存不足被杀不算崩溃`() {
        // SIGKILL 多半是 OOM / 用户强杀，正确的提示是「清理后重试」。
        assertFalse(isProotCrashSignal(137, ""))
    }

    @Test
    fun `脚本自己声明的阶段退出码不算崩溃`() {
        assertFalse(isProotCrashSignal(11, "[proot] dpkg --configure -a 失败"))
        assertFalse(isProotCrashSignal(12, "[proot] apt-get -f install 失败"))
        assertFalse(isProotCrashSignal(13, "[proot] 全部软件源均安装失败"))
    }

    @Test
    fun `成功退出不算崩溃`() {
        assertFalse(isProotCrashSignal(0, ""))
    }

    @Test
    fun `兼容档位逐级变保守且首档是默认`() {
        val fallbacks = TermuxRuntime.ProotLaunchTuning.fallbacks
        assertTrue("至少要给一个兜底档位", fallbacks.size >= 2)

        val first = fallbacks.first()
        assertFalse("首选必须是默认参数，否则所有安装都白慢一轮", first.noSeccomp)
        assertTrue("默认档保留 kill-on-exit", first.killOnExit)
        assertTrue("默认档保留 sysvipc", first.sysvipc)

        val second = fallbacks[1]
        assertTrue("第二档必须关掉 seccomp 加速（最常见的崩溃原因）", second.noSeccomp)

        // 档位必须真的不同：重复档位等于白跑一轮超时。
        // 键要把三个参数都算进去 —— 第三、四档只差 killOnExit。
        val signatures = fallbacks.map { Triple(it.noSeccomp, it.sysvipc, it.killOnExit) }
        assertEquals(fallbacks.size, signatures.distinct().size)
    }

    @Test
    fun `失败提示能指到下一步动作`() {
        val name = "Ubuntu 22.04 (jammy) · Java 21"

        val crash = prootInstallFailureMessage(name, 1, crashLog)
        assertTrue("崩溃要提示换 JRE 或换发行版：$crash", crash.contains("Pojav JRE"))

        val halfDone = prootInstallFailureMessage(name, 11, "")
        assertTrue("半完成事务要提示修复：$halfDone", halfDone.contains("修复"))

        val killed = prootInstallFailureMessage(name, 137, "")
        assertTrue("OOM 要提示清理：$killed", killed.contains("内存") || killed.contains("存储"))

        val mirror = prootInstallFailureMessage(name, 13, "")
        assertTrue("源失败要提示网络：$mirror", mirror.contains("网络"))

        // 每一条都要带上具体环境，否则用户分不清是哪个宿主的哪个 Java。
        listOf(crash, halfDone, killed, mirror).forEach {
            assertTrue("提示里要带环境名：$it", it.contains(name))
        }
    }

    @Test
    fun `未知退出码保留原信息便于排障`() {
        val msg = prootInstallFailureMessage("X · Java 21", 42, null)
        assertTrue("未知退出码要显式写出：$msg", msg.contains("42"))
    }

    @Test
    fun `包状态损坏的提示优先于退出码 13`() {
        // 真实场景（1.3.1 之后用户实机）：dpkg 记着 openjdk-21-jdk 已安装，
        // 但 /usr/lib/jvm 下没有 java —— 每轮 apt 都报「已是最新版本」，
        // 两轮跑完 exit=13。若只看退出码就会误导成「检查网络」。
        val tail = """
            [proot] openjdk-21-jdk 记录为已安装但 java 文件缺失，强制重新解包
            [proot] 安装结束但找不到 java 可执行文件（/usr/lib/jvm/java-21-openjdk-arm64/bin/java），容器内的包状态已损坏
            [proot] 全部软件源均安装失败
        """.trimIndent()

        val msg = prootInstallFailureMessage("Ubuntu 22.04 (jammy) · Java 21", 13, tail)
        assertTrue("要指到「删容器重装」而不是网络：$msg", msg.contains("容器"))
        assertFalse("不能误导成网络问题：$msg", msg.contains("检查网络"))
    }

    @Test
    fun `索引下载失败要提示网络`() {
        val tail = "[proot] apt-get update 失败（源 http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports）"
        val msg = prootInstallFailureMessage("X · Java 21", 13, tail)
        assertTrue("要提示网络：$msg", msg.contains("网络"))
    }

    @Test
    fun `磁盘下限要覆盖 openjdk 解包体积`() {
        // openjdk-21-jdk 解包约 500MB，apt 还要缓存 deb 与依赖；
        // 低于 1GB 必然中途失败，给个下限防止「装到一半炸」。
        assertTrue(CONTAINER_INSTALL_MIN_FREE_MB >= 1024)
    }
}

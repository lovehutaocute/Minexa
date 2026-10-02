package com.mineserve.mobile.runtime

import com.mineserve.mobile.data.isProotGuestRuntime
import com.mineserve.mobile.data.JavaVendor
import com.mineserve.mobile.data.JavaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 孤儿进程匹配与来宾分派的守卫测试。
 *
 * ## 为什么这段逻辑值得单独测
 *
 * `killOrphanMcProcess` 靠 `/proc/<pid>/cmdline` 里的**路径子串**判断
 * 「这个 java 进程是不是某个 MC 服务端的孤儿」。两个方向出错都很难查：
 *
 * - **漏判**：容器里的孤儿 java 清理不掉，继续占着 `world/session.lock`，
 *   用户下次启动看到 `already locked`，而日志里那句「清理残留服务器进程」
 *   根本没出现过 —— 现象与原因隔得很远；
 * - **误判**：把**正常运行**的服务端当孤儿 SIGKILL 掉，表现为
 *   「启动中突然秒退且没有任何报错」，还会被上层当成崩溃并触发自动重启。
 *
 * 早期实现只匹配宿主 `serverDir` 绝对路径，在 PRoot 容器下必然漏判 ——
 * 这正是本测试存在的原因。
 */
class OrphanProcessMatchingTest {

    /**
     * 被测的是 [TermuxRuntime.matchesManagedServer] 的**真实实现**。
     *
     * 它当初是私有方法里的一段内联判断，只能靠设备实测覆盖；
     * 现在是 `internal` 顶层可测函数，且逻辑是纯字符串包含 ——
     * 不需要 Context、不需要设备、不需要 /proc。
     */
    private fun matches(cmdline: String, serverDirPath: String) =
        matchesManagedServer(cmdline, serverDirPath)

    @Test
    fun guestServerDirIsTheDocumentedMountPoint() {
        // 挂载点是整个方案的核心约定：`--bind=<宿主 serverDir>:/srv/mineserve`。
        // 一旦改名，容器内的世界/插件/配置路径全部对不上，孤儿匹配也会跟着失效。
        assertEquals(
            "来宾挂载点必须保持 /srv/mineserve",
            "/srv/mineserve",
            TermuxRuntime.GUEST_SERVER_DIR
        )
    }

    @Test
    fun hostPathCmdlineMatches() {
        val serverDir = "/data/user/0/com.mineserve.mobile/files/servers/survival"
        val cmdline = "/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/bin/java " +
            "-jar $serverDir/server.jar nogui"
        assertTrue("宿主 link 的 cmdline 必须匹配", matches(cmdline, serverDir))
    }

    @Test
    fun guestRelativeJarCmdlineMatches() {
        // 容器链路：java 在 `cd /srv/mineserve` 之后用**来宾相对/来宾绝对**路径启动，
        // cmdline 里完全不出现宿主 serverDir —— 这是旧实现漏判的典型形态。
        val serverDir = "/data/user/0/com.mineserve.mobile/files/servers/survival"
        val cmdline = "/usr/lib/jvm/java-17-openjdk-arm64/bin/java " +
            "-Djava.io.tmpdir=/tmp -Xmx2048m -jar /srv/mineserve/server.jar nogui"
        assertTrue(
            "容器内孤儿必须能被匹配，否则残留 session.lock 会导致 already locked",
            matches(cmdline, serverDir)
        )
    }

    @Test
    fun rootfsJavaPathCmdlineMatches() {
        // 容器里 java 的可执行路径位于 rootfs 内部（宿主绝对路径），同样要能识别
        val serverDir = "/data/user/0/com.mineserve.mobile/files/servers/creative"
        val cmdline = "/data/user/0/com.mineserve.mobile/files/var/lib/mineserve/focal/" +
            "usr/lib/jvm/java-8-openjdk-arm64/bin/java -jar /srv/mineserve/server.jar nogui"
        assertTrue(matches(cmdline, serverDir))
    }

    @Test
    fun guestMountPointAloneCannotDistinguishInstances() {
        // 这条把「已知局限」显式钉下来：`/srv/mineserve` 是全局固定挂载点，
        // 所以它**无法**区分是哪个服务端实例。跨实例归属只能靠
        // 进程树排除（descendantPids）与 mcServerDir 判断。
        // 如果哪天有人想「靠 cmdline 里的来宾路径区分实例」，这条会失败并提醒他不行。
        val otherServerDir = "/data/user/0/com.mineserve.mobile/files/servers/other"
        val cmdline = "/usr/lib/jvm/java-17-openjdk-arm64/bin/java " +
            "-jar /srv/mineserve/server.jar nogui"
        assertTrue(
            "来宾挂载点不区分实例 —— 跨实例归属必须靠进程树/mcServerDir",
            matches(cmdline, otherServerDir)
        )
    }

    @Test
    fun nonMinecraftJavaDoesNotMatch() {
        // 反向用例：既不含宿主目录也不会宾路径的 java（例如 MCP 工具自己起的）
        // 不该被当成 MC 孤儿清掉。
        val serverDir = "/data/user/0/com.mineserve.mobile/files/servers/survival"
        val cmdline = "/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/bin/java " +
            "-jar /data/user/0/com.mineserve.mobile/files/tmp/some-tool.jar"
        assertFalse(
            "无关 java 不能匹配，否则会误杀",
            matches(cmdline, serverDir)
        )
    }

    @Test
    fun dispatcherRoutesOnlyProotAndLegacyTermux8ToGuest() {
        // 分派谓词是「走容器还是走宿主」的唯一依据，必须锁住：
        assertTrue(isProotGuestRuntime(JavaVendor.Proot, JavaVersion.Java8))
        assertTrue(isProotGuestRuntime(JavaVendor.Proot, JavaVersion.Java11))
        assertTrue(isProotGuestRuntime(JavaVendor.Proot, JavaVersion.Java17))
        assertTrue(isProotGuestRuntime(JavaVendor.Proot, JavaVersion.Java21))
        // 历史遗留组合：Termux + Java 8 在 PRoot 通用化之前就走容器，不能退化
        assertTrue(isProotGuestRuntime(JavaVendor.Termux, JavaVersion.Java8))
        // 其余 Termux 版本必须走宿主，否则会把没有容器的环境当容器用
        assertFalse(isProotGuestRuntime(JavaVendor.Termux, JavaVersion.Java11))
        assertFalse(isProotGuestRuntime(JavaVendor.Termux, JavaVersion.Java17))
        assertFalse(isProotGuestRuntime(JavaVendor.Termux, JavaVersion.Java21))
        assertFalse(isProotGuestRuntime(JavaVendor.Termux, JavaVersion.Java25))
        // FCL 是 Android 原生 JRE，永远走宿主
        JavaVersion.entries.forEach { version ->
            assertFalse(
                "FCL.$version 不该走来宾链路",
                isProotGuestRuntime(JavaVendor.Fcl, version)
            )
        }
    }

    @Test
    fun everyVendorVersionPairHasADecidedRoute() {
        // 穷举所有 (来源 × 版本)：不允许出现未定义行为。
        // 这里不重复谓词内部实现，只断言「结果稳定且有明确归属」。
        JavaVendor.entries.forEach { vendor ->
            vendor.versions.forEach { version ->
                val guest = isProotGuestRuntime(vendor, version)
                val expected = when (vendor) {
                    JavaVendor.Proot -> true
                    JavaVendor.Termux -> version == JavaVersion.Java8
                    JavaVendor.Fcl -> false
                }
                assertEquals("$vendor.$version 的路由不符合预期", expected, guest)
            }
        }
    }
}

package com.mineserve.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住 PRoot 启动参数。
 *
 * ## 这个文件为什么重要
 *
 * `ProotCommandBuilder` 把 `TermuxRuntime.kt` 里 4 处手写的 proot 参数合并成了一个。
 * 合并最大的风险是**悄悄改变了某处产出的命令**——那种 bug 不报错，
 * 只表现为"用 PRoot 模式开服，服务器起不来"，而且只坏一种启动方式，
 * 测试时很容易漏掉。
 *
 * 所以这里每条 `expected` 都是**从改造前能跑通的代码里逐字抄下来的**，
 * 不是凭记忆写的。4 处各有一条，外加开关组合的独立验证。
 *
 * ## 如果测试红了怎么办
 *
 * 先判断：是不是**故意**改变命令？
 *  - 是（比如确实要加一个新参数）→ 同步改 `expected`，并在 commit 信息里说明为什么。
 *  - 不是 → 说明 `ProotCommandBuilder` 改坏了，去看 `build()`。
 *
 * **不要**为了让它变绿就把断言放宽成 `assertTrue(args.contains(...))`——
 * 那样这个测试就失去意义了。
 */
class ProotCommandBuilderTest {

    // -----------------------------------------------------------------------
    // 第 1 处：runUbuntu（TermuxRuntime.kt 原 1936 行附近）
    //
    // 特征：proot 用字面量 "proot"（走 PATH），参数用 --xxx=yyy 等号写法，
    //      kill-on-exit / sysvipc 受 tuning 控制，没有 TZ，shell 是 /bin/sh -c。
    // -----------------------------------------------------------------------
    @Test
    fun `runUbuntu 默认参数与改造前逐字一致`() {
        val spec = ProotCommandBuilder.Spec(
            prootPath = "proot",
            rootfs = "/data/user/0/com.mineserve.mobile/files/usr/var/lib/proot-distro/ubuntu",
            sharedMemoryPath = "/data/user/0/com.mineserve.mobile/files/usr/var/lib/proot-distro/ubuntu/tmp",
            resolvConfPath = "/data/user/0/com.mineserve.mobile/files/usr/var/lib/proot-distro/resolv.conf",
            workDir = "/root",
            killOnExit = true,
            sysvipc = true,
            extraBinds = emptyList(),
            includeDebianFrontend = true,
            includeTimeZone = false,
            shellMode = ProotCommandBuilder.ShellMode.COMMAND,
            command = "apt-get update",
            equalsStyle = true,
        )

        val expected = listOf(
            "proot",
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "-L",
            "--change-id=0:0",
            "--rootfs=/data/user/0/com.mineserve.mobile/files/usr/var/lib/proot-distro/ubuntu",
            "--cwd=/root",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/data/user/0/com.mineserve.mobile/files/usr/var/lib/proot-distro/ubuntu/tmp:/dev/shm",
            "--bind=/data/user/0/com.mineserve.mobile/files/usr/var/lib/proot-distro/resolv.conf:/etc/resolv.conf",
            "/usr/bin/env",
            "-i",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root",
            "TMPDIR=/tmp",
            "LANG=C.UTF-8",
            "DEBIAN_FRONTEND=noninteractive",
            "/bin/sh",
            "-c",
            "apt-get update",
        )

        assertEquals(expected, ProotCommandBuilder.build(spec, timeZoneId = null))
    }

    @Test
    fun `runUbuntu 关掉 killOnExit 和 sysvipc 时两个开关都不出现`() {
        val spec = baseRunUbuntu(killOnExit = false, sysvipc = false)
        val args = ProotCommandBuilder.build(spec, timeZoneId = null)

        assertTrue("不应该有 --kill-on-exit", "--kill-on-exit" !in args)
        assertTrue("不应该有 --sysvipc", "--sysvipc" !in args)
        // 但 --link2symlink 是无条件的，必须还在
        assertTrue("--link2symlink 应该始终存在", "--link2symlink" in args)
    }

    @Test
    fun `runUbuntu 的 extraBinds 按顺序追加在基础绑定之后`() {
        val spec = baseRunUbuntu().copy(extraBinds = listOf("/a:/b", "/c:/d"))
        val args = ProotCommandBuilder.build(spec, timeZoneId = null)

        val resolvIdx = args.indexOf("--bind=/resolv.conf:/etc/resolv.conf")
        val aIdx = args.indexOf("--bind=/a:/b")
        val cIdx = args.indexOf("--bind=/c:/d")
        assertTrue("resolv.conf 绑定应该在最前", resolvIdx in 0 until aIdx)
        assertTrue("extraBinds 应保持原顺序", aIdx < cIdx)
        // 环境变量段必须还在它们后面
        assertTrue("/usr/bin/env 应在所有 bind 之后", cIdx < args.indexOf("/usr/bin/env"))
    }

    // -----------------------------------------------------------------------
    // 第 2 处：startMcInProot（原 4019 行附近）
    //
    // 特征：proot 用绝对路径，-r/-w/-b 分开写，固定开 kill-on-exit + sysvipc，
    //      有 TZ、有 DEBIAN_FRONTEND，shell 是 /bin/sh -lc。
    // -----------------------------------------------------------------------
    @Test
    fun `startMcInProot 参数与改造前逐字一致`() {
        val spec = ProotCommandBuilder.Spec(
            prootPath = "/data/user/0/com.mineserve.mobile/files/usr/bin/proot",
            rootfs = "/data/user/0/com.mineserve.mobile/files/rootfs/ubuntu22",
            sharedMemoryPath = "/data/user/0/com.mineserve.mobile/cache/shm",
            resolvConfPath = "/data/user/0/com.mineserve.mobile/files/resolv.conf",
            workDir = "/root",
            killOnExit = true,
            sysvipc = true,
            extraBinds = listOf("/sdcard/Minexa/servers/survival:/srv/mineserve"),
            includeDebianFrontend = true,
            includeTimeZone = true,
            shellMode = ProotCommandBuilder.ShellMode.LOGIN,
            command = "cd /srv/mineserve && java -jar server.jar nogui",
            equalsStyle = false,
        )

        val expected = listOf(
            "/data/user/0/com.mineserve.mobile/files/usr/bin/proot",
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "-L",
            "--change-id=0:0",
            "-r",
            "/data/user/0/com.mineserve.mobile/files/rootfs/ubuntu22",
            "-w",
            "/root",
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-b",
            "/dev/urandom:/dev/random",
            "-b",
            "/data/user/0/com.mineserve.mobile/cache/shm:/dev/shm",
            "-b",
            "/data/user/0/com.mineserve.mobile/files/resolv.conf:/etc/resolv.conf",
            "-b",
            "/sdcard/Minexa/servers/survival:/srv/mineserve",
            "/usr/bin/env",
            "-i",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root",
            "TMPDIR=/tmp",
            "LANG=C.UTF-8",
            "DEBIAN_FRONTEND=noninteractive",
            "TZ=Asia/Shanghai",
            "/bin/sh",
            "-lc",
            "cd /srv/mineserve && java -jar server.jar nogui",
        )

        assertEquals(expected, ProotCommandBuilder.build(spec, timeZoneId = "Asia/Shanghai"))
    }

    @Test
    fun `没有时区信息时用 TZ=UTC 兜底`() {
        val spec = baseStartMc()
        val args = ProotCommandBuilder.build(spec, timeZoneId = null)
        assertTrue("应注入 TZ=UTC", "TZ=UTC" in args)
    }

    // -----------------------------------------------------------------------
    // 第 3 处：startBdsViaBox64（原 4991 行附近）
    //
    // 特征与 startMcInProot 相同，但多两条 bind，且 extraEnv 里插了 caEnv。
    // -----------------------------------------------------------------------
    @Test
    fun `startBdsViaBox64 参数与改造前逐字一致`() {
        val spec = ProotCommandBuilder.Spec(
            prootPath = "/data/user/0/com.mineserve.mobile/files/usr/bin/proot",
            rootfs = "/data/user/0/com.mineserve.mobile/files/rootfs/ubuntu22",
            sharedMemoryPath = "/data/user/0/com.mineserve.mobile/cache/shm",
            resolvConfPath = "/data/user/0/com.mineserve.mobile/files/resolv.conf",
            workDir = "/root",
            killOnExit = true,
            sysvipc = true,
            extraBinds = listOf(
                "/data/user/0/com.mineserve.mobile/files/bds/1.21.0:/opt/bds",
                "/sdcard/Minexa/servers/bds-home:/srv/mineserve",
            ),
            extraEnv = listOf(
                "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt",
                "CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt",
            ),
            includeDebianFrontend = true,
            includeTimeZone = true,
            shellMode = ProotCommandBuilder.ShellMode.LOGIN,
            command = "cd /srv/mineserve && box64 bedrock_server",
            equalsStyle = false,
        )

        val expected = listOf(
            "/data/user/0/com.mineserve.mobile/files/usr/bin/proot",
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "-L",
            "--change-id=0:0",
            "-r",
            "/data/user/0/com.mineserve.mobile/files/rootfs/ubuntu22",
            "-w",
            "/root",
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-b",
            "/dev/urandom:/dev/random",
            "-b",
            "/data/user/0/com.mineserve.mobile/cache/shm:/dev/shm",
            "-b",
            "/data/user/0/com.mineserve.mobile/files/resolv.conf:/etc/resolv.conf",
            "-b",
            "/data/user/0/com.mineserve.mobile/files/bds/1.21.0:/opt/bds",
            "-b",
            "/sdcard/Minexa/servers/bds-home:/srv/mineserve",
            "/usr/bin/env",
            "-i",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root",
            "TMPDIR=/tmp",
            "LANG=C.UTF-8",
            "DEBIAN_FRONTEND=noninteractive",
            "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt",
            "CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt",
            "TZ=Asia/Shanghai",
            "/bin/sh",
            "-lc",
            "cd /srv/mineserve && box64 bedrock_server",
        )

        assertEquals(expected, ProotCommandBuilder.build(spec, timeZoneId = "Asia/Shanghai"))
    }

    // -----------------------------------------------------------------------
    // 第 4 处：probeBdsNetwork（原 5121 行附近）
    //
    // 特征：没有 DEBIAN_FRONTEND，没有 TZ，shell 是 /bin/sh -lc。
    //       bind 数量是根据 dirName 动态决定的，所以这条测试里用"带 dirName"的情形。
    // -----------------------------------------------------------------------
    @Test
    fun `probeBdsNetwork 参数与改造前逐字一致`() {
        val spec = ProotCommandBuilder.Spec(
            prootPath = "/data/user/0/com.mineserve.mobile/files/usr/bin/proot",
            rootfs = "/data/user/0/com.mineserve.mobile/files/rootfs/ubuntu22",
            sharedMemoryPath = "/data/user/0/com.mineserve.mobile/cache/shm",
            resolvConfPath = "/data/user/0/com.mineserve.mobile/files/resolv.conf",
            workDir = "/root",
            killOnExit = true,
            sysvipc = true,
            extraBinds = listOf("/sdcard/Minexa/servers/survival:/srv/mineserve"),
            includeDebianFrontend = false,
            includeTimeZone = false,
            shellMode = ProotCommandBuilder.ShellMode.LOGIN,
            command = "getent hosts example.com",
            equalsStyle = false,
        )

        val expected = listOf(
            "/data/user/0/com.mineserve.mobile/files/usr/bin/proot",
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "-L",
            "--change-id=0:0",
            "-r",
            "/data/user/0/com.mineserve.mobile/files/rootfs/ubuntu22",
            "-w",
            "/root",
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
            "-b",
            "/dev/urandom:/dev/random",
            "-b",
            "/data/user/0/com.mineserve.mobile/cache/shm:/dev/shm",
            "-b",
            "/data/user/0/com.mineserve.mobile/files/resolv.conf:/etc/resolv.conf",
            "-b",
            "/sdcard/Minexa/servers/survival:/srv/mineserve",
            "/usr/bin/env",
            "-i",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root",
            "TMPDIR=/tmp",
            "LANG=C.UTF-8",
            "/bin/sh",
            "-lc",
            "getent hosts example.com",
        )

        assertEquals(expected, ProotCommandBuilder.build(spec, timeZoneId = "Asia/Shanghai"))
    }

    @Test
    fun `probeBdsNetwork 没有额外绑定时不追加 -b`() {
        val spec = specFor(ProotCommandBuilder.ShellMode.LOGIN, equalsStyle = false).copy(
            includeDebianFrontend = false,
            includeTimeZone = false,
            extraBinds = emptyList(),
        )
        val args = ProotCommandBuilder.build(spec, timeZoneId = null)
        // 基础绑定 6 条，正好 6 个 -b
        assertEquals(6, args.count { it == "-b" })
    }

    // -----------------------------------------------------------------------
    // 跨用例的一致性检查
    // -----------------------------------------------------------------------

    @Test
    fun `四处共有的开关每一条都还在`() {
        val specs = listOf(
            baseRunUbuntu(killOnExit = true, sysvipc = true),
            baseStartMc(),
            baseStartMc().copy(extraBinds = listOf("/x:/y"), extraEnv = listOf("K=V")),
            baseStartMc().copy(includeDebianFrontend = false, includeTimeZone = false),
        )
        val mustHave = listOf("--link2symlink", "-L", "--change-id=0:0", "/usr/bin/env", "-i")
        for (spec in specs) {
            val args = ProotCommandBuilder.build(spec, timeZoneId = "Asia/Shanghai")
            for (token in mustHave) {
                assertTrue("缺少必备参数 $token（shell=${spec.shellMode}, equals=${spec.equalsStyle}）", token in args)
            }
            // 命令体必须在最后
            assertEquals("命令体应该是最后一个参数", spec.command, args.last())
        }
    }

    @Test
    fun `equlasStyle 与分开写产出的语义相同`() {
        // 用两种写法描述同一个容器，比对关键信息是否一致（不要求逐字相同）
        val equals = specFor(ProotCommandBuilder.ShellMode.LOGIN, equalsStyle = true)
        val spaced = specFor(ProotCommandBuilder.ShellMode.LOGIN, equalsStyle = false)

        val a = ProotCommandBuilder.build(equals, timeZoneId = null)
        val b = ProotCommandBuilder.build(spaced, timeZoneId = null)

        assertTrue("等号写法里应有 --rootfs=", a.any { it.startsWith("--rootfs=") })
        assertTrue("分开写法里应有 -r", b.contains("-r"))
        assertEquals("rootfs 值应一致", a.first { it.startsWith("--rootfs=") }.removePrefix("--rootfs="), b[b.indexOf("-r") + 1])
        assertEquals("绑定条数应一致", a.count { it.startsWith("--bind=") }, b.count { it == "-b" })
    }

    @Test
    fun `resolveProot 找不到可执行文件时抛出与原逻辑相同的异常`() {
        val tmpDir = java.nio.file.Files.createTempDirectory("proot-test").toFile()
        try {
            val thrown = runCatching { ProotCommandBuilder.resolveProot(tmpDir) }.exceptionOrNull()
            assertTrue("应抛出异常", thrown != null)
            assertEquals(
                "异常信息必须与原代码一致，否则上层按 message 判断的逻辑会失效",
                "proot is not available",
                thrown!!.message,
            )
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `resolveProot 优先用 bin 下的 再用 usr bin 下的`() {
        val tmpDir = java.nio.file.Files.createTempDirectory("proot-test2").toFile()
        try {
            val usrBin = java.io.File(tmpDir, "usr/bin").apply { mkdirs() }
            val usrProot = java.io.File(usrBin, "proot").apply {
                writeText("#!/bin/sh\n"); setExecutable(true)
            }
            assertEquals("只有 usr/bin 时应找到它", usrProot.absolutePath, ProotCommandBuilder.resolveProot(tmpDir).absolutePath)

            val bin = java.io.File(tmpDir, "bin").apply { mkdirs() }
            val binProot = java.io.File(bin, "proot").apply {
                writeText("#!/bin/sh\n"); setExecutable(true)
            }
            assertEquals("两个都有时应优先 bin", binProot.absolutePath, ProotCommandBuilder.resolveProot(tmpDir).absolutePath)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `toShellScript 保留原来的前缀与引号逻辑`() {
        val args = listOf("/path/proot", "--kill-on-exit", "a b")
        val quote = { s: String -> "'" + s.replace("'", "'\\''") + "'" }
        val script = ProotCommandBuilder.toShellScript(args, "/tmp/xyz", quote)

        assertEquals(
            "export PROOT_TMP_DIR='/tmp/xyz'; export TMPDIR='/tmp/xyz'; exec " +
                "'/path/proot' '--kill-on-exit' 'a b'",
            script,
        )
    }

    // -----------------------------------------------------------------------
    // 造数据的小工具
    // -----------------------------------------------------------------------

    private fun baseRunUbuntu(killOnExit: Boolean = true, sysvipc: Boolean = true) =
        ProotCommandBuilder.Spec(
            prootPath = "proot",
            rootfs = "/rootfs",
            sharedMemoryPath = "/rootfs/tmp",
            resolvConfPath = "/resolv.conf",
            workDir = "/root",
            killOnExit = killOnExit,
            sysvipc = sysvipc,
            includeDebianFrontend = true,
            includeTimeZone = false,
            shellMode = ProotCommandBuilder.ShellMode.COMMAND,
            command = "true",
            equalsStyle = true,
        )

    private fun baseStartMc() =
        ProotCommandBuilder.Spec(
            prootPath = "/abs/proot",
            rootfs = "/rootfs",
            sharedMemoryPath = "/shm",
            resolvConfPath = "/resolv.conf",
            workDir = "/root",
            killOnExit = true,
            sysvipc = true,
            includeDebianFrontend = true,
            includeTimeZone = true,
            shellMode = ProotCommandBuilder.ShellMode.LOGIN,
            command = "java -jar server.jar",
            equalsStyle = false,
        )

    private fun specFor(shell: ProotCommandBuilder.ShellMode, equalsStyle: Boolean) =
        ProotCommandBuilder.Spec(
            prootPath = "/abs/proot",
            rootfs = "/rootfs",
            sharedMemoryPath = "/shm",
            resolvConfPath = "/resolv.conf",
            workDir = "/root",
            killOnExit = true,
            sysvipc = true,
            includeDebianFrontend = true,
            includeTimeZone = true,
            shellMode = shell,
            command = "x",
            equalsStyle = equalsStyle,
        )
}

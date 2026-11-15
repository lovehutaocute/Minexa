package com.mineserve.mobile.runtime

/**
 * 统一构建 PRoot 启动参数。
 *
 * ## 为什么需要它
 *
 * `TermuxRuntime.kt` 里有 4 处各自手写了几乎相同的 proot 参数串，分散在：
 *  - `runUbuntu`        —— 在 Ubuntu 容器里跑一次性命令
 *  - `startMcInProot`   —— 用 PRoot 启动 Java 版服务端
 *  - `startBdsViaBox64` —— 用 box64 + PRoot 启动基岩版服务端
 *  - `probeBdsNetwork`  —— 自检：在容器里探网络通不通
 *
 * 以前要加/改一个全局参数（比如某天要给所有 PRoot 调用加 `--kill-on-exit` 之外的
 * 新开关），得记着改 4 个地方；漏一个就变成"只有某种启动方式坏掉"，而且不报错，
 * 只表现为"服务器起不来"，排查成本很高。
 *
 * ## 设计原则：逐字兼容
 *
 * 这个类**不追求把 4 处写成一样**，而是追求"改完之后 4 处产出的参数列表
 * 与改之前一模一样的字节"。因为差异是真实存在的：
 *
 * | 差异点            | runUbuntu        | startMcInProot | startBdsViaBox64 | probeBdsNetwork |
 * |-------------------|------------------|----------------|------------------|-----------------|
 * | proot 可执行路径  | 字面量 `"proot"` | 绝对路径       | 绝对路径         | 绝对路径        |
 * | --kill-on-exit    | 看 tuning 开关   | 固定开          | 固定开           | 固定开          |
 * | --sysvipc         | 看 tuning 开关   | 固定开          | 固定开           | 固定开          |
 * | 参数写法          | `--rootfs=x`     | `-r`/分开写    | `-r`/分开写      | `-r`/分开写     |
 * | 时区变量 TZ       | 无               | 有             | 有               | 无              |
 * | 额外环境变量      | 无               | 无             | caEnv…           | 无              |
 * | 收尾 shell        | `/bin/sh -c`     | `/bin/sh -lc`  | `/bin/sh -lc`    | `/bin/sh -lc`   |
 *
 * 所以这里用 [Spec] 把这些差异全部显式参数化，调用方只声明"我这次是什么形态"，
 * 具体怎么拼交给这里。以后再加全局参数，只改这一个文件。
 *
 * 配套测试见 `app/src/test/java/com/mineserve/mobile/runtime/ProotCommandBuilderTest.kt`，
 * 那个文件里每条 `expected` 都是从**改造前能跑通的代码**里逐字录下来的，
 * 不是凭记忆写的。改动本类时如果测试红了，先确认是不是有意改变了命令。
 */
object ProotCommandBuilder {

    /** 命令末尾那段 `/usr/bin/env -i ...` 之后要执行的 shell 形态 */
    enum class ShellMode(
        /** `/bin/sh -c <command>`：一次性命令，命令体本身已是一个完整脚本 */
        val executable: String,
        val flag: String,
    ) {
        /** -c：不做登录 shell 初始化，快但 PATH 等要自己给全（runUbuntu 用） */
        COMMAND("/bin/sh", "-c"),

        /** -lc：登录 shell，会读 /etc/profile 之类，服务端脚本依赖它 */
        LOGIN("/bin/sh", "-lc"),
    }

    /**
     * 一次 PRoot 调用的完整描述。
     *
     * 所有字段都必须显式给值——不给默认值是有意的：
     * 这几处历史上就不一样，默认值会让人"以为一样"，反而是漏改的温床。
     */
    data class Spec(
        /** proot 可执行文件的路径。传给进程的参数值；runUbuntu 这里历史上就是字面量 "proot" */
        val prootPath: String,
        /** rootfs 根目录的绝对路径 */
        val rootfs: String,
        /** 临时 shm 目录（映射到容器内 /dev/shm） */
        val sharedMemoryPath: String,
        /** 容器内 /etc/resolv.conf 对应的宿主文件 */
        val resolvConfPath: String,
        /** 容器内工作目录，历史上统一是 /root */
        val workDir: String = "/root",
        /** 是否加 --kill-on-exit。runUbuntu 由 tuning 决定，其余固定 true */
        val killOnExit: Boolean,
        /** 是否加 --sysvipc。runUbuntu 由 tuning 决定，其余固定 true */
        val sysvipc: Boolean,
        /** 额外的 `--bind=host:guest` 绑定（按顺序追加在基础绑定之后） */
        val extraBinds: List<String> = emptyList(),
        /** 额外的环境变量（如 caEnv），插在标准环境变量之后、TZ 之前 */
        val extraEnv: List<String> = emptyList(),
        /**
         * 是否注入 DEBIAN_FRONTEND=noninteractive。
         * runUbuntu / startMcInProot / startBdsViaBox64 为 true，probeBdsNetwork 为 false。
         */
        val includeDebianFrontend: Boolean,
        /** 是否注入 TZ。runUbuntu 历史上没有，其余三处有 */
        val includeTimeZone: Boolean,
        /** 收尾 shell 形态 */
        val shellMode: ShellMode,
        /** 要执行的命令体 / 脚本 */
        val command: String,
        /** 参数写法：true 用 `--rootfs=x` / `--bind=a:b`，false 用 `-r x` / `-b a:b` */
        val equalsStyle: Boolean,
    )

    /** `/usr/bin/env -i` 之后固定的标准环境变量，4 处完全一致 */
    private val BASE_ENV = listOf(
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME=/root",
        "TMPDIR=/tmp",
        "LANG=C.UTF-8",
    )

    /** 带 DEBIAN_FRONTEND 的版本（runUbuntu / startMcInProot / startBdsViaBox64 用） */
    private const val DEBIAN_FRONTEND = "DEBIAN_FRONTEND=noninteractive"

    /**
     * 构建完整的参数列表。
     *
     * 返回值可直接交给 `ProcessBuilder`（逐一 shellQuote 后拼串）或
     * `execOnceWithTimeoutCapture`（直接展开成数组）。
     */
    fun build(spec: Spec, timeZoneId: String?): List<String> {
        val args = mutableListOf<String>()
        args += spec.prootPath

        // —— 通用开关 ——
        // 注意顺序：kill-on-exit 在 link2symlink 之前，sysvipc 在 link2symlink 之后。
        // 这是原代码的顺序，proot 本身对顺序不敏感，但为了"逐字一致"必须保持。
        if (spec.killOnExit) args += "--kill-on-exit"
        args += "--link2symlink"
        if (spec.sysvipc) args += "--sysvipc"
        args += "-L"
        args += "--change-id=0:0"

        // —— rootfs 与工作目录 ——
        if (spec.equalsStyle) {
            args += "--rootfs=${spec.rootfs}"
            args += "--cwd=${spec.workDir}"
        } else {
            args += "-r"
            args += spec.rootfs
            args += "-w"
            args += spec.workDir
        }

        // —— 基础绑定：容器正常运行所必需的那几条 ——
        val baseBinds = listOf(
            "/dev",
            "/proc",
            "/sys",
            "/dev/urandom:/dev/random",
            "${spec.sharedMemoryPath}:/dev/shm",
            "${spec.resolvConfPath}:/etc/resolv.conf",
        )
        for (bind in baseBinds + spec.extraBinds) {
            if (spec.equalsStyle) {
                args += "--bind=$bind"
            } else {
                args += "-b"
                args += bind
            }
        }

        // —— 环境变量注入 ——
        args += "/usr/bin/env"
        args += "-i"
        args += BASE_ENV

        // DEBIAN_FRONTEND 只有 runUbuntu 那处历史上有；其余两处也有，
        // probeBdsNetwork 没有。用 extraEnv 无法表达"要不要 DEBIAN_FRONTEND"，
        // 所以这里用一个显式开关。
        if (spec.includeDebianFrontend) args += DEBIAN_FRONTEND

        args += spec.extraEnv

        if (spec.includeTimeZone) {
            // Android 无 /etc/localtime，容器内需要显式给时区
            args += (timeZoneId?.let { "TZ=$it" } ?: "TZ=UTC")
        }

        // —— 收尾：shell 形态 + 命令体 ——
        args += spec.shellMode.executable
        args += spec.shellMode.flag
        args += spec.command

        return args
    }

    /** 取一个已存在的 proot 可执行文件；找不到就抛（原 4 处的统一行为） */
    fun resolveProot(rootDir: java.io.File): java.io.File =
        listOf(
            java.io.File(rootDir, "bin/proot"),
            java.io.File(rootDir, "usr/bin/proot"),
        ).firstOrNull { it.isFile && it.canExecute() }
            ?: throw RuntimeException("proot is not available")

    /**
     * 拼成 `/system/bin/sh -c` 用的字符串。
     *
     * 与原来 3 处 `ProcessBuilder("/system/bin/sh", "-c", ...)` 的写法保持一致：
     * 前面两个 export 是给 proot 自己找临时目录用的，必须在 exec 之前生效。
     */
    fun toShellScript(args: List<String>, prootTmpDir: String, quote: (String) -> String): String =
        "export PROOT_TMP_DIR='$prootTmpDir'; " +
            "export TMPDIR='$prootTmpDir'; exec " +
            args.joinToString(" ") { quote(it) }
}

package com.mineserve.mobile.mcp

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.mineserve.mobile.BuildConfig
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.data.AptMirror
import com.mineserve.mobile.data.AutoBackupType
import com.mineserve.mobile.data.DownloadMirror
import com.mineserve.mobile.data.JavaRuntime
import com.mineserve.mobile.data.JavaVendor
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.JvmFlavor
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.PhpVersion
import com.mineserve.mobile.data.RuntimeKind
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.data.StepStatus
import com.mineserve.mobile.server.BackupManager
import com.mineserve.mobile.server.McServerController
import com.mineserve.mobile.server.PluginManager
import com.mineserve.mobile.server.Worlds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP 的「开发/测试」工具集：运行环境与依赖管理、核心安装、应用配置读写、
 * 快照、插件、日志与设备诊断、以及给自动化测试用的等待/探针工具。
 *
 * 与 [McpServerManager] 的分工：后者保留原有的服务器/文件/模组工具与 HTTP 传输，
 * 这里只负责新增的这一批。`call` 返回 null 表示「不是本类负责的工具名」，
 * 由调用方继续兜底，避免两处维护同一份名字清单。
 *
 * 约定：
 *  - 工具描述（tools/list 可见的部分）在 [McpToolCatalog]；
 *  - 结果文本用英文，与既有工具保持一致；
 *  - 任何可能跑上几分钟的操作都走 [McpJobRegistry]，不在 HTTP 线程里同步等；
 *  - 破坏性操作要求显式 `confirm=true`。
 */
internal class McpToolRunner(
    private val app: McApplication,
    private val repo: ServerRepository,
    private val controller: McServerController,
    private val pluginManager: PluginManager,
    private val jobs: McpJobRegistry
) {
    private val termux get() = repo.termuxRuntime
    private val backups = BackupManager(repo.termuxRuntime)

    /** 返回 null 表示该工具名不由本类处理 */
    suspend fun call(name: String, args: JsonObject?): McpToolResult? = when (name) {
        // 运行环境与依赖
        "get_runtime_info" -> runtimeInfo()
        "install_java" -> installJava(args)
        "remove_java" -> removeJava(args)
        "reinstall_java" -> reinstallJava(args)
        "install_php" -> installPhp(args)
        "remove_php" -> removePhp(args)
        "install_dependencies" -> installDependencies(args)
        "remove_dependencies" -> removeDependencies(args)
        "install_apt_package" -> installAptPackage(args)
        "repair_runtime" -> repairRuntime(args)
        "check_command" -> checkCommand(args)
        "set_download_mirror" -> setDownloadMirror(args)
        "set_apt_mirror" -> setAptMirror(args)
        // 核心与实例
        "list_core_versions" -> listCoreVersions(args)
        "create_server" -> createServer(args)
        "delete_server" -> deleteServer(args)
        // 应用配置
        "get_config" -> getConfig()
        "set_config" -> setConfig(args)
        // 快照
        "list_snapshots" -> listSnapshots()
        "create_snapshot" -> createSnapshot(args)
        "restore_snapshot" -> restoreSnapshot(args)
        "delete_snapshot" -> deleteSnapshot(args)
        // 插件
        "list_plugins" -> listPlugins(args)
        "install_plugin" -> installPlugin(args)
        "toggle_plugin" -> togglePlugin(args)
        "delete_plugin" -> deletePlugin(args)
        // 日志与诊断
        "get_app_log" -> appLog(args)
        "clear_app_log" -> clearAppLog()
        "get_last_run_log" -> lastRunLog(args)
        "get_device_info" -> deviceInfo()
        "check_port" -> checkPort(args)
        "http_probe" -> httpProbe(args)
        // 自动化测试辅助
        "wait_for_server" -> waitForServer(args)
        "wait_for_log" -> waitForLog(args)
        "file_hash" -> fileHash(args)
        // 后台任务
        "get_job" -> getJob(args)
        "list_jobs" -> listJobs()
        else -> null
    }

    // ══════════════════════════════════════════════════════════
    // 运行环境与依赖
    // ══════════════════════════════════════════════════════════

    private fun runtimeInfo(): McpToolResult {
        val config = repo.currentConfig()
        val installedRuntimes = termux.installedJavaRuntimes()
        val installedPhp = PhpVersion.entries.filter { termux.isPhpInstalled(it) }
        val deps = termux.installedDependencySteps()
        val doneDeps = deps.count { it.status == StepStatus.Done }
        val json = buildJsonObject {
            put("appVersion", BuildConfig.VERSION_NAME)
            put("bootstrapReady", termux.isReady())
            put("serverRunning", termux.isMcRunning())
            put("rootDir", termux.installer.rootDir.absolutePath)
            put("serversDir", termux.serversDir.absolutePath)
            put("selectedJavaVendor", config.selectedJavaVendor.name)
            put("selectedJavaVersion", config.selectedJavaVersion.name)
            put("selectedPhpVersion", config.selectedPhpVersion.name)
            put("downloadMirror", config.downloadMirror.name)
            put("aptMirror", config.aptMirror.name)
            // 键是 "来源.版本"（如 Fcl.Java21）：两套 JDK 的版本号大量重合，
            // 只按版本号列会在 JSON 里互相覆盖。
            put("java", buildJsonObject {
                JavaVendor.entries.forEach { vendor ->
                    vendor.versions.forEach { v ->
                        val runtime = JavaRuntime(vendor, v)
                        val (usable, detail) = termux.javaRuntimeDiagnostic(v, vendor)
                        put("${vendor.name}.${v.name}", buildJsonObject {
                            put("vendor", vendor.name)
                            put("version", v.name)
                            put("installed", runtime in installedRuntimes)
                            put("usable", usable)
                            put("detail", detail)
                        })
                    }
                }
            })
            put("phpInstalled", buildJsonArray { installedPhp.forEach { add(JsonPrimitive(it.name)) } })
            put("dependencies", buildJsonArray {
                deps.forEach { state ->
                    add(buildJsonObject {
                        put("step", state.step.name)
                        put("label", state.step.label)
                        put("status", state.status.name)
                        put("done", state.status == StepStatus.Done)
                    })
                }
            })
            put("commands", buildJsonObject {
                listOf("bash", "java", "wget", "frpc", "rclone", "proot", "apt-get").forEach { cmd ->
                    put(cmd, termux.isCommandInstalled(cmd))
                }
            })
            val stat = runCatching { StatFs(termux.installer.rootDir.absolutePath) }.getOrNull()
            if (stat != null) {
                put("runtimeDiskFreeMb", stat.availableBytes / MIB)
                put("runtimeDiskTotalMb", stat.totalBytes / MIB)
            }
        }
        val summary = "bootstrap=" + (if (termux.isReady()) "ready" else "NOT ready") +
            " java=" + (
                installedRuntimes.joinToString(",") { "${it.vendor.name}.${it.version.name}" }
                    .ifEmpty { "none" }
                ) +
            " php=" + (installedPhp.joinToString(",") { it.name }.ifEmpty { "none" }) +
            " dependencies=$doneDeps/${deps.size}"
        return McpToolResult(summary + "\n" + json)
    }

    private suspend fun installJava(args: JsonObject?): McpToolResult {
        val vendor = parseJavaVendor(args) ?: return bad(
            "Unknown vendor. Use one of: " + JavaVendor.entries.joinToString(", ") { it.name }
        )
        // Termux 那套装在 Termux 前缀里，必须先有环境；
        // FCL 是 Android 原生二进制，与 Termux 前缀无关，环境没装好也能下。
        if (vendor == JavaVendor.Termux && !termux.isReady()) return notReady()
        // 与 install_dependencies 同规则：共用一个 rootfs，实测会把正在跑的 MC 进程杀掉
        if (termux.isMcRunning()) return serverBusyError("installing a Java runtime")
        val version = parseJavaVersion(McpArgs.string(args, "version"), vendor)
            ?: return bad("Missing/invalid argument: version. Use one of: " + javaVersionNames(vendor))
        return runJob(args, "install_java:${vendor.name}.${version.name}") { job ->
            job.log("Installing ${vendor.logName} ${version.displayName}; this usually takes a few minutes on first run...")
            if (!termux.installJava(version, vendor)) {
                dumpAppLog(job)
                throw IllegalStateException("${vendor.logName} ${version.displayName} installation reported failure")
            }
            // 与 UI 一致：Java 8 走独立安装器、FCL 那套默认不在选用列表里，装完都直接设为所选运行时
            if (vendor == JavaVendor.Fcl || version == JavaVersion.Java8) {
                repo.updateAndSaveConfig {
                    it.copy(selectedJavaVendor = vendor, selectedJavaVersion = version)
                }
            }
            val (usable, detail) = termux.javaRuntimeDiagnostic(version, vendor)
            if (!usable) dumpAppLog(job)
            "${vendor.name}.${version.name} installed (usable=$usable, detail=$detail)"
        }
    }

    private suspend fun removeJava(args: JsonObject?): McpToolResult {
        val vendor = parseJavaVendor(args) ?: return bad(
            "Unknown vendor. Use one of: " + JavaVendor.entries.joinToString(", ") { it.name }
        )
        val version = parseJavaVersion(McpArgs.string(args, "version"), vendor)
            ?: return bad("Missing/invalid argument: version. Use one of: " + javaVersionNames(vendor))
        val label = "${vendor.logName} ${version.displayName}"
        requireConfirm(args, "remove $label")?.let { return it }
        if (termux.isMcRunning()) return bad("The server is running; stop it before removing a Java runtime.")
        if (!termux.isJavaInstalled(version, vendor)) {
            return McpToolResult("$label is not installed; nothing to remove.")
        }
        val ok = termux.deleteJava(version, vendor)
        return McpToolResult(
            if (ok) "Removed $label." else "Failed to remove $label; it is still present.",
            isError = !ok
        )
    }

    /**
     * 重装修复。
     *
     * Termux 来源走 [TermuxRuntime.clearAndReinstallJava]（真正的修复路径：wrapper、JVM 目录一起重建）；
     * FCL 来源没有 wrapper 这回事，按 (来源, 版本) 删掉再重下即可。
     */
    private suspend fun reinstallJava(args: JsonObject?): McpToolResult {
        val config0 = repo.currentConfig()
        val vendor = parseJavaVendor(args, config0.selectedJavaVendor) ?: return bad(
            "Unknown vendor. Use one of: " + JavaVendor.entries.joinToString(", ") { it.name }
        )
        if (termux.isMcRunning()) return bad("The server is running; stop it before reinstalling the Java runtime.")
        val version = parseJavaVersion(McpArgs.string(args, "version"), vendor)
            ?: config0.selectedJavaVersion.takeIf { it in vendor.versions }
            ?: vendor.versions.first()
        if (vendor == JavaVendor.Termux) {
            if (!termux.isReady()) return notReady()
            return runJob(args, "reinstall_java:Termux") { job ->
                job.log("Clearing and reinstalling the Termux Java runtime (repair path for a broken wrapper)...")
                if (!termux.clearAndReinstallJava()) {
                    dumpAppLog(job)
                    throw IllegalStateException("clearAndReinstallJava reported failure")
                }
                val (usable, detail) = termux.javaRuntimeDiagnostic(version, vendor)
                "reinstalled; ${vendor.name}.${version.name} usable=$usable detail=$detail"
            }
        }
        return runJob(args, "reinstall_java:${vendor.name}.${version.name}") { job ->
            job.log("Re-downloading ${vendor.logName} ${version.displayName}...")
            termux.deleteJava(version, vendor)
            if (!termux.installJava(version, vendor)) {
                dumpAppLog(job)
                throw IllegalStateException("${vendor.logName} ${version.displayName} install reported failure")
            }
            val (usable, detail) = termux.javaRuntimeDiagnostic(version, vendor)
            if (!usable) dumpAppLog(job)
            "reinstalled; ${vendor.name}.${version.name} usable=$usable detail=$detail"
        }
    }

    private suspend fun installPhp(args: JsonObject?): McpToolResult {
        if (!termux.isReady()) return notReady()
        val version = parsePhpVersion(McpArgs.string(args, "version"))
            ?: return bad("Invalid argument: version. Use one of: " + PhpVersion.entries.joinToString(", ") { it.name })
        return runJob(args, "install_php:${version.name}") { job ->
            job.log("Installing ${version.displayName} (${version.tarballAsset})...")
            val ok = termux.installPhp(version) { line -> job.log(line) }
            if (!ok) {
                dumpAppLog(job)
                throw IllegalStateException("${version.displayName} installation reported failure")
            }
            "${version.name} installed at ${termux.phpRuntimeDirFor(version).absolutePath}"
        }
    }

    private suspend fun removePhp(args: JsonObject?): McpToolResult {
        val version = parsePhpVersion(McpArgs.string(args, "version"))
            ?: return bad("Invalid argument: version. Use one of: " + PhpVersion.entries.joinToString(", ") { it.name })
        if (termux.isMcRunning()) return bad("The server is running; stop it before removing a PHP runtime.")
        if (!termux.isPhpInstalled(version)) return McpToolResult("${version.displayName} is not installed; nothing to remove.")
        val ok = termux.deletePhp(version)
        return McpToolResult(
            if (ok) "Removed ${version.displayName}." else "Failed to remove ${version.displayName}; it is still present.",
            isError = !ok
        )
    }

    private suspend fun installDependencies(args: JsonObject?): McpToolResult {
        if (!termux.isReady()) return notReady()
        // 服务端运行中一律拒绝：apt 要在同一个 rootfs 里跑重活，实测会把正在跑的 MC 进程
        // SIGKILL 掉（应用日志 `[crash] 检测到异常退出(exit=137)`，startupPhase 变 Failed），
        // 而 AI 只会看到 job succeeded，完全不知道服务端已经倒了。与 remove_dependencies 同规则。
        if (termux.isMcRunning()) {
            return bad(
                "The server is running; stop it before installing dependencies " +
                    "(apt runs in the same rootfs and has been observed to kill the MC process with exit=137)."
            )
        }
        return runJob(args, "install_dependencies") { job ->
            job.log("Installing runtime dependencies (wget / fonts / frp / rclone / proot) via apt...")
            val ok = controller.installDependencies()
            if (!ok) {
                dumpAppLog(job)
                throw IllegalStateException("installDependencies reported failure; check apt mirror with set_apt_mirror")
            }
            termux.installedDependencySteps().joinToString(", ") { "${it.step.name}=${it.status.name}" }
        }
    }

    private suspend fun removeDependencies(args: JsonObject?): McpToolResult {
        requireConfirm(args, "remove the installed Termux dependencies (wget / frp / rclone / proot)")?.let { return it }
        if (termux.isMcRunning()) return bad("The server is running; stop it before removing dependencies.")
        val removed = termux.deleteDependencies()
        return McpToolResult("Dependency removal finished ($removed entries touched). Verify with get_runtime_info.")
    }

    private suspend fun installAptPackage(args: JsonObject?): McpToolResult {
        if (!termux.isReady()) return notReady()
        if (termux.isMcRunning()) return serverBusyError("installing apt packages")
        val packages = McpArgs.stringList(args, "packages")
        if (packages.isEmpty()) return bad("Missing required argument: packages (string or array of apt package names)")
        val invalid = packages.firstOrNull { !APT_PACKAGE_RE.matches(it) }
        if (invalid != null) {
            // 这些名字会被拼进 /system/bin/sh -c 的命令行：不含空格的参数不做引号包裹，
            // 所以 `pkg;id` 这种没有空格的注入串会直接生效。必须在入口按字符白名单挡住。
            return bad("Invalid package name '$invalid'. Allowed: letters, digits, '.', '+', '-', '_' (max 64 chars).")
        }
        return runJob(args, "install_apt_package:${packages.joinToString("+")}") { job ->
            job.log("apt: preparing ${packages.joinToString(" ")}")
            if (!termux.prepareAptPackages(*packages.toTypedArray())) {
                throw IllegalStateException("apt update failed for ${packages.joinToString(" ")}; try set_apt_mirror or repair_runtime {\"target\":\"apt\"}")
            }
            val code = termux.execOnce(
                "apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y",
                *packages.toTypedArray()
            )
            if (code != 0) throw IllegalStateException("apt-get install failed (exit=$code)")
            termux.repairInstalledCommands()
            "installed ${packages.joinToString(", ")}"
        }
    }

    private suspend fun repairRuntime(args: JsonObject?): McpToolResult {
        if (!termux.isReady()) return notReady()
        val target = McpArgs.string(args, "target")?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: "auto"
        if (target !in REPAIR_TARGETS) {
            return bad("Unknown target '$target'. Use one of: " + REPAIR_TARGETS.joinToString(", "))
        }
        // auto / apt / jvm / rootfs 这几个目标都要在 rootfs 里重装或改写文件，
        // 与「运行中不能装依赖」是同一条理由。
        if (target in REPAIR_TARGETS_TOUCHING_ROOTFS && termux.isMcRunning()) {
            return serverBusyError("repairing the runtime (target=$target)")
        }
        return runJob(args, "repair_runtime:$target") { job ->
            val config = repo.currentConfig()
            job.log("repair target=$target")
            when (target) {
                "refresh" -> "refreshTermux applied ${termux.refreshTermux()} fix(es)"
                "rootfs" -> "ensureRootfsExecutable -> ${termux.ensureRootfsExecutable()}"
                "permissions" -> "fixRootfsPermissions -> ${termux.fixRootfsPermissions()}"
                "commands" -> "repairInstalledCommands -> ${termux.repairInstalledCommands()}"
                "scripts" -> "fixScriptsOnce -> ${termux.fixScriptsOnce()}"
                "dpkg" -> "fixDpkgWrapper -> ${termux.fixDpkgWrapper()}"
                "aptconfig" -> "ensureAptConfigs -> ${termux.ensureAptConfigs()}"
                "usrbin" -> "fixUsrBin -> ${termux.fixUsrBin()}"
                "apt" -> "fixAptSources -> ${termux.fixAptSources()}, ensureAptWorking -> ${termux.ensureAptWorking()}"
                "jvm" -> if (config.selectedJavaVendor == JavaVendor.Fcl) {
                    // FCL 那套没有 bin/java wrapper、也不在 $PREFIX/lib/jvm 下，
                    // 它的「修复」等价于重下一次 —— 指向它做 ensureJvmComplete 只会修错对象。
                    "selected runtime is FCL (${config.selectedJavaVersion.name}); " +
                        "use reinstall_java with vendor=Fcl"
                } else {
                    "ensureJvmComplete(${config.selectedJavaVersion.name}) -> ${termux.ensureJvmComplete(config.selectedJavaVersion)}"
                }
                "fonts" -> "repairFontRuntime -> ${termux.repairFontRuntime()}"
                else -> {
                    val activeCore = config.activeCoresOrNull()
                    val needed = activeCore?.let { termux.needsFontRuntime(it) } ?: false
                    val fixes = termux.autoRepairRuntime(
                        config.selectedJavaVersion,
                        needed,
                        config.selectedJavaVendor
                    )
                    "autoRepairRuntime applied $fixes fix(es)"
                }
            }
        }
    }

    private suspend fun checkCommand(args: JsonObject?): McpToolResult {
        val command = McpArgs.string(args, "command")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return bad("Missing required argument: command")
        // diagnoseCommand 会真的去跑一遍探测命令，只在「没装上」时才调用，避免无谓开销
        val installed = termux.isCommandInstalled(command)
        val detail = if (installed) "(present in the Termux PATH)" else termux.diagnoseCommand(command)
        return McpToolResult("$command installed=$installed\n$detail")
    }

    private suspend fun setDownloadMirror(args: JsonObject?): McpToolResult {
        val raw = McpArgs.string(args, "mirror")?.trim()
        val mirror = DownloadMirror.entries.firstOrNull { it.name.equals(raw, true) || it.displayName == raw }
            ?: return bad("Unknown mirror '$raw'. Use one of: " + DownloadMirror.entries.joinToString(", ") { it.name })
        repo.updateAndSaveConfig { it.copy(downloadMirror = mirror) }
        termux.setDownloadMirror(mirror)
        return McpToolResult("downloadMirror = ${mirror.name} (${mirror.displayName}). Takes effect on the next download.")
    }

    private suspend fun setAptMirror(args: JsonObject?): McpToolResult {
        val raw = McpArgs.string(args, "mirror")?.trim()
        val picked = AptMirror.entries.firstOrNull { it.name.equals(raw, true) || it.displayName == raw }
            ?: return bad("Unknown mirror '$raw'. Use one of: " + AptMirror.entries.joinToString(", ") { it.name })
        // 与 UI 保持一致：Official 只是旧配置的兼容值，落盘时归一到清华
        val effective = if (picked == AptMirror.Official) AptMirror.Tuna else picked
        repo.updateAndSaveConfig { it.copy(aptMirror = effective) }
        withContext(Dispatchers.IO) { termux.setAptMirror(effective) }
        return McpToolResult("aptMirror = ${effective.name} (${effective.displayName}). Termux sources rewritten.")
    }

    // ══════════════════════════════════════════════════════════
    // 核心与实例
    // ══════════════════════════════════════════════════════════

    private suspend fun listCoreVersions(args: JsonObject?): McpToolResult {
        val core = parseCore(McpArgs.string(args, "core"))
            ?: return bad("Missing/invalid argument: core. Use one of: " + coreNames())
        val limit = (McpArgs.int(args, "limit") ?: 60).coerceIn(1, 400)
        val versions = controller.fetchVersions(core).take(limit)
        if (versions.isEmpty()) {
            return McpToolResult(
                "No versions returned for ${core.displayName}. The upstream API may be unreachable from this device; " +
                    "check the network or retry.",
                isError = true
            )
        }
        return McpToolResult(
            "${core.displayName} (${versions.size} versions shown):\n" + versions.joinToString(", ") +
                "\nCreate with create_server {\"core\":\"${core.name}\",\"version\":\"${versions.first()}\",\"name\":\"...\"}."
        )
    }

    private suspend fun createServer(args: JsonObject?): McpToolResult {
        if (!termux.isReady()) return notReady()
        val core = parseCore(McpArgs.string(args, "core"))
            ?: return bad("Missing/invalid argument: core. Use one of: " + coreNames())
        val version = McpArgs.string(args, "version")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return bad("Missing required argument: version (see list_core_versions)")
        val config = repo.currentConfig()
        val name = McpArgs.string(args, "name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${core.displayName} $version"
        controller.duplicateNameOf(name, config)?.let {
            return bad("A server named '$it' already exists; pass a different name.")
        }
        if (core.needsInstaller && !termux.isJavaInstalled(config.selectedJavaVersion, config.selectedJavaVendor)) {
            return bad(
                "${core.displayName} runs an installer that needs " +
                    "${config.selectedJavaVendor.logName} ${config.selectedJavaVersion.displayName}. " +
                    "Install it first (install_java) and then retry."
            )
        }
        // PocketMine 走 PHP 解释器：这里不校验的话，创建会成功、而随后 start_server 抛
        // 「需要 PHP 运行环境」——它只回一句 "Start initiated"，AI 得翻 get_app_log 才知道失败。
        if (core == com.mineserve.mobile.data.ServerCore.PocketMine && !termux.isPhpInstalled()) {
            return bad(
                "PocketMine-MP runs on PHP. Install the PHP runtime first (install_php) and then retry create_server."
            )
        }
        // runtimeKind 必须跟着核心一起落位：它决定 start_server 走 JVM 还是 PHP 解释器。
        // downloadCore 从不改这个字段，于是「装好了 PocketMine 但 runtimeKind 还是 Java」
        // 会让每一次启动都失败。
        val launchKind = if (core == com.mineserve.mobile.data.ServerCore.PocketMine) {
            com.mineserve.mobile.data.RuntimeKind.Php
        } else {
            com.mineserve.mobile.data.RuntimeKind.Java
        }
        val launch = config.copy(selectedCore = core, mcVersion = version, runtimeKind = launchKind)
        if (config.runtimeKind != launchKind) {
            repo.updateAndSaveConfig { it.copy(runtimeKind = launchKind) }
        }
        return runJob(args, "create_server:$name") { job ->
            job.log("Downloading ${core.displayName} $version as '$name'...")
            val lastPct = AtomicInteger(-1)
            val dirName = controller.downloadCore(launch, name) { done, total, speed ->
                // 进度回调很密（每个数据块一次），只在每跨过 5% 时记一行，否则日志会被刷爆
                val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                if (pct >= 0 && pct / 5 != lastPct.get() / 5) {
                    lastPct.set(pct)
                    job.log("  $pct% ($done/$total bytes, ${speed / 1024} KB/s)")
                }
            }
            val serverDir = termux.serverDirFor(dirName)
            if (!File(serverDir, "eula.txt").exists()) {
                job.log("warning: eula.txt was not created; the server may refuse to start")
            }
            "created '$name' at home/servers/$dirName (set active). Start it with start_server."
        }
    }

    private suspend fun deleteServer(args: JsonObject?): McpToolResult {
        val (config, core) = McpServers.resolve(repo, args)
        if (McpArgs.bool(args, "confirm") != true) {
            return bad(
                "Refusing to delete '${core.name}': this permanently removes home/servers/${core.dirName} " +
                    "including its world, configs and plugins. Re-issue with \"confirm\": true to proceed."
            )
        }
        if (termux.isMcRunning() && config.activeCoreName == core.name) {
            return bad("'${core.name}' is the running server; stop it first (stop_server).")
        }
        val dir = termux.serverDirFor(core.dirName)
        if (dir.exists() && !dir.deleteRecursively()) {
            return bad("Failed to delete ${dir.absolutePath}; some files may be in use.")
        }
        repo.updateAndSaveConfig { current ->
            val remaining = current.installedCores.filter { it.name != core.name }
            current.copy(
                installedCores = remaining,
                activeCoreName = if (current.activeCoreName == core.name) remaining.firstOrNull()?.name else current.activeCoreName
            )
        }
        return McpToolResult("Deleted '${core.name}' (directory ${core.dirName}). Remaining: " + McpServers.nameList(repo.currentConfig()))
    }

    // ══════════════════════════════════════════════════════════
    // 应用配置
    // ══════════════════════════════════════════════════════════

    private fun getConfig(): McpToolResult = McpToolResult(configJson(repo.currentConfig()).toString())

    private fun configJson(c: McConfig): JsonObject = buildJsonObject {
        put("activeCoreName", c.activeCoreName ?: "")
        put("localPort", c.localPort)
        put("maxHeapMb", c.maxHeapMb)
        put("autoRestartOnCrash", c.autoRestartOnCrash)
        put("selectedJavaVendor", c.selectedJavaVendor.name)
        put("selectedJavaVersion", c.selectedJavaVersion.name)
        put("selectedPhpVersion", c.selectedPhpVersion.name)
        put("runtimeKind", c.runtimeKind.name)
        // 当前这套 JDK 能不能监听 IPv6（决定 server-ip 能不能写 ::）。
        // FCL 来源自带；Termux 来源取决于 jvmFlavor 是否为 PatchedJdk。
        put("javaIpv6ListenAvailable", c.ipv6ListenAvailable)
        // Java 核心用哪套 JVM 实现（Termux 原版 / 带 IPv6 兼容层的补丁版）。
        // UI 上已不再暴露这个开关（默认就是 PatchedJdk），保留给 MCP 做对照实验。
        put("jvmFlavor", c.jvmFlavor.name)
        put("autoBackupIntervalMin", c.autoBackupIntervalMin)
        put("autoBackupType", c.autoBackupType.name)
        put("maxSnapshots", c.maxSnapshots)
        put("stopAutoBackup", c.stopAutoBackup)
        put("downloadMirror", c.downloadMirror.name)
        put("aptMirror", c.aptMirror.name)
        put("keepWifiLock", c.keepWifiLock)
        put("keepCpuWakelock", c.keepCpuWakelock)
        put("keepScreenOnWhileRunning", c.keepScreenOnWhileRunning)
        put("keepStatusOverlay", c.keepStatusOverlay)
        put("skipStartupProgress", c.skipStartupProgress)
        put("advancedCustomCommandEnabled", c.advancedCustomCommandEnabled)
        put("advancedCustomCommand", c.advancedCustomCommand)
        put("dailyStartEnabled", c.dailyStartEnabled)
        put("dailyStartHour", c.dailyStartHour)
        put("dailyStartMinute", c.dailyStartMinute)
        put("dailyStopEnabled", c.dailyStopEnabled)
        put("dailyStopHour", c.dailyStopHour)
        put("dailyStopMinute", c.dailyStopMinute)
        put("installedCores", buildJsonArray { c.installedCores.forEach { add(JsonPrimitive(it.name)) } })
    }

    private suspend fun setConfig(args: JsonObject?): McpToolResult {
        val key = McpArgs.string(args, "key")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return bad("Missing required argument: key")
        val raw = McpArgs.string(args, "value") ?: return bad("Missing required argument: value")
        val text = raw.trim()

        var transform: ((McConfig) -> McConfig)? = null
        // 越界/类型错误统一收集，避免每个分支都要写一遍 return
        var invalid: String? = null
        val intVal = text.toIntOrNull()
        val boolVal = McpArgs.bool(args, "value")

        fun intField(range: IntRange, apply: (McConfig, Int) -> McConfig) {
            val v = intVal
            if (v == null) {
                invalid = "'$key' requires an integer, got '$raw'"
            } else if (v !in range) {
                invalid = "'$key' must be in ${range.first}..${range.last}, got $v"
            } else {
                transform = { c -> apply(c, v) }
            }
        }

        fun boolField(apply: (McConfig, Boolean) -> McConfig) {
            val v = boolVal
            if (v == null) invalid = "'$key' requires a boolean (true/false), got '$raw'"
            else transform = { c -> apply(c, v) }
        }

        when (key) {
            "localPort" -> intField(1..65535) { c, v -> c.copy(localPort = v) }
            "maxHeapMb" -> intField(128..65536) { c, v -> c.copy(maxHeapMb = v) }
            "autoBackupIntervalMin" -> intField(0..10080) { c, v -> c.copy(autoBackupIntervalMin = v) }
            "maxSnapshots" -> intField(1..100) { c, v -> c.copy(maxSnapshots = v) }
            "dailyStartHour", "dailyStopHour" -> intField(0..23) { c, v ->
                if (key == "dailyStartHour") c.copy(dailyStartHour = v) else c.copy(dailyStopHour = v)
            }
            "dailyStartMinute", "dailyStopMinute" -> intField(0..59) { c, v ->
                if (key == "dailyStartMinute") c.copy(dailyStartMinute = v) else c.copy(dailyStopMinute = v)
            }
            "autoRestartOnCrash" -> boolField { c, v -> c.copy(autoRestartOnCrash = v) }
            "keepWifiLock" -> boolField { c, v -> c.copy(keepWifiLock = v) }
            "keepCpuWakelock" -> boolField { c, v -> c.copy(keepCpuWakelock = v) }
            "keepScreenOnWhileRunning" -> boolField { c, v -> c.copy(keepScreenOnWhileRunning = v) }
            "keepStatusOverlay" -> boolField { c, v -> c.copy(keepStatusOverlay = v) }
            "skipStartupProgress" -> boolField { c, v -> c.copy(skipStartupProgress = v) }
            "stopAutoBackup" -> boolField { c, v -> c.copy(stopAutoBackup = v) }
            "dailyStartEnabled" -> boolField { c, v -> c.copy(dailyStartEnabled = v) }
            "dailyStopEnabled" -> boolField { c, v -> c.copy(dailyStopEnabled = v) }
            "advancedCustomCommandEnabled" -> boolField { c, v -> c.copy(advancedCustomCommandEnabled = v) }
            "advancedCustomCommand" -> transform = { c -> c.copy(advancedCustomCommand = raw) }
            "selectedJavaVersion" -> {
                val v = parseJavaVersion(text)
                if (v == null) {
                    invalid = "Unknown Java version '$text'. Termux 来源可用: " + javaVersionNames() +
                        "; FCL 来源可用: " + javaVersionNames(JavaVendor.Fcl)
                } else transform = { c ->
                    // 版本必须与该来源匹配：FCL 上游没有 8/11，留着会写出启动时找不到 java 的组合。
                    // 与 UI 的 applyRuntimeSelection 同规则 —— 不匹配时回到支持该版本的 Termux 来源。
                    if (v in c.selectedJavaVendor.versions) c.copy(selectedJavaVersion = v)
                    else c.copy(selectedJavaVendor = JavaVendor.Termux, selectedJavaVersion = v)
                }
            }
            "selectedJavaVendor" -> {
                val v = JavaVendor.entries.firstOrNull {
                    it.name.equals(text, true) || it.logName.equals(text, true)
                }
                if (v == null) {
                    invalid = "Unknown selectedJavaVendor '$text'. Use one of: " +
                        JavaVendor.entries.joinToString(", ") { it.name }
                } else transform = { c ->
                    // 来源与版本必须同时合法，单独换来源会留下 Fcl.Java8 这种不存在的组合
                    c.copy(
                        selectedJavaVendor = v,
                        selectedJavaVersion = c.selectedJavaVersion.takeIf { it in v.versions }
                            ?: v.versions.first()
                    )
                }
            }
            "selectedPhpVersion" -> {
                val v = parsePhpVersion(text)
                if (v == null) invalid = "Unknown PHP version '$text'. Use: " + PhpVersion.entries.joinToString(", ") { it.name }
                else transform = { c -> c.copy(selectedPhpVersion = v) }
            }
            "runtimeKind" -> {
                val v = RuntimeKind.entries.firstOrNull { it.name.equals(text, true) }
                if (v == null) invalid = "Unknown runtimeKind '$text'. Use: Java, Php"
                else transform = { c -> c.copy(runtimeKind = v) }
            }
            "jvmFlavor" -> {
                val v = JvmFlavor.entries.firstOrNull { it.name.equals(text, true) }
                if (v == null) invalid = "Unknown jvmFlavor '$text'. Use one of: " + JvmFlavor.entries.joinToString(", ") { it.name }
                else transform = { c -> c.copy(jvmFlavor = v) }
            }
            "autoBackupType" -> {
                val v = AutoBackupType.entries.firstOrNull { it.name.equals(text, true) }
                if (v == null) invalid = "Unknown autoBackupType '$text'. Use one of: " + AutoBackupType.entries.joinToString(", ") { it.name }
                else transform = { c -> c.copy(autoBackupType = v) }
            }
            "downloadMirror" -> {
                val v = DownloadMirror.entries.firstOrNull { it.name.equals(text, true) }
                if (v == null) invalid = "Unknown downloadMirror '$text'. Use one of: " + DownloadMirror.entries.joinToString(", ") { it.name }
                else transform = { c -> c.copy(downloadMirror = v) }
            }
            "aptMirror" -> {
                val picked = AptMirror.entries.firstOrNull { it.name.equals(text, true) }
                if (picked == null) {
                    invalid = "Unknown aptMirror '$text'. Use one of: " + AptMirror.entries.joinToString(", ") { it.name }
                } else {
                    val effective = if (picked == AptMirror.Official) AptMirror.Tuna else picked
                    transform = { c -> c.copy(aptMirror = effective) }
                }
            }
            in SELF_LOCKOUT_KEYS -> return bad(
                "'$key' is intentionally not writable over MCP: changing it restarts or disables the very HTTP channel " +
                    "this call arrived on, so the client would lose the connection with no way back. Change it in the app UI."
            )
            else -> return bad("Unknown or read-only config key '$key'. Writable keys: " + WRITABLE_CONFIG_KEYS.joinToString(", "))
        }

        invalid?.let { return bad(it) }
        val apply = transform ?: return bad("Invalid value '$raw' for '$key'.")
        repo.updateAndSaveConfig(apply)
        val applied = (configJson(repo.currentConfig())[key] as? JsonPrimitive)?.content ?: ""
        return McpToolResult("config.$key = $applied")
    }

    // ══════════════════════════════════════════════════════════
    // 快照
    // ══════════════════════════════════════════════════════════

    private fun listSnapshots(): McpToolResult {
        val snapshots = backups.listSnapshots()
        if (snapshots.isEmpty()) return McpToolResult("(no snapshots)")
        val json = buildJsonArray {
            snapshots.forEach { s ->
                add(buildJsonObject {
                    put("name", s.name)
                    put("size", s.sizeText)
                    put("created", s.createdText)
                })
            }
        }
        return McpToolResult(json.toString())
    }

    private suspend fun createSnapshot(args: JsonObject?): McpToolResult {
        val (config, core) = McpServers.resolve(repo, args)
        val serverDir = termux.serverDirFor(core.dirName)
        // Java 系的世界直接在服务端目录下（`world/`），基岩系（PowerNukkitX）在 `worlds/<名字>/`。
        // 以前只看 `world/`，于是 PNX 实例的备份链路整条不可用，还给出误导性的归因。
        val worldsRoot = Worlds.worldsRoot(serverDir, core.core.isBedrock)
        val hasWorld = Worlds.WORLD_DIR_NAMES.any { File(serverDir, it).isDirectory } ||
            Worlds.list(worldsRoot, "").isNotEmpty()
        if (!hasWorld) {
            return bad(
                "'${core.name}' has no world directory yet; start the server once before snapshotting " +
                    "(looked for ${Worlds.WORLD_DIR_NAMES.joinToString(" / ")})."
            )
        }
        return runJob(args, "create_snapshot:${core.name}") { job ->
            // 必须走 TermuxRuntime.createSnapshot：它落盘的是 home/snapshots/world_<ts>.zip，
            // 也就是 listSnapshots / restoreSnapshot / deleteSnapshot 认的那一份。
            //
            // 不要用 BackupManager.createSnapshot —— 那个名字实际是
            // `backupWorldToExternal(dirName, BackupOrigin.Manual)`，产物只写到公共存储
            // /storage/emulated/0/世界与服务器的备份/，home/snapshots 始终为空。
            // 用它会出现「create_snapshot 报成功、list_snapshots 却查不到、restore_snapshot
            // 永远说找不到」的自相矛盾，AI 的备份-还原链路直接断掉（实测踩过）。
            job.log("Zipping $worldsRoot into home/snapshots ...")
            val path = termux.createSnapshot(config.maxSnapshots, core.dirName)
                ?: throw IllegalStateException("snapshot creation failed (check free space and the app log)")
            // 回文件名而不是绝对路径：restore_snapshot / delete_snapshot 的 `snapshot` 参数收的就是它
            "snapshot created: ${File(path).name}"
        }
    }

    private suspend fun restoreSnapshot(args: JsonObject?): McpToolResult {
        val snapshot = McpArgs.string(args, "snapshot")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return bad("Missing required argument: snapshot (see list_snapshots)")
        val (_, core) = McpServers.resolve(repo, args)
        requireConfirm(args, "restore '$snapshot' over the world of '${core.name}' (current progress is lost)")?.let { return it }
        if (!backups.listSnapshots().any { it.name == snapshot }) {
            return bad("Snapshot '$snapshot' not found. See list_snapshots.")
        }
        return runJob(args, "restore_snapshot:$snapshot") { job ->
            job.log("Restoring $snapshot into ${core.name} (the running server, if any, is stopped first)...")
            val ok = backups.restoreSnapshot(snapshot, core.dirName)
            if (!ok) {
                dumpAppLog(job)
                throw IllegalStateException("restoreSnapshot returned false")
            }
            "restored $snapshot into '${core.name}'"
        }
    }

    private fun deleteSnapshot(args: JsonObject?): McpToolResult {
        val snapshot = McpArgs.string(args, "snapshot")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return bad("Missing required argument: snapshot (see list_snapshots)")
        requireConfirm(args, "delete snapshot '$snapshot'")?.let { return it }
        val file = resolveSnapshotFile(snapshot)
            ?: return bad("Snapshot '$snapshot' not found or outside home/snapshots (see list_snapshots).")
        if (!file.isFile) return bad("'$snapshot' is not a snapshot file.")
        val ok = file.delete()
        return McpToolResult(
            if (ok) "Deleted snapshot $snapshot." else "Failed to delete $snapshot (${file.absolutePath}).",
            isError = !ok
        )
    }

    /**
     * 把快照名收敛到 `home/snapshots/` 内的一个真实文件。
     *
     * 参数直接进 `File(dir, name)` 时 `../servers/<dir>/server.jar` 能删掉任意服务端文件，
     * 所以这里走 [McpFileUtils.sandboxedFile] 做 canonical 校验，并要求结果确实是文件。
     */
    private fun resolveSnapshotFile(snapshot: String): File? {
        val dir = File(termux.installer.rootDir, "home/snapshots")
        val file = try {
            McpFileUtils.sandboxedFile(dir, snapshot)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return file.takeIf { it.isFile }
    }

    // ══════════════════════════════════════════════════════════
    // 插件
    // ══════════════════════════════════════════════════════════

    private suspend fun listPlugins(args: JsonObject?): McpToolResult {
        val (_, core) = McpServers.resolve(repo, args)
        val plugins = pluginManager.scan(core.dirName)
        if (plugins.isEmpty()) return McpToolResult("(no plugins installed in ${core.name}/plugins)")
        val json = buildJsonArray {
            plugins.forEach { p ->
                add(buildJsonObject {
                    put("fileName", p.fileName)
                    put("name", p.meta?.name ?: p.baseName)
                    put("version", p.meta?.version ?: "")
                    put("size", p.sizeText)
                    put("enabled", p.isEnabled)
                    put("source", p.sourceTag)
                })
            }
        }
        val conflicts = pluginManager.detectPluginConflicts(plugins)
        val note = if (conflicts.isEmpty()) "" else "\nconflicts: " + conflicts.joinToString(" | ")
        return McpToolResult(json.toString() + note)
    }

    private suspend fun installPlugin(args: JsonObject?): McpToolResult {
        val url = McpArgs.string(args, "url")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return bad("Missing required argument: url (direct download link to the .jar)")
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return bad("Only http/https URLs are supported.")
        }
        val (config, core) = McpServers.resolve(repo, args)
        if (!core.core.supportsPlugins) {
            return bad("${core.core.displayName} has no plugin system.")
        }
        if (termux.isMcRunning() && config.activeCoreName == core.name) {
            return bad("'${core.name}' is running; stop it before changing plugins (stop_server).")
        }
        val fileName = McpArgs.string(args, "name")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.endsWith(".jar", true)) it else "$it.jar" }
            ?: McpFileUtils.safeRemoteFileName(url, "plugin")
        return runJob(args, "install_plugin:${core.name}") { job ->
            job.log("Downloading $url")
            val installed = pluginManager.installFromUrl(url, fileName, core.dirName)
            "installed ${installed.name} (${installed.length() / 1024} KB) into ${core.name}/plugins"
        }
    }

    private suspend fun togglePlugin(args: JsonObject?): McpToolResult {
        val fileName = McpArgs.string(args, "file_name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return bad("Missing required argument: file_name (see list_plugins)")
        val (config, core) = McpServers.resolve(repo, args)
        if (termux.isMcRunning() && config.activeCoreName == core.name) {
            return bad("'${core.name}' is running; stop it before enabling/disabling plugins.")
        }
        val newName = pluginManager.toggleEnabled(fileName, core.dirName)
            ?: return bad("Plugin '$fileName' not found in ${core.name}/plugins.")
        return McpToolResult("Renamed to '$newName' (leading '-' means disabled).")
    }

    private suspend fun deletePlugin(args: JsonObject?): McpToolResult {
        val fileName = McpArgs.string(args, "file_name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return bad("Missing required argument: file_name (see list_plugins)")
        val (config, core) = McpServers.resolve(repo, args)
        requireConfirm(args, "delete plugin '$fileName' from '${core.name}'")?.let { return it }
        if (termux.isMcRunning() && config.activeCoreName == core.name) {
            return bad("'${core.name}' is running; stop it before deleting plugins.")
        }
        // 文件名是外部输入：直接交给下层 `File(pluginsDir, name)` 时 `../server.properties`
        // 会删掉服务端配置，而失败路径还会把它拼进 `rm -f` 命令行。这里要求它是不含
        // 目录成分的纯文件名，越界直接拒绝（不静默剥成另一个文件）。
        val safeName = McpFileUtils.safeRemoteFileName(fileName, "plugin")
        if (safeName != fileName) {
            return bad("Invalid file_name '$fileName': must be a plain file name inside plugins/ (see list_plugins).")
        }
        val alsoRemoveData = McpArgs.bool(args, "also_remove_data") == true
        val ok = pluginManager.delete(safeName, core.dirName, alsoRemoveData)
        return McpToolResult(
            if (ok) "Deleted $fileName" + if (alsoRemoveData) " and its data directory." else "."
            else "Plugin '$fileName' not found.",
            isError = !ok
        )
    }

    // ══════════════════════════════════════════════════════════
    // 日志与诊断
    // ══════════════════════════════════════════════════════════

    private fun appLog(args: JsonObject?): McpToolResult {
        val lines = (McpArgs.int(args, "lines") ?: 200).coerceIn(1, 2000)
        val tail = termux.readAppLogTail(lines)
        if (tail.isEmpty()) return McpToolResult("(app log is empty)")
        return McpToolResult(tail.joinToString("\n"))
    }

    private fun clearAppLog(): McpToolResult {
        termux.clearAppLog()
        return McpToolResult("App log cleared. Useful right before a test run to keep the output readable.")
    }

    private suspend fun lastRunLog(args: JsonObject?): McpToolResult {
        val maxBytes = (McpArgs.int(args, "max_bytes") ?: 262_144).coerceIn(1024, 4 * 1024 * 1024).toLong()
        val text = termux.readLastMcRunLog(maxBytes)
        if (text.isBlank()) return McpToolResult("(no previous server run log found)")
        return McpToolResult(text)
    }

    private fun deviceInfo(): McpToolResult {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(memory) }
        val dataStat = runCatching { StatFs(app.filesDir.absolutePath) }.getOrNull()
        val json = buildJsonObject {
            put("appVersion", BuildConfig.VERSION_NAME)
            put("packageName", app.packageName)
            put("androidRelease", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("abis", buildJsonArray { Build.SUPPORTED_ABIS.forEach { add(JsonPrimitive(it)) } })
            put("cpuCores", Runtime.getRuntime().availableProcessors())
            put("jvmMaxHeapMb", Runtime.getRuntime().maxMemory() / MIB)
            put("deviceTotalMemMb", memory.totalMem / MIB)
            put("deviceAvailMemMb", memory.availMem / MIB)
            put("lowMemory", memory.lowMemory)
            if (dataStat != null) {
                put("dataFreeMb", dataStat.availableBytes / MIB)
                put("dataTotalMb", dataStat.totalBytes / MIB)
            }
        }
        return McpToolResult(json.toString())
    }

    private suspend fun checkPort(args: JsonObject?): McpToolResult {
        val port = McpArgs.int(args, "port")
            ?: return bad("Missing required argument: port")
        if (port !in 1..65535) return bad("port must be 1-65535")
        val host = McpArgs.string(args, "host")?.trim()?.takeIf { it.isNotEmpty() } ?: "127.0.0.1"
        val timeoutMs = (McpArgs.int(args, "timeout_ms") ?: 1500).coerceIn(200, 10_000)
        val reachable = withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            }.isSuccess
        }
        return McpToolResult("tcp $host:$port reachable=$reachable (timeout ${timeoutMs}ms)")
    }

    private suspend fun httpProbe(args: JsonObject?): McpToolResult {
        val raw = McpArgs.string(args, "url")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return bad("Missing required argument: url")
        val uri = runCatching { URI(raw) }.getOrNull() ?: return bad("Invalid URL: $raw")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return bad("Only http/https URLs are supported.")
        val method = McpArgs.string(args, "method")?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: "GET"
        if (method !in HTTP_METHODS) return bad("Unsupported method '$method'. Use one of: " + HTTP_METHODS.joinToString(", "))
        val timeoutMs = (McpArgs.int(args, "timeout_ms") ?: 10_000).coerceIn(500, 60_000)
        val body = McpArgs.string(args, "body")
        val maxBody = 4096

        return withContext(Dispatchers.IO) {
            if (scheme == "http") return@withContext rawHttpProbe(uri, method, body, timeoutMs, maxBody)
            var conn: HttpURLConnection? = null
            try {
                conn = uri.toURL().openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Minexa-MCP/${BuildConfig.VERSION_NAME}")
                if (body != null && method !in setOf("GET", "HEAD")) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.let { readAtMostText(it, maxBody) } ?: ""
                val out = StringBuilder()
                out.append("$method ${conn.url} -> HTTP $code ${conn.responseMessage ?: ""}\n")
                conn.headerFields?.forEach { (header, values) ->
                    if (header != null && !values.isNullOrEmpty()) out.append("  $header: ${values.joinToString(", ")}\n")
                }
                if (text.isNotEmpty()) out.append("--- body (first $maxBody bytes) ---\n").append(text)
                McpToolResult(out.toString())
            } catch (e: Exception) {
                McpToolResult("$method $raw failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    /**
     * 明文 http 的探测走裸 socket（见 [McpRawHttp] 的说明）：
     * HttpURLConnection 会被 Android 的明文策略直接拒掉（连 127.0.0.1 都不放过），
     * 而 http_probe 最主要的用途恰恰是探内网/环回的明文端点。
     */
    private fun rawHttpProbe(
        uri: URI,
        method: String,
        body: String?,
        timeoutMs: Int,
        maxBody: Int
    ): McpToolResult {
        val host = runCatching { McpRawHttp.host(uri) }.getOrElse {
            return McpToolResult("$method $uri failed: ${it.message}", isError = true)
        }
        val port = McpRawHttp.port(uri)
        val userAgent = "Minexa-MCP/${BuildConfig.VERSION_NAME}"
        var socket: Socket? = null
        return try {
            socket = Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write(McpRawHttp.buildRequest(uri, method, body, userAgent))
                flush()
            }
            // Connection: close ⇒ 读到 EOF 即响应结束；上限 64KB，避免恶意/异常服务把内存拉爆
            val raw = readAtMostText(socket.getInputStream(), 65_536)
            val r = McpRawHttp.parseResponse(raw, maxBody)
            val out = StringBuilder()
            out.append("$method ${uri} -> ${r.statusLine.ifEmpty { "(no status line)" }}\n")
            r.headerLines.forEach { out.append("  ").append(it).append('\n') }
            if (r.body.isNotEmpty()) {
                out.append("--- body (first $maxBody bytes) ---\n").append(r.body)
            } else if (r.code < 0) {
                out.append("(empty response; the port may not speak HTTP)\n")
            }
            McpToolResult(out.toString())
        } catch (e: Exception) {
            McpToolResult(
                "$method ${uri} failed: ${e.message ?: e.javaClass.simpleName}",
                isError = true
            )
        } finally {
            runCatching { socket?.close() }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 自动化测试辅助
    // ══════════════════════════════════════════════════════════

    private suspend fun waitForServer(args: JsonObject?): McpToolResult {
        val condition = McpArgs.string(args, "condition")?.trim()?.takeIf { it.isNotEmpty() } ?: "ready"
        val timeoutSec = (McpArgs.int(args, "timeout_sec") ?: 180).coerceIn(1, 1800)
        val pollMs = (McpArgs.int(args, "poll_ms") ?: 1000).coerceIn(200, 10_000)
        val normalized = condition.lowercase(Locale.ROOT)
        val phaseWanted = if (normalized.startsWith("phase:")) normalized.removePrefix("phase:").trim() else null
        if (phaseWanted == null && normalized !in SERVER_CONDITIONS) {
            return bad("Unknown condition '$condition'. Use one of: " + SERVER_CONDITIONS.joinToString(", ") + ", phase:<Name>")
        }
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeoutSec * 1000L
        while (true) {
            val state = repo.serverState.value
            val met = when {
                phaseWanted != null -> state.startupPhase.name.equals(phaseWanted, ignoreCase = true)
                normalized == "ready" -> state.isRunning && state.startupPhase == StartupPhase.Ready
                normalized == "running" -> state.isRunning
                normalized == "stopped" -> !state.isRunning
                normalized == "failed" -> state.startupPhase == StartupPhase.Failed
                else -> false
            }
            val elapsed = (System.currentTimeMillis() - startedAt) / 1000
            if (met) {
                // 静默期。「就绪」是从日志里的 "Done (...)" 行判出来的，但 Paper 在那之后
                // 还有极短的一段时间控制台的 CommandSourceStack 尚未挂上世界：这段时间内
                // 任何能被解析的控制台指令都会让服务端抛
                //   NullPointerException: ... "CommandSourceStack.getLevel()" is null
                // 而不是真的执行。于是「wait_for_server 一发完就 send_command」会稳定踩坑
                // （整个指令被吞掉，AI 只能看到一条服务端异常）。
                // 实测 19:54:35 的 Done 与紧随其后的 list/tps 全部 NPE，6 秒后的同一条指令正常。
                // 这里补一个默认 2 秒的静默期把它挡掉；明确不需要的调用方传 settle_ms=0 关掉。
                val settleMs = (McpArgs.int(args, "settle_ms") ?: DEFAULT_READY_SETTLE_MS)
                    .coerceIn(0, MAX_READY_SETTLE_MS)
                if (settleMs > 0 && normalized in SETTLE_CONDITIONS) {
                    delay(settleMs.toLong())
                }
                val settled = repo.serverState.value
                val settleNote = if (settleMs > 0 && normalized in SETTLE_CONDITIONS) {
                    " (+${settleMs}ms settle)"
                } else ""
                return McpToolResult(
                    "condition '$condition' met after ${elapsed}s$settleNote " + stateLine(settled)
                )
            }
            if (System.currentTimeMillis() >= deadline) {
                return McpToolResult(
                    "condition '$condition' NOT met within ${timeoutSec}s " + stateLine(state), isError = true
                )
            }
            delay(pollMs.toLong())
        }
    }

    private fun stateLine(state: com.mineserve.mobile.data.ServerState): String {
        val uptime = if (state.isRunning && state.runningSinceMs > 0L) {
            " uptime=" + ((android.os.SystemClock.elapsedRealtime() - state.runningSinceMs) / 1000L) + "s"
        } else ""
        return "(isRunning=${state.isRunning}, phase=${state.startupPhase.name}, players=${state.onlinePlayers}$uptime)"
    }

    private suspend fun waitForLog(args: JsonObject?): McpToolResult {
        val pattern = McpArgs.string(args, "pattern")?.takeIf { it.isNotEmpty() }
            ?: return bad("Missing required argument: pattern (regular expression)")
        val ignoreCase = McpArgs.bool(args, "ignore_case") == true
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = runCatching { Regex(pattern, options) }.getOrElse {
            return bad("Invalid regular expression: ${it.message}")
        }
        val timeoutSec = (McpArgs.int(args, "timeout_sec") ?: 120).coerceIn(1, 1800)
        val pollMs = (McpArgs.int(args, "poll_ms") ?: 1000).coerceIn(200, 10_000)
        val (_, core) = McpServers.resolve(repo, args)
        // 日志文件每轮都要重新挑：服务端可能先只往控制台写、latest.log 随后才出现
        // （反过来也有：Forge 早期输出在 latest.log，本轮才切到控制台捕获）。
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeoutSec * 1000L
        var lastTail: List<String> = emptyList()
        while (true) {
            val logFile = termux.mcLogFileFor(core.dirName)
            lastTail = if (logFile == null) emptyList()
            else withContext(Dispatchers.IO) { McpFileUtils.tailLines(logFile, 400) }
            val hits = lastTail.filter { regex.containsMatchIn(it) }
            if (hits.isNotEmpty()) {
                val elapsed = (System.currentTimeMillis() - startedAt) / 1000
                return McpToolResult(
                    "matched /$pattern/ in ${core.name} after ${elapsed}s:\n" + hits.takeLast(20).joinToString("\n")
                )
            }
            if (System.currentTimeMillis() >= deadline) {
                val tail = lastTail.takeLast(20).joinToString("\n")
                return McpToolResult(
                    "no line matched /$pattern/ in ${core.name} within ${timeoutSec}s.\n" +
                        "--- last 20 lines ---\n" + (tail.ifEmpty { "(log is empty)" }),
                    isError = true
                )
            }
            delay(pollMs.toLong())
        }
    }

    private suspend fun fileHash(args: JsonObject?): McpToolResult {
        val algorithm = McpArgs.string(args, "algo")?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: "sha256"
        if (!McpFileUtils.supportsDigest(algorithm)) {
            return bad("Unsupported algo '$algorithm'. Use one of: " + McpFileUtils.digestAlgorithms().joinToString(", "))
        }
        val (_, core) = McpServers.resolve(repo, args)
        val rel = McpArgs.string(args, "path")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return bad("Missing required argument: path")
        val file = McpFileUtils.sandboxedFile(termux.serverDirFor(core.dirName), rel)
        if (!file.isFile) return bad("File not found: $rel")
        val hex = withContext(Dispatchers.IO) { McpFileUtils.digestHex(file, algorithm) }
            ?: return bad("Failed to hash $rel")
        return McpToolResult("$algorithm $hex  size=${file.length()}  path=$rel")
    }

    // ══════════════════════════════════════════════════════════
    // 后台任务
    // ══════════════════════════════════════════════════════════

    private fun getJob(args: JsonObject?): McpToolResult {
        val id = McpArgs.string(args, "job_id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return bad("Missing required argument: job_id (see list_jobs)")
        val job = jobs.get(id) ?: return bad("Unknown job '$id'. It may have been pruned; use list_jobs.")
        return jobResult(job, (McpArgs.int(args, "lines") ?: 200).coerceIn(0, 500))
    }

    private fun listJobs(): McpToolResult {
        val all = jobs.listNewestFirst()
        if (all.isEmpty()) return McpToolResult("(no jobs)")
        val json = buildJsonArray {
            all.forEach { job ->
                add(buildJsonObject {
                    put("id", job.id)
                    put("action", job.action)
                    put("state", job.state.label)
                    put("elapsedMs", job.elapsedMs)
                    if (job.summary.isNotEmpty()) put("summary", job.summary)
                })
            }
        }
        return McpToolResult(json.toString())
    }

    // ══════════════════════════════════════════════════════════
    // 通用辅助
    // ══════════════════════════════════════════════════════════

    /**
     * 提交长任务并给出结果。
     *
     * 先等一小段「宽限期」：毫秒级到几秒的任务（多数 repair/查询类）能直接拿到最终结果，
     * 调用方不用为了一件瞬间完成的事多跑一次 get_job；超过宽限期才返回 job id。
     * 传 `wait: true` 则在 `timeout_sec`（默认 600s）内一直等到任务结束。
     */
    private suspend fun runJob(
        args: JsonObject?,
        action: String,
        block: suspend (McpJobRegistry.Job) -> String
    ): McpToolResult {
        val job = jobs.submit(action, block)
        val waitForCompletion = McpArgs.bool(args, "wait") == true
        val budgetMs = if (waitForCompletion) {
            (McpArgs.int(args, "timeout_sec") ?: 600).coerceIn(5, 3600) * 1000L
        } else {
            JOB_GRACE_MS
        }
        val deadline = System.currentTimeMillis() + budgetMs
        while (!job.isFinished && System.currentTimeMillis() < deadline) delay(JOB_POLL_MS)
        return jobResult(job, DEFAULT_JOB_LOG_LINES)
    }

    private fun jobResult(job: McpJobRegistry.Job, lines: Int): McpToolResult {
        val tail = job.tailLines(lines).joinToString("\n").trim()
        val suffix = if (tail.isEmpty()) "" else "\n--- job log (last ${minOf(lines, job.lineCount)} lines) ---\n$tail"
        val seconds = job.elapsedMs / 1000
        return when (job.state) {
            McpJobRegistry.State.Running -> McpToolResult(
                "Job ${job.id} (${job.action}) is still running after ${seconds}s. " +
                    "Poll it with get_job {\"job_id\":\"${job.id}\"}.$suffix"
            )
            McpJobRegistry.State.Succeeded -> McpToolResult(
                "Job ${job.id} (${job.action}) succeeded in ${seconds}s. ${job.summary}$suffix"
            )
            McpJobRegistry.State.Failed -> McpToolResult(
                "Job ${job.id} (${job.action}) FAILED after ${seconds}s. ${job.summary}$suffix",
                isError = true
            )
        }
    }

    /** 失败时把应用日志尾部灌进 job 日志：安装类操作的真实输出都走 emitLog，没有回调可用 */
    private fun dumpAppLog(job: McpJobRegistry.Job) {
        job.log("--- recent app log ---")
        termux.readAppLogTail(120).forEach { job.log("  $it") }
    }

    private fun notReady() = bad("The Termux runtime is not ready yet (bootstrap incomplete). Wait for setup to finish, then retry.")

    /**
     * 「服务端运行中禁止动同一份 rootfs」的统一守卫。
     *
     * 实测：MC 跑着的时候在 rootfs 里装东西会把 JVM SIGKILL 掉（exit=137，phase 变 Failed），
     * 而这类操作大多用 job 封装，AI 只会看到 job succeeded。install_dependencies 早就有这道
     * 守卫，install_java / install_apt_package / repair_runtime 走的同一个 rootfs 却漏了。
     */
    private fun serverBusyError(what: String) = bad(
        "The server is running; stop it before $what. Package installs write into the same rootfs and " +
            "have been observed to kill the MC process with exit=137, while the job still reports success."
    )

    private fun bad(message: String) = McpToolResult(message, isError = true)

    /**
     * 删除/覆盖类操作的统一护栏：没显式传 `confirm=true` 就只回提示、不做任何事。
     *
     * 判定必须用 `== true` 而不是 `!= false`：`McpArgs.bool` 对「没传」返回 null，
     * 语义上要区别于「传了 false」——前者是调用方没读到提示，后者是明确拒绝。
     */
    private fun requireConfirm(args: JsonObject?, what: String): McpToolResult? =
        if (McpArgs.bool(args, "confirm") == true) null
        else bad("Refusing to $what. Re-issue with \"confirm\": true to proceed.")

    /**
     * 解析 Java 来源；缺省用 [default]（当前所选来源）。
     *
     * 显式传了但拼错时返回 null（调用方报错），不能静默回退 —— 否则
     * 「装 FCL 的 Java 21」会静默变成「装 Termux 的 Java 21」，
     * 这种错法在日志里几乎看不出来。
     */
    private fun parseJavaVendor(raw: JsonObject?, default: JavaVendor = JavaVendor.Termux): JavaVendor? {
        val needle = McpArgs.string(raw, "vendor")?.trim()?.takeIf { it.isNotEmpty() } ?: return default
        return JavaVendor.entries.firstOrNull {
            it.name.equals(needle, true) || it.logName.equals(needle, true)
        }
    }

    private fun parseJavaVersion(raw: String?, vendor: JavaVendor = JavaVendor.Termux): JavaVersion? {
        val needle = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val upper = needle.uppercase(Locale.ROOT)
        val digits = needle.filter { it.isDigit() }
        val pool = vendor.versions
        return pool.firstOrNull { it.name.equals(needle, true) }
            ?: pool.firstOrNull { it.name.uppercase(Locale.ROOT) == upper }
            ?: pool.firstOrNull { digits.isNotEmpty() && it.name.filter { ch -> ch.isDigit() } == digits }
    }

    private fun parsePhpVersion(raw: String?): PhpVersion? {
        val needle = raw?.trim().orEmpty()
        // 缺省用当前唯一版本；显式给了名字但拼错时必须报错，不能静默回退
        if (needle.isEmpty()) return PhpVersion.Default
        return PhpVersion.entries.firstOrNull { it.name.equals(needle, true) || it.displayName.equals(needle, true) }
    }

    private fun parseCore(raw: String?): ServerCore? {
        val needle = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ServerCore.entries.firstOrNull { it.name.equals(needle, true) || it.displayName.equals(needle, true) }
    }

    private fun javaVersionNames(vendor: JavaVendor = JavaVendor.Termux): String =
        vendor.versions.joinToString(", ") { it.name }

    private fun coreNames(): String = ServerCore.entries.joinToString(", ") { it.name }

    private fun readAtMostText(stream: InputStream, limit: Int): String {
        val out = java.io.ByteArrayOutputStream(minOf(limit, 4096))
        val buf = ByteArray(2048)
        stream.use { input ->
            var remaining = limit
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size, remaining))
                if (n <= 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** 取活动服务器的核心类型（repair 时用来判断是否需要字体运行库） */
    private fun McConfig.activeCoresOrNull(): ServerCore? =
        installedCores.firstOrNull { it.name == activeCoreName }?.core

    companion object {
        private const val MIB = 1024L * 1024L

        /** 提交后先等这么久，短任务可以直接返回结果，避免多一次 get_job 往返 */
        private const val JOB_GRACE_MS = 1500L
        private const val JOB_POLL_MS = 250L
        private const val DEFAULT_JOB_LOG_LINES = 60

        private val APT_PACKAGE_RE = Regex("^[A-Za-z0-9][A-Za-z0-9+._-]{0,63}$")

        private val HTTP_METHODS = listOf("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS")

        private val SERVER_CONDITIONS = listOf("ready", "running", "stopped", "failed")

        /**
         * wait_for_server 命中「就绪」后额外静默多久再返回（毫秒）。
         *
         * 见 waitForServer 里的注释：Done 之后到控制台真正可接受指令之间有个很短的窗口，
         * 期间的指令会被 Paper 以 NPE 吞掉。默认压 2 秒把这个窗口盖过去。
         */
        private const val DEFAULT_READY_SETTLE_MS = 2000
        private const val MAX_READY_SETTLE_MS = 15_000

        /** 只有「等它起来」这类条件才需要静默期；等 stopped/failed 没必要等 */
        private val SETTLE_CONDITIONS = setOf("ready", "running")

        /** 这几项目标会在同一个 rootfs 里重装/改写可执行文件，运行中必须拦下。 */
        private val REPAIR_TARGETS_TOUCHING_ROOTFS = setOf(
            "auto", "refresh", "rootfs", "permissions", "commands", "scripts", "dpkg", "aptconfig", "usrbin", "apt", "jvm"
        )

        private val REPAIR_TARGETS = listOf(
            "auto", "refresh", "rootfs", "permissions", "commands",
            "scripts", "dpkg", "aptconfig", "usrbin", "apt", "jvm", "fonts"
        )

        /**
         * 这几项刻意不给 set_config：改动它们会重启 / 关闭当前这条 HTTP 通道，
         * 调用方会在拿到结果之前就断线，而且没有任何回退手段（只能等人去操作 App 界面）。
         */
        private val SELF_LOCKOUT_KEYS = listOf("mcpEnabled", "mcpPort", "mcpAllowLan", "mcpToken")

        private val WRITABLE_CONFIG_KEYS = listOf(
            "localPort", "maxHeapMb", "autoRestartOnCrash",
            "selectedJavaVendor", "selectedJavaVersion", "selectedPhpVersion",
            "runtimeKind", "jvmFlavor", "autoBackupIntervalMin", "autoBackupType", "maxSnapshots", "stopAutoBackup",
            "downloadMirror", "aptMirror", "keepWifiLock", "keepCpuWakelock", "keepScreenOnWhileRunning",
            "keepStatusOverlay", "skipStartupProgress", "advancedCustomCommandEnabled", "advancedCustomCommand",
            "dailyStartEnabled", "dailyStartHour", "dailyStartMinute",
            "dailyStopEnabled", "dailyStopHour", "dailyStopMinute"
        )
    }
}

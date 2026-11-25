package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.DiagnosticCheck
import com.mineserve.mobile.data.DiagnosticReport
import com.mineserve.mobile.data.DiagnosticStatus
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ProotDistro
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.ServerPort
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.data.isProotGuestRuntime
import com.mineserve.mobile.runtime.TermuxRuntime
import com.mineserve.mobile.server.CrashReportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class DiagnosticsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val termuxRuntime: TermuxRuntime,
    private val serverState: StateFlow<ServerState>,
    private val serverProperties: StateFlow<Map<String, String>>,
    private val crashReportManager: CrashReportManager,
    private val refreshJava: () -> Unit,
    private val refreshDependencies: () -> Unit,
    private val refreshPhp: () -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _diagnosticReport = MutableStateFlow(DiagnosticReport())
    val diagnosticReport: StateFlow<DiagnosticReport> = _diagnosticReport.asStateFlow()
    private val _isDiagnosing = MutableStateFlow(false)
    val isDiagnosing: StateFlow<Boolean> = _isDiagnosing.asStateFlow()
    private val _isRepairingRuntime = MutableStateFlow(false)
    val isRepairingRuntime: StateFlow<Boolean> = _isRepairingRuntime.asStateFlow()

    fun runDiagnostics() {
        if (_isDiagnosing.value || _isRepairingRuntime.value) return
        _isDiagnosing.value = true
        scope.launch {
            try {
                _diagnosticReport.value = withContext(Dispatchers.IO) { buildDiagnosticReport() }
            } catch (e: Exception) {
                _diagnosticReport.value = DiagnosticReport(
                    checks = listOf(DiagnosticCheck(
                        "scan", str(R.string.diag_check_scan), e.message ?: str(R.string.diag_check_scan_fail), DiagnosticStatus.Failed
                    )),
                    generatedAtMs = System.currentTimeMillis()
                )
            } finally {
                _isDiagnosing.value = false
            }
        }
    }

    fun safeRepairRuntime() {
        if (_isRepairingRuntime.value || _isDiagnosing.value) return
        _isRepairingRuntime.value = true
        _diagnosticReport.value = _diagnosticReport.value.copy(isRunning = true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cfg = config.value
                    val core = cfg.installedCores.find { it.name == cfg.activeCoreName }
                    termuxRuntime.fixRootfsPermissions()
                    termuxRuntime.repairInstalledCommands()
                    if (termuxRuntime.isReady()) {
                        termuxRuntime.autoRepairRuntime(
                            cfg.selectedJavaVersion,
                            core?.let { termuxRuntime.needsFontRuntime(it.core) } == true
                        )
                    }
                }
                refreshJava()
                refreshDependencies()
                refreshPhp()
                reportMessage(str(R.string.msg_repair_done))
            } catch (e: Exception) {
                reportError(e.message ?: str(R.string.msg_repair_fail))
            } finally {
                _isRepairingRuntime.value = false
                runDiagnostics()
            }
        }
    }

    private fun buildDiagnosticReport(): DiagnosticReport {
        val cfg = config.value
        val runtime = termuxRuntime
        val active = cfg.installedCores.find { it.name == cfg.activeCoreName }
        val checks = mutableListOf<DiagnosticCheck>()
        val runtimeReady = runtime.isReady()
        checks += DiagnosticCheck(
            "runtime", str(R.string.diag_check_runtime),
            if (runtimeReady) str(R.string.diag_check_runtime_ok) else str(R.string.diag_check_runtime_bad),
            if (runtimeReady) DiagnosticStatus.Pass else DiagnosticStatus.Failed, !runtimeReady
        )
        val javaCheck = runtime.javaRuntimeDiagnostic(cfg.selectedJavaVersion, cfg.selectedJavaVendor)
        checks += DiagnosticCheck(
            "java", str(R.string.diag_check_java, cfg.selectedJavaVersion.displayName), javaCheck.second,
            if (javaCheck.first) DiagnosticStatus.Pass else DiagnosticStatus.Failed, !javaCheck.first
        )
        // PRoot 容器分层状态：把「rootfs 没下好」与「容器好了但 Java 没装」区分开。
        // 只报一句「未安装」会把两种代价完全不同的情况混为一谈 ——
        // 前者要重新跑一次几百兆的下载，后者只需重装一个 apt 包。
        ProotDistro.entries.forEach { distro ->
            val state = runtime.prootContainerState(distro)
            val title = str(R.string.diag_check_proot_container, distro.displayName)
            if (!state.rootfsPresent && !state.readyMarkerPresent) {
                // 完全没碰过这个发行版：不算问题，避免刷出两条无意义的红色告警
                checks += DiagnosticCheck(
                    "proot-${distro.name}", title,
                    str(R.string.diag_proot_none),
                    DiagnosticStatus.NotApplicable
                )
            } else {
                // installedVersions 非空时 joinToString 必然非空，无需再兜 "—"
                val versions = state.installedVersions.joinToString(" / ") { it.displayName }
                val (detail, status) = when {
                    !state.shellUsable -> str(R.string.diag_proot_shell_broken) to DiagnosticStatus.Failed
                    state.installedVersions.isEmpty() -> str(R.string.diag_proot_ready_empty) to DiagnosticStatus.Warning
                    else -> str(R.string.diag_proot_ready_installed, versions) to DiagnosticStatus.Pass
                }
                checks += DiagnosticCheck(
                    "proot-${distro.name}", title, detail, status,
                    // 只有「解压不完整」值得走一键修复；缺 Java 属于用户选择，不该替他装
                    repairable = !state.shellUsable && state.rootfsPresent
                )
            }
        }
        if (active?.core == ServerCore.NeoForge && cfg.selectedJavaVersion != JavaVersion.Java8) {
            checks += DiagnosticCheck(
                "native-jna", str(R.string.diag_check_native),
                str(R.string.diag_check_native_detail),
                DiagnosticStatus.Warning
            )
        }
        // proot 是所有来宾容器链路的必需命令：Termux 的 Java 8 与 Proot 的全部版本
        // 都要靠它启动。早先这里只判 `Termux && Java8`，于是选中「PRoot 容器」来源时
        // prootRequired 为 false —— 诊断会报告「无需 proot」并标为 NotApplicable，
        // 而 proot 正是这条链路赖以启动的东西。容器起不来时这条会把用户引向错误方向。
        // 统一走路由谓词，将来新增来宾组合也不会漏判。
        val prootRequired = isProotGuestRuntime(cfg.selectedJavaVendor, cfg.selectedJavaVersion)
        val downloaderRequired = active?.core?.needsInstaller == true
        val requiredCommandsReady = (!prootRequired || runtime.isCommandInstalled("proot")) &&
            (!downloaderRequired || runtime.isCommandInstalled("wget"))
        checks += DiagnosticCheck(
            "commands", str(R.string.diag_check_commands),
            when {
                !prootRequired && !downloaderRequired -> str(R.string.diag_check_commands_none)
                requiredCommandsReady -> str(R.string.diag_check_commands_ready, if (prootRequired) "proot" else "wget")
                else -> str(R.string.diag_check_commands_missing2, listOfNotNull(if (prootRequired) "proot" else null, if (downloaderRequired) "wget" else null).joinToString(" / "))
            },
            if (!prootRequired && !downloaderRequired) DiagnosticStatus.NotApplicable else if (requiredCommandsReady) DiagnosticStatus.Pass else DiagnosticStatus.Warning,
            !requiredCommandsReady
        )
        if (active == null) {
            checks += DiagnosticCheck(
                "core", str(R.string.diag_check_core), str(R.string.diag_check_core_none), DiagnosticStatus.Warning
            )
            checks += DiagnosticCheck(
                "storage", str(R.string.diag_check_storage), str(R.string.diag_check_storage_none), DiagnosticStatus.NotApplicable
            )
            checks += DiagnosticCheck(
                "port", str(R.string.diag_check_port), str(R.string.diag_check_port_none), DiagnosticStatus.NotApplicable
            )
        } else {
            val dir = runtime.serverDirFor(active.dirName)
            val entryName = active.serverFile?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
            val jar = entryName?.let { File(dir, it) }
            val dirReady = dir.isDirectory && dir.canRead() && dir.canWrite()
            checks += DiagnosticCheck(
                "core", str(R.string.diag_check_core),
                if (!dirReady) str(R.string.diag_check_core_dir_bad) else if (jar?.isFile != true) str(R.string.diag_check_core_entry_missing, entryName ?: "未指定") else str(R.string.diag_check_core_ok, active.name),
                if (dirReady && jar?.isFile == true) DiagnosticStatus.Pass else DiagnosticStatus.Failed
            )
            val fontNeeded = runtime.needsFontRuntime(active.core)
            // 用接收 vendor 的版本：Proot / Termux Java 8 的字体在容器内，
            // 只看宿主文件会把「容器缺字体」误报成「已就绪」。
            val fontsReady = fontNeeded &&
                runtime.fontRuntimeDiagnostic(cfg.selectedJavaVendor, cfg.selectedJavaVersion)
            checks += DiagnosticCheck(
                "fonts", str(R.string.diag_check_fonts),
                when {
                    !fontNeeded -> str(R.string.diag_check_fonts_not_needed)
                    fontsReady -> str(R.string.diag_check_fonts_ready)
                    else -> str(R.string.diag_check_fonts_need)
                },
                when {
                    !fontNeeded -> DiagnosticStatus.NotApplicable
                    fontsReady -> DiagnosticStatus.Pass
                    else -> DiagnosticStatus.Warning
                }, fontNeeded
            )
            val available = try { if (dir.exists()) android.os.StatFs(dir.path).availableBytes else null } catch (_: Exception) { null }
            checks += DiagnosticCheck(
                "storage", str(R.string.diag_check_storage),
                available?.let { str(R.string.diag_check_storage_avail, formatDiagnosticBytes(it)) } ?: str(R.string.diag_check_storage_unreadable),
                if (available != null) DiagnosticStatus.Pass else DiagnosticStatus.Warning
            )
            val running = runtime.isMcRunning()
            val bedrock = active.core == ServerCore.PowerNukkitX
            // 端口取真源：配置文件里的 server-port（PNX 为 pnx.yml 的 settings.port），
            // 未生成时退回 localPort。与地址栏同源，避免「诊断说 19132、服务端其实在 25562」。
            val port = ServerPort.resolve(serverProperties.value["server-port"], active?.core, cfg.localPort)
            val portOk = if (running) {
                if (bedrock) probeBedrockUdpPort(port) else probeLoopbackPort(port)
            } else false
            val runningSince = serverState.value.runningSinceMs
            val starting = running && runningSince > 0L &&
                android.os.SystemClock.elapsedRealtime() - runningSince < 15_000L
            checks += DiagnosticCheck(
                "port", str(R.string.diag_check_port_title, if (bedrock) "UDP" else "TCP", port),
                when { !running -> str(R.string.diag_check_port_not_running); portOk -> str(R.string.diag_check_port_ok, port); starting -> str(R.string.diag_check_port_starting); else -> str(R.string.diag_check_port_bad2) },
                when { !running -> DiagnosticStatus.NotApplicable; portOk -> DiagnosticStatus.Pass; else -> DiagnosticStatus.Warning }
            )
            val crash = (crashReportManager.listCrashReports() + crashReportManager.listNativeCrashReports(active.dirName))
                .maxByOrNull { it.createdTime }
            checks += DiagnosticCheck(
                "crash", str(R.string.diag_check_crash),
                crash?.let { str(R.string.diag_check_crash_found, it.fileName) } ?: str(R.string.diag_check_crash_none),
                if (crash == null) DiagnosticStatus.Pass else DiagnosticStatus.Warning
            )
        }
        return DiagnosticReport(checks, System.currentTimeMillis(), termuxRuntime.isMcRunning())
    }

    private fun probeLoopbackPort(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_500); true }
    } catch (_: Exception) { false }

    private fun probeBedrockUdpPort(port: Int): Boolean = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = 1_500
            val packet = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN)
                .put(0x01.toByte())
                .putLong(System.currentTimeMillis())
                .put(byteArrayOf(0x00, 0xff.toByte(), 0xff.toByte(), 0x00, 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0x12, 0x34, 0x56, 0x78))
                .putLong(System.nanoTime()).array()
            socket.send(DatagramPacket(packet, packet.size, java.net.InetAddress.getByName("127.0.0.1"), port))
            val response = DatagramPacket(ByteArray(2048), 2048)
            socket.receive(response)
            response.length > 0
        }
    } catch (_: Exception) { false }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}

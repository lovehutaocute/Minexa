package com.mineserve.mobile.runtime

import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 命令执行器（Termux 原生模式，不依赖 proot）：
 *  - 直接用 rootfs 里的 bash 执行命令
 *  - 设置 LD_LIBRARY_PATH 指向 rootfs/lib
 *  - 日志通过文件监视实现（替代 LocalSocket）
 */
class CommandExecutor(
    private val installer: BootstrapInstaller,
    /**
     * 随 APK 打包的共享库目录（nativeLibraryDir），用于兜底链接缺失的 Termux 依赖。
     * 为 null 时行为与加这个参数之前完全一致。
     */
    private val extraLibraryDir: String? = null
) {

    /** 一次带超时的执行结果：退出码 + 输出尾部。 */
    data class ExecResult(val code: Int, val tail: String)

    @Volatile
    private var interactiveInput: java.io.OutputStream? = null

    private val _consoleFlow = MutableSharedFlow<String>(
        replay = 256,
        extraBufferCapacity = 2048
    )
    val consoleFlow: SharedFlow<String> = _consoleFlow.asSharedFlow()

    private var logWatcher: FileObserver? = null
    private var lastLogPos: Long = 0L

    fun emit(line: String) {
        _consoleFlow.tryEmit(line)
    }

    /** 使用 Android 系统 sh 执行，避免 app_data_file 执行限制 */
    private fun buildExecCommand(command: List<String>): List<String> {
        val sh = "/system/bin/sh"
        val prefix = installer.rootDir.absolutePath
        // 显式设置 PATH 和 LD_LIBRARY_PATH，确保 /system/bin/sh 能找到 Termux 命令
        // ProcessBuilder.environment() 在某些 Android 版本上可能不正确传递 PATH
        // LD_LIBRARY_PATH 需包含 usr/lib/（Termux compat 实际解压路径），否则 proot 找不到 libtalloc.so.2
        val compatUsr = "$prefix/data/data/com.termux/files/usr"
        val compatUsrLib = "$compatUsr/lib"
        // java.io.tmpdir 在 Android 上是 app 私有 cache 目录，用于暴露打包进来的 Termux 共享库
        val bundledLib = extraLibraryDir
        val libPath = buildList {
            add("$prefix/lib")
            add(compatUsrLib)
            add("$prefix/usr/lib")
            bundledLib?.let { add(it) }
            add("/system/lib64")
        }.joinToString(":")
        val envSetup = "export PATH='$prefix/bin:$prefix/usr/bin:$compatUsr/bin:$prefix/bin/applets:$prefix/libexec:/system/bin:/system/xbin'; " +
            "export LD_LIBRARY_PATH='$libPath'; " +
            "export FONTCONFIG_PATH='$prefix/etc/fonts'; " +
            "export FONTCONFIG_FILE='$prefix/etc/fonts/fonts.conf'; " +
            "export PREFIX='$prefix'; " +
            "export HOME='$prefix/home'; " +
            "export TMPDIR='$prefix/tmp'; "
        val cmdStr = command.joinToString(" ") { arg ->
            if (arg.any { it == ' ' || it == '\'' || it == '"' || it == '$' }) {
                "'${arg.replace("'", "'\\''")}'"
            } else arg
        }
        return listOf(sh, "-c", envSetup + cmdStr)
    }

    /** Termux 环境变量 */
    fun termuxEnv(): Map<String, String> {
        val prefix = installer.rootDir.absolutePath
        val caBundle = "$prefix/etc/ssl/certs/ca-certificates.crt"
        // compat 路径：dpkg-deb -x 解包时 compat 符号链接被覆盖，库实际落在 usr/lib/
        // 必须加入 LD_LIBRARY_PATH，否则 proot 找不到 libtalloc.so.2
        val compatUsr = "$prefix/data/data/com.termux/files/usr"
        val compatUsrLib = "$compatUsr/lib"
        // Termux rootfs 的共享库标准位置为 $PREFIX/usr/lib（bash 依赖的 readline/ncurses
        // 等都在这里）；仅靠 $prefix/lib 会导致这些命令启动失败（退出码 126）。
        // 顺序：$prefix/lib → compat 实际落点 → Termux 标准 usr/lib → 系统库
        val libPath = listOfNotNull(
            "$prefix/lib",
            compatUsrLib,
            "$prefix/usr/lib",
            extraLibraryDir,
            "/system/lib64"
        ).joinToString(":")
        return mapOf(
            "HOME" to "$prefix/home",
            "PATH" to "$prefix/bin:$prefix/usr/bin:$compatUsr/bin:$prefix/bin/applets:$prefix/libexec:/system/bin:/system/xbin",
            "TMPDIR" to "$prefix/tmp",
            "LD_LIBRARY_PATH" to libPath,
            "FONTCONFIG_PATH" to "$prefix/etc/fonts",
            "FONTCONFIG_FILE" to "$prefix/etc/fonts/fonts.conf",
            "PREFIX" to "$prefix",
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "COLORTERM" to "true",
            "APT_CONFIG" to "$prefix/etc/apt/apt.conf",
            "DPKG_ADMINDIR" to "$prefix/var/lib/dpkg",
            "DPKG_CONFIGDIR" to "$prefix/etc/dpkg/dpkg.cfg.d",
            "DEBIAN_FRONTEND" to "noninteractive",
            // SSL 证书：让 apt 的 https 方法驱动、curl、wget 等能找到 CA 证书
            "SSL_CERT_FILE" to caBundle,
            "CURL_CA_BUNDLE" to caBundle,
            "REQUESTS_CA_BUNDLE" to caBundle,
            "SSL_CERT_DIR" to "$prefix/etc/ssl/certs",
            "GIT_SSL_CAINFO" to caBundle
        )
    }

    /** 一次性命令，阻塞等待完成 */
    fun execOnce(vararg command: String, env: Map<String, String> = emptyMap()): Int {
        val full = buildExecCommand(command.toList())
        Log.d(TAG, "execOnce: ${full.joinToString(" ").take(200)}")
        val pb = ProcessBuilder(full).apply {
            redirectErrorStream(true)
            // stdin 重定向到 /dev/null，避免 apt-get/dpkg 等命令阻塞等待用户输入
            redirectInput(File("/dev/null"))
            directory(File(installer.rootDir, "home").apply { mkdirs() })
            environment().putAll(termuxEnv())
            env.forEach { (k, v) -> environment()[k] = v }
        }
        val process = pb.start()
        // 确保关闭进程的 stdin（某些情况下 redirectInput 不生效）
        process.outputStream.close()
        BufferedReader(InputStreamReader(process.inputStream)).useLines { seq ->
            seq.forEach { line ->
                _consoleFlow.tryEmit(line)
                // 同时输出到 logcat 便于调试
                Log.d(TAG, "  | $line")
            }
        }
        return process.waitFor()
    }

    /** Execute a setup command with an app-side deadline; coreutils timeout may be unavailable. */
    fun execOnceWithTimeout(
        timeoutMs: Long,
        vararg command: String,
        env: Map<String, String> = emptyMap()
    ): Int = execOnceWithTimeoutCapture(timeoutMs, *command, env = env).code

    /**
     * 同 [execOnceWithTimeout]，但把输出尾部一并带回。
     *
     * 为什么需要：容器里跑的 proot 崩溃时**不会**改变 shell 脚本自己的退出码 ——
     * 脚本被 SIGSEGV 打断后由 proot 的退出码顶上，脚本里精心写的「第几步失败」
     * 就全丢了，Java 侧只看到一个 exit=1。要判断「是不是 proot 自己崩了」
     * 只能看它吐在 stderr 里的 `proot warning: signal 11 ...`。
     *
     * 输出仍照常流进 [consoleFlow]；这里只是额外留一份尾部文本。
     * 只保留尾部若干行：apt 输出可达数千行，而诊断只需要最后那一截。
     */
    fun execOnceWithTimeoutCapture(
        timeoutMs: Long,
        vararg command: String,
        env: Map<String, String> = emptyMap(),
        tailLines: Int = 80
    ): ExecResult {
        val full = buildExecCommand(command.toList())
        Log.d(TAG, "execOnceWithTimeout[$timeoutMs]: ${full.joinToString(" ").take(200)}")
        val tail = ArrayDeque<String>()
        val pb = ProcessBuilder(full).apply {
            redirectErrorStream(true)
            directory(File(installer.rootDir, "home").apply { mkdirs() })
            environment().putAll(termuxEnv())
            env.forEach { (k, v) -> environment()[k] = v }
        }
        val process = pb.start()
        process.outputStream.close()
        val reader = Thread {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        _consoleFlow.tryEmit(line)
                        Log.d(TAG, "  | $line")
                        synchronized(tail) {
                            tail.addLast(line)
                            while (tail.size > tailLines) tail.removeFirst()
                        }
                    }
                }
            }
        }
        reader.start()
        val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!completed) {
            Log.w(TAG, "execOnceWithTimeout: command timed out after ${timeoutMs}ms")
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            reader.join(2_000)
            return ExecResult(124, synchronized(tail) { tail.joinToString("\n") })
        }
        reader.join(2_000)
        val text = synchronized(tail) { tail.joinToString("\n") }
        lastTail = text
        return ExecResult(process.exitValue(), text)
    }

    /** 最近一次 [execOnceWithTimeoutCapture] 的输出尾部，供调用方在失败后判定原因。 */
    @Volatile
    var lastTail: String = ""
        private set

    /**
     * 执行命令并把输出逐行回调给 onLine（不进入 consoleFlow，避免与 MC 服务器日志混流）。
     * 供 Termux 终端面板使用；单次执行，输出实时回调。
     */
    fun execWithOutput(
        vararg command: String,
        env: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit
    ): Int {
        val full = buildExecCommand(command.toList())
        Log.d(TAG, "execWithOutput: ${full.joinToString(" ").take(200)}")
        val pb = ProcessBuilder(full).apply {
            redirectErrorStream(true)
            // 这里**不能** redirectInput(/dev/null)：
            // 一旦把 stdin 重定向到文件，Process.outputStream 就变成 Java 的 null output stream，
            // 写入既不报错也不生效。而下面马上把这个流赋给 interactiveInput、由
            // sendInteractiveInput 往上写——于是用户在终端面板敲的 y/n、read、python 输入
            // 全被静默丢弃，UI 却照常回显「> 输入」，命令永远卡住。
            // 保持 PIPE（默认）才能让交互真正生效。
            directory(File(installer.rootDir, "home").apply { mkdirs() })
            environment().putAll(termuxEnv())
            env.forEach { (k, v) -> environment()[k] = v }
        }
        val process = pb.start()
        interactiveInput = process.outputStream
        try {
            BufferedReader(InputStreamReader(process.inputStream)).useLines { seq ->
                seq.forEach(onLine)
            }
            return process.waitFor()
        } finally {
            if (interactiveInput === process.outputStream) interactiveInput = null
            runCatching { process.outputStream.close() }
        }
    }

    fun sendInteractiveInput(input: String): Boolean = runCatching {
        val stream = interactiveInput ?: return false
        stream.write((input + "\n").toByteArray(Charsets.UTF_8))
        stream.flush()
        true
    }.getOrDefault(false)

    /**
     * 后台长期命令（如 tmux new-session -d）。
     * 输出流入 consoleFlow 供 UI 订阅
     */
    fun execStream(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process {
        val full = buildExecCommand(command.toList())
        Log.d(TAG, "execStream[$tag]: ${full.joinToString(" ").take(200)}")
        val pb = ProcessBuilder(full).apply {
            redirectErrorStream(true)
            directory(File(installer.rootDir, "home").apply { mkdirs() })
            environment().putAll(termuxEnv())
            env.forEach { (k, v) -> environment()[k] = v }
        }
        val process = pb.start()
        Thread({
            BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    _consoleFlow.tryEmit("[$tag] $line")
                    line = r.readLine()
                }
            }
        }, "exec-$tag-reader").start()
        return process
    }

    /**
     * 启动长驻进程但不启动 reader 线程。
     * 调用方需自行读取 [Process.getInputStream] 并处理输出。
     * 用于 TunnelManager 等需要解析进程输出（提取公网 URL）的场景。
     */
    fun execRaw(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process {
        val full = buildExecCommand(command.toList())
        Log.d(TAG, "execRaw[$tag]: ${full.joinToString(" ").take(200)}")
        val pb = ProcessBuilder(full).apply {
            redirectErrorStream(true)
            // stdin 重定向到 /dev/null，防止 ngrok/cloudflared 读 stdin 阻塞
            redirectInput(File("/dev/null"))
            directory(File(installer.rootDir, "home").apply { mkdirs() })
            environment().putAll(termuxEnv())
            env.forEach { (k, v) -> environment()[k] = v }
        }
        return pb.start()
    }

    /**
     * 启动日志文件监视：当 MC 服务器日志文件更新时，读取新行推送到 consoleFlow。
     * 替代之前的 ConsoleSocketServer 方案，更简单可靠。
     */
    fun startLogWatcher(logFile: File) {
        stopLogWatcher()
        if (!logFile.exists()) {
            logFile.parentFile?.mkdirs()
            logFile.createNewFile()
        }
        lastLogPos = logFile.length()

        logWatcher = object : FileObserver(logFile.absolutePath, MODIFY or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                try {
                    val len = logFile.length()
                    if (len < lastLogPos) {
                        lastLogPos = 0
                    }
                    if (len > lastLogPos) {
                        RandomAccessFile(logFile, "r").use { raf ->
                            raf.seek(lastLogPos)
                            val data = ByteArray((len - lastLogPos).toInt())
                            raf.readFully(data)
                            String(data).split("\n").forEach { line ->
                                if (line.isNotBlank()) {
                                    _consoleFlow.tryEmit(line)
                                }
                            }
                        }
                        lastLogPos = len
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "logWatcher error: ${e.message}")
                }
            }
        }
        logWatcher?.startWatching()
        Log.i(TAG, "logWatcher started on ${logFile.absolutePath}")
    }

    fun stopLogWatcher() {
        logWatcher?.stopWatching()
        logWatcher = null
    }

    companion object { private const val TAG = "CommandExecutor" }
}

package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.JavaRuntime
import com.mineserve.mobile.data.JavaVendor
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class JavaRuntimeDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val termuxRuntime: TermuxRuntime,
    private val config: StateFlow<McConfig>,
    private val isBootstrapped: StateFlow<Boolean>,
    private val isInstalling: StateFlow<Boolean>,
    private val setInstalling: (Boolean) -> Unit,
    private val updateAndSaveConfig: ((McConfig) -> McConfig) -> McConfig,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val _javaOperation = MutableStateFlow<String?>(null)
    val javaOperation: StateFlow<String?> = _javaOperation.asStateFlow()

    /**
     * 正在安装的那一项运行环境（来源 + 版本），用于把进度圈钉在对应的版本行上。
     *
     * 进度是长任务（FCL 那套要下 37–62MB），只给一句全局文案的话，
     * 用户无从判断「现在到底在下哪一套」。
     */
    private val _javaInFlight = MutableStateFlow<JavaRuntime?>(null)
    val javaInFlight: StateFlow<JavaRuntime?> = _javaInFlight.asStateFlow()

    /**
     * 已安装的运行环境集合。
     *
     * 用 [JavaRuntime]（来源 + 版本）而不是裸版本号：Termux 的 Java 17 与 FCL 的
     * Java 17 是两份不同的文件，只按版本号记会把两者混成一个。
     */
    private val _installedJava = MutableStateFlow<Set<JavaRuntime>>(emptySet())
    val installedJava: StateFlow<Set<JavaRuntime>> = _installedJava.asStateFlow()

    fun setJavaOperation(value: String?) {
        _javaOperation.value = value
    }

    fun refreshJava() {
        // 刻意不检查 isBootstrapped：FCL 来源的 JRE 与 Termux 环境无关，
        // 环境还没初始化时它也可能已经装好了（装了就该显示出来）。
        scope.launch(Dispatchers.IO) {
            _installedJava.value = termuxRuntime.installedJavaRuntimes()
        }
    }

    /**
     * 安装运行环境。
     *
     * 刻意不要求 Termux 环境已初始化：FCL 来源的 JRE 是 Android 原生二进制，
     * 与 Termux 前缀无关，环境没装好时也完全可以先把它下载下来（Termux 来源的
     * 安装会自己在运行时里抛「环境未初始化」）。
     *
     * 装完直接把它设为当前选用 —— 用户点安装就是为了用它，装完还要再点一次
     * 选中是没必要的往返；启动前本就会校验 java 是否找得到，所以不存在
     * 「选中了一个装不上的东西」的风险。
     */
    fun installJava(runtime: JavaRuntime) {
        if (isInstalling.value) return
        setInstalling(true)
        _javaInFlight.value = runtime
        _javaOperation.value = str(R.string.msg_java_op_installing, runtime.displayName)
        scope.launch {
            try {
                val ok = termuxRuntime.installJava(
                    version = runtime.version,
                    vendor = runtime.vendor,
                    // PRoot 来源必须带上当前发行版：装哪个发行版里的 JDK
                    // 由用户的选择决定，不能用默认值顶替。
                    distro = config.value.prootDistro,
                    onProgress = { percent ->
                        // FCL 按下载字节数回调；PRoot 按阶段回调（rootfs 下载解压 0–60、
                        // 容器内 apt 安装 60–100）。两者都是数分钟级的长任务，
                        // 不显示进度会让用户以为卡死了。Termux 宿主 apt 无细粒度进度。
                        _javaOperation.value =
                            str(R.string.msg_java_op_installing, runtime.displayName) + " $percent%"
                    }
                )
                if (ok) {
                    // 用原子读改写并落盘：安装耗时长，期间用户其他配置修改不能被这份旧快照冲掉
                    updateAndSaveConfig {
                        it.copy(selectedJavaVendor = runtime.vendor, selectedJavaVersion = runtime.version)
                    }
                    refreshJava()
                    reportMessage(str(R.string.msg_java_installed, runtime.displayName))
                } else reportError(str(R.string.err_java_install_fail, runtime.displayName))
            } catch (e: Exception) { reportError(e.message ?: str(R.string.msg_java_install_fail2)) }
            finally {
                _javaInFlight.value = null
                _javaOperation.value = null
                setInstalling(false)
            }
        }
    }

    /**
     * 重装**指定的这一份**运行环境：先删掉它，再重新安装。
     *
     * 刻意按 (来源, 版本) 精确重装，而不是沿用旧 UI 那个「清空全部 Java 再装回」
     * 的粗粒度路径 —— 现在有两套来源、五加三共八种组合，一次重装把别的版本一起
     * 删掉，与依赖页那句「操作作用于所选版本」的提示自相矛盾，也白白浪费用户
     * 已经下好的 37–62MB。Termux 那套仍然要求环境已初始化；FCL 那套与 Termux
     * 前缀无关，环境没装好也能重装。
     */
    fun reinstallJava(runtime: JavaRuntime) {
        if (isInstalling.value) return
        if (runtime.vendor == JavaVendor.Termux && !isBootstrapped.value) return
        if (termuxRuntime.isMcRunning()) {
            reportError(str(R.string.err_java_running))
            return
        }
        if (runtime !in _installedJava.value) {
            // 没有可删的旧文件，直接走安装
            installJava(runtime)
            return
        }
        setInstalling(true)
        _javaInFlight.value = runtime
        _javaOperation.value = str(R.string.msg_java_op_reinstall)
        scope.launch {
            var removed = false
            try {
                removed = withContext(Dispatchers.IO) {
                    termuxRuntime.deleteJava(runtime.version, runtime.vendor)
                }
                if (removed) refreshJava()
                else reportError(str(R.string.err_java_reinstall_fail))
            } catch (e: Exception) {
                reportError(e.message ?: str(R.string.msg_java_reinstall_fail2))
            } finally {
                // 先解除删除阶段的占用，installJava 会把三个状态重新置位
                setInstalling(false)
                _javaInFlight.value = null
                _javaOperation.value = null
            }
            if (removed) installJava(runtime)
        }
    }

    /** 卸载指定运行环境（Proot 走容器内 apt purge，只删该版本；其余来源删文件） */
    fun deleteJava(runtime: JavaRuntime) {
        if (isInstalling.value) return
        _javaOperation.value = str(R.string.msg_java_op_deleting, runtime.displayName)
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    termuxRuntime.deleteJava(runtime.version, runtime.vendor)
                }
                refreshJava()
                if (ok) reportMessage(str(R.string.msg_java_deleted, runtime.displayName))
                else reportError(str(R.string.err_java_delete_fail, runtime.displayName))
            } catch (e: Exception) {
                reportError(e.message ?: str(R.string.err_java_delete_fail, runtime.displayName))
            } finally {
                _javaOperation.value = null
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}

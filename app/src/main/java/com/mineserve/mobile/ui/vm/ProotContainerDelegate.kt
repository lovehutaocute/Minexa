package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.ProotDistro
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ProotContainerDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val termuxRuntime: TermuxRuntime,
    private val isInstalling: StateFlow<Boolean>,
    private val setJavaOperation: (String?) -> Unit,
    private val refreshJava: () -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    /** 容器占用空间（字节），供删除确认弹窗显示「可回收多少」。 */
    fun prootContainerSizeBytes(distro: ProotDistro): Long =
        termuxRuntime.prootContainerSizeBytes(distro)

    /**
     * 各发行版容器占用空间（字节），**异步**填充。
     *
     * 为什么不直接在 Composable 里调 [prootContainerSizeBytes]：
     * 它内部是 `walkTopDown()` 全量递归 rootfs（数万个文件），属于重量级 IO。
     * 旧代码在 EnvManagerScreen 里同步调用，且 remember 的 key 含每次刷新
     * 都会换新实例的 `installed`，于是进页面、切发行版、每次刷新 Java 状态
     * 都会重新扫一遍整个 rootfs —— 这就是依赖管理页卡顿的主因。
     *
     * 现在改为：进页面时在 IO 线程算一次，结果放进 StateFlow 供 UI 读取。
     */
    private val _prootContainerSizes = MutableStateFlow<Map<ProotDistro, Long>>(emptyMap())
    val prootContainerSizes: StateFlow<Map<ProotDistro, Long>> = _prootContainerSizes.asStateFlow()

    /** 刷新所有发行版容器占用（IO 线程，供依赖管理页展示「删除可回收多少空间」）。 */
    fun refreshProotContainerSizes() {
        scope.launch(Dispatchers.IO) {
            val sizes = ProotDistro.entries.associateWith { distro ->
                termuxRuntime.prootContainerSizeBytes(distro)
            }
            _prootContainerSizes.value = sizes
        }
    }

    /**
     * 删除整个 PRoot 容器（连同容器内已装的全部 Java 版本）。
     *
     * 与 [deleteJava] 是**两个不同粒度**的操作，所以入口也分开：
     * - [deleteJava]：卸载某一个 Java 版本，容器与其余版本保留（apt purge）
     * - 本方法：回收整个容器（直接删 rootfs），用于磁盘空间吃紧时一次性清干净
     *
     * 若混成一个按钮，用户点「卸载 Java 17」却把 Java 8/11 一起清掉，
     * 那是数据静默丢失级别的问题。
     */
    fun deleteProotContainer(distro: ProotDistro) {
        if (isInstalling.value) return
        if (termuxRuntime.isMcRunning()) {
            reportError(str(R.string.err_java_running))
            return
        }
        setJavaOperation(str(R.string.msg_java_op_deleting, distro.displayName))
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    termuxRuntime.deleteProotContainer(distro)
                }
                refreshJava()
                if (ok) reportMessage(str(R.string.msg_proot_container_deleted, distro.displayName))
                else reportError(str(R.string.err_proot_container_delete_fail, distro.displayName))
            } catch (e: Exception) {
                reportError(e.message ?: str(R.string.err_proot_container_delete_fail, distro.displayName))
            } finally {
                setJavaOperation(null)
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}

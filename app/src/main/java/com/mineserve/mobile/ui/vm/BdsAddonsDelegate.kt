package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.server.BdsPacks
import com.mineserve.mobile.server.WorldManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BdsAddonsDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    worldManagerProvider: () -> WorldManager,
    private val activeDirName: () -> String?,
    private val refreshActiveCore: () -> Unit,
    private val requireServerStopped: (String) -> Boolean,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    private val worldManager by lazy(worldManagerProvider)

    private val _addons = MutableStateFlow<BdsPacks.Scan?>(null)
    val addons: StateFlow<BdsPacks.Scan?> = _addons.asStateFlow()

    private val _addonsLoading = MutableStateFlow(false)
    val addonsLoading: StateFlow<Boolean> = _addonsLoading.asStateFlow()

    /**
     * `texturepack-required` 的当前值；null = 当前核心不支持（PNX 走 pnx.yml）。
     *
     * 它是 `server.properties` 里唯一与资源包有关的键，语义是"进服前必须下载本服登记的
     * 全部资源包"。UI 上必须与资源包清单一起看：开着它却一个资源包都没登记，等于什么都没做。
     */
    private val _texturePackRequired = MutableStateFlow<Boolean?>(null)
    val texturePackRequired: StateFlow<Boolean?> = _texturePackRequired.asStateFlow()

    /**
     * 重新扫描附加包。
     *
     * 扫描范围包含全服目录与世界目录：BDS 对两处都会加载，且**世界目录里的同名包优先**
     * （实测，见 `docs/BDS-ADDONS-WORLDS-VERIFICATION.md` §11.3）。
     */
    fun loadAddons() {
        refreshActiveCore()
        val dirName = activeDirName() ?: return
        scope.launch {
            _addonsLoading.value = true
            try {
                val scan = withContext(Dispatchers.IO) {
                    val world = worldManager.readLevelName(dirName)
                    BdsPacks.scan(
                        serverDir = worldManager.serverDir(dirName),
                        worldName = world,
                        builtinUuids = BdsPacks.readBuiltinUuids(worldManager.serverDir(dirName))
                    )
                }
                _addons.value = scan
                _texturePackRequired.value = withContext(Dispatchers.IO) {
                    runCatching { worldManager.readTexturePackRequired(dirName) }.getOrNull()
                }
            } catch (e: Exception) {
                reportError("${str(R.string.ad_load_failed)}：${e.message}")
            } finally {
                _addonsLoading.value = false
            }
        }
    }

    /**
     * 启用 / 停用某个包：只改当前世界的 `world_*_packs.json`。
     *
     * BDS 只在启动时读这两个文件，运行中改动不会生效，所以运行中直接拒绝而不是静默写入。
     */
    fun setAddonEnabled(pack: BdsPacks.Pack, enabled: Boolean) {
        val dirName = activeDirName() ?: return
        if (!requireServerStopped(str(if (enabled) R.string.ad_enable else R.string.ad_disable))) return
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    val world = worldManager.readLevelName(dirName)
                    BdsPacks.setEnabled(
                        worldDir = worldManager.worldDir(dirName, world),
                        kind = pack.kind,
                        entry = BdsPacks.Entry(pack.uuid, pack.version),
                        enabled = enabled
                    )
                }
                if (ok) {
                    reportMessage(str(if (enabled) R.string.ad_enabled else R.string.ad_disabled, pack.name))
                    loadAddons()
                } else {
                    reportError(str(R.string.ad_write_failed))
                }
            } catch (e: Exception) {
                reportError("${str(R.string.ad_write_failed)}：${e.message}")
            }
        }
    }

    /**
     * 切换「强制客户端下载资源包」（`texturepack-required`）。
     *
     * 只写 `server.properties`，BDS 每次启动会把它同步进 `level.dat:texturePacksRequired`。
     * 开启时如果世界一个资源包都没登记，提示语要说清楚"开了但没东西可下发"，否则用户会
     * 以为是客户端坏了。
     */
    fun setTexturePackRequired(required: Boolean) {
        val dirName = activeDirName() ?: return
        if (!requireServerStopped(str(R.string.ad_tpr_title))) return
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    worldManager.setTexturePackRequired(dirName, required)
                }
                if (!ok) {
                    reportError(str(R.string.ad_write_failed))
                    return@launch
                }
                _texturePackRequired.value = required
                val empty = _addons.value?.enabledResource?.isEmpty() ?: true
                reportMessage(
                    when {
                        required && empty -> str(R.string.ad_tpr_on_empty)
                        required -> str(R.string.ad_tpr_on, _addons.value?.enabledResource?.size ?: 0)
                        else -> str(R.string.ad_tpr_off)
                    }
                )
            } catch (e: Exception) {
                reportError("${str(R.string.ad_write_failed)}：${e.message}")
            }
        }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}

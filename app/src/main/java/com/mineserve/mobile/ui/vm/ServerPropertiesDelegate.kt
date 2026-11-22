package com.mineserve.mobile.ui.vm

import android.content.Context
import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.ServerPort
import com.mineserve.mobile.server.PowerNukkitXConfigManager
import com.mineserve.mobile.server.ServerPropertiesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ServerPropertiesDelegate(
    private val app: Context,
    private val scope: CoroutineScope,
    private val config: StateFlow<McConfig>,
    private val propertiesManager: ServerPropertiesManager,
    private val powerNukkitXConfigManager: PowerNukkitXConfigManager,
    private val activeDirName: () -> String?,
    private val requireServerStopped: (String) -> Boolean,
    private val updateLocalPort: (Int) -> Unit,
    private val refreshLanIp: () -> Unit,
    private val reportMessage: (String) -> Unit,
    private val reportError: (String) -> Unit
) {
    fun isPowerNukkitXActive(): Boolean = config.value.installedCores
        .firstOrNull { it.name == config.value.activeCoreName }?.core == ServerCore.PowerNukkitX

    fun supportedServerPropertyKeys(): Set<String> =
        if (isPowerNukkitXActive()) powerNukkitXConfigManager.supportedKeys()
        else emptySet()

    private val _serverProperties = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverProperties: StateFlow<Map<String, String>> = _serverProperties.asStateFlow()

    val serverPort: StateFlow<Int> = combine(_serverProperties, config) { props, cfg ->
        ServerPort.resolve(props["server-port"], activeCoreType() ?: cfg.selectedCore, cfg.localPort)
    }.distinctUntilChanged().stateIn(
        scope, SharingStarted.WhileSubscribed(5_000),
        ServerPort.DEFAULT_JAVA
    )

    /** 当前激活核心的类型；未选择时返回 null。 */
    private fun activeCoreType(): ServerCore? = config.value.installedCores
        .firstOrNull { it.name == config.value.activeCoreName }?.core

    /**
     * **此刻**服务端真正在监听的端口。
     *
     * 与 [serverPort] 的区别：那个是 `WhileSubscribed(5s)` 的 StateFlow，没有订阅者时
     * `.value` 会退化成初始值，拿去启动穿透就会打到错误的端口上。启动链路上的调用方
     * 一律用这个函数 —— 它每次现算，不经过任何缓存。
     */
    fun realServerPort(): Int = ServerPort.resolve(
        _serverProperties.value["server-port"],
        activeCoreType() ?: config.value.selectedCore,
        config.value.localPort
    )

    private val _serverPropertiesExist = MutableStateFlow<Boolean?>(null)
    val serverPropertiesExist: StateFlow<Boolean?> = _serverPropertiesExist.asStateFlow()

    fun loadServerProperties() {
        scope.launch { refreshServerPropertiesNow() }
    }

    /**
     * 重新读一遍服务端配置文件，并**等它读完**。
     *
     * 为什么必须能「等」：端口真源历史上只在进入「服务器配置」页时才被读一次。
     * 用户在配置文件里把端口从 25565 改成别的，然后直接去开穿透 —— 隧道拿到的还是
     * 上一次的值，于是把玩家送到 25565，一个没人监听的端口。界面上隧道「已就绪」，
     * 日志里只有一句 `connect: connection refused`，从字面完全看不出是端口对不上。
     * 启动前先把真源读回来，这条坑就不存在了。
     */
    suspend fun refreshServerPropertiesNow() {
        val dirName = activeDirName() ?: return
        try {
            val pnx = isPowerNukkitXActive()
            // PNX 的真实配置是 pnx.yml，Java 系是 server.properties，两者要分别判定，
            // 否则 PNX 下永远看 server.properties 的存在性，导致 pnx.yml 还没生成就放行表单。
            val exists = withContext(Dispatchers.IO) {
                if (pnx) powerNukkitXConfigManager.exists(dirName)
                else propertiesManager.exists(dirName)
            }
            _serverPropertiesExist.value = exists
            val loaded = withContext(Dispatchers.IO) {
                if (pnx) powerNukkitXConfigManager.read(dirName)
                else propertiesManager.readProperties(dirName)
            }
            _serverProperties.value = loaded
            // 端口真源镜像：配置文件已生成且含合法端口时，把它同步回 [McConfig.localPort]。
            // 「服务器配置」页是端口唯一编辑入口（网络页的输入框已在 1.2.8 移除），
            // 不同步的话地址栏会一直停在旧值 —— 这正是「配置 25562、显示 19132」的成因。
            // 只在 exists 为真时同步：文件不存在时 loaded 是空 Map，会把 localPort 误覆盖成默认值。
            if (exists) ServerPort.parse(loaded["server-port"])?.let { port ->
                if (config.value.localPort != port) updateLocalPort(port)
            }
        } catch (e: Exception) {
            reportError(str(R.string.s261, e.message))
        }
    }

    fun saveServerProperties(props: Map<String, String>) {
        // PowerNukkitX 的配置落在 pnx.yml，而服务端退出时会整份回写该文件，
        // 运行期间的修改必然被覆盖；PowerNukkitXConfigManager.write 也会主动拒绝运行中写入。
        // 在这里提前拦截是为了给出明确原因，而不是让用户看到笼统的「保存失败」。
        if (isPowerNukkitXActive() && !requireServerStopped("修改 PowerNukkitX 配置")) return
        val dirName = activeDirName() ?: run {
            reportError(str(R.string.s249))
            return
        }
        scope.launch {
            try {
                // 返回值第二个元素是「本次实际生效的端口」：Java 系取提交值，缺省时退回
                // 文件原值再退回核心默认端口；PNX 系还要同时写 server.properties，
                // 那里的端口就是最终生效值。保存成功后据此回写 [McConfig.localPort]。
                val result = withContext(Dispatchers.IO) {
                    val current = propertiesManager.readProperties(dirName)
                    if (isPowerNukkitXActive()) {
                        val supported = props.filterKeys { it in powerNukkitXConfigManager.supportedKeys() }
                        val yamlOk = powerNukkitXConfigManager.write(dirName, supported)
                        val effectivePort = props["server-port"] ?: current["server-port"]
                            ?: ServerPort.defaultFor(ServerCore.PowerNukkitX).toString()
                        val serverProps = current.toMutableMap()
                        serverProps["server-port"] = effectivePort
                        (yamlOk && propertiesManager.writeProperties(serverProps, dirName)) to effectivePort
                    } else {
                        val core = activeCoreType() ?: config.value.selectedCore
                        val effectivePort = props["server-port"] ?: current["server-port"]
                            ?: ServerPort.defaultFor(core).toString()
                        propertiesManager.writeProperties(props, dirName) to effectivePort
                    }
                }
                if (result.first) {
                    reportMessage(str(R.string.s262))
                    // 合并而不是替换：属性页现在只提交「值变过的键」（无损保存），
                    // 直接赋值会把页面上没碰过的键从内存镜像里抹掉 —— 端口真源、
                    // 白名单提示都读这份镜像，抹掉后界面会显示回默认值。
                    _serverProperties.value = _serverProperties.value + props
                    // 端口真源回写：「服务器配置」页是端口唯一编辑入口（网络页的输入框已在
                    // 1.2.8 移除）。不回写的话地址栏/穿透默认端口会停在旧值 ——
                    // 用户改了 25562 而界面仍显示 19132 就是这么来的。
                    ServerPort.parse(result.second)?.let { port ->
                        if (config.value.localPort != port) updateLocalPort(port)
                    }
                    refreshLanIp()
                } else {
                    reportError(str(R.string.s263))
                }
            } catch (e: Exception) {
                reportError(str(R.string.s264, e.message))
            }
        }
    }

    fun setWhitelistProperty(key: String, enabled: Boolean) {
        _serverProperties.value = _serverProperties.value.toMutableMap()
            .apply { put(key, enabled.toString()) }
    }

    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)
}

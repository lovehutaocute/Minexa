package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.MinecraftVersionNormalizer
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.ServerPort

internal object CoreSelection {
    fun select(config: McConfig, core: ServerCore, coreChanged: Boolean): McConfig {
        // 基岩版核心（PNX/PocketMine/Allay/BDS）走 UDP，默认端口 19132；Java 系 25565。
        // 迁移规则见 [ServerPort.migrateOnCoreSwitch]：只在 Java 系 ↔ 基岩系之间
        // 对调**默认值**，用户自定义端口（如 25562）原样保留。
        // 同一个核心重复点击不动端口 —— 用户没换核心，端口不该被「迁移」一次。
        val port = if (coreChanged) {
            ServerPort.migrateOnCoreSwitch(config.localPort, config.selectedCore, core)
        } else {
            config.localPort
        }
        return config.copy(selectedCore = core, mcVersion = "", localPort = port)
    }

    inline fun setVersion(
        version: String,
        core: ServerCore,
        save: (ServerCore, String, String) -> Unit
    ) {
        val trimmed = version.trim()
        val normalized = MinecraftVersionNormalizer.forCore(core, trimmed)
        save(core, trimmed, normalized)
    }
}

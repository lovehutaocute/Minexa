package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.RuntimeKind
import com.mineserve.mobile.data.ServerCore

internal object RuntimeKinds {
    fun mismatchMessage(config: McConfig, str: (Int, List<Any?>) -> String): String? {
        val core = config.installedCores.firstOrNull { it.name == config.activeCoreName } ?: return null
        val required = when (core.core) {
            ServerCore.PocketMine -> RuntimeKind.Php
            // 官方 BDS 既不跑 JVM 也不跑 PHP：它是原生可执行文件，
            // arm64 设备上靠 box64 转译（容器由启动流程自行准备）。
            // 若仍按「Java 核心」要求 runtimeKind=Java，之前开过 PocketMine 的用户
            // 会被一条与 BDS 无关的提示挡住启动。
            ServerCore.Bds -> config.runtimeKind
            else -> RuntimeKind.Java
        }
        if (config.runtimeKind == required) return null
        val requiredLabel = when (required) {
            RuntimeKind.Java -> str(R.string.dash_runtime_java, emptyList())
            RuntimeKind.Php -> str(R.string.dash_runtime_php, emptyList())
        }
        return str(R.string.err_runtime_kind_mismatch, listOf(core.name, requiredLabel))
    }
}

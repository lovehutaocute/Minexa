package com.mineserve.mobile.ui.vm

import com.mineserve.mobile.R
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.JavaRuntime
import com.mineserve.mobile.data.JavaVendor
import com.mineserve.mobile.data.PhpVersion
import com.mineserve.mobile.data.ProotDistro
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

    fun withJavaRuntime(config: McConfig, runtime: JavaRuntime): McConfig {
        // Proot 来源的版本必须落在**当前发行版**提供的范围内。界面上点不到
        // 置灰项（见 JavaRuntimeGroupList），这里是第二道保险：
        // 写进 config 的组合一旦装不上，用户要到启动失败时才会发现。
        val version = if (runtime.vendor == JavaVendor.Proot) {
            runtime.version.takeIf { it in config.prootDistro.availableJavaVersions }
                ?: config.prootDistro.availableJavaVersions.firstOrNull()
                ?: runtime.version
        } else {
            runtime.version
        }
        return config.copy(selectedJavaVendor = runtime.vendor, selectedJavaVersion = version)
    }

    fun applySelection(
        config: McConfig,
        kind: RuntimeKind,
        javaRuntime: JavaRuntime,
        phpVersion: PhpVersion,
        prootDistro: ProotDistro?
    ): McConfig {
        val vendor = javaRuntime.vendor
        val distro = prootDistro ?: config.prootDistro
        // Proot 来源的可用版本由**发行版**决定，而不是来源的并集：
        // 并集里同时有 8（仅 focal）与 21（仅 jammy），照并集放行会写出
        // 一个该发行版装不上的版本，用户点安装才看到 apt 报「找不到软件包」。
        val allowed = if (vendor == JavaVendor.Proot) {
            distro.availableJavaVersions
        } else {
            vendor.versions
        }
        // 版本必须落在允许列表内，否则会出现「FCL 来源 + Java 8」这种
        // 不存在的组合（FCL 只发布 17/21/25）。
        val version = javaRuntime.version.takeIf { it in allowed }
            ?: allowed.firstOrNull()
            ?: JavaRuntime.Default.version
        return config.copy(
            runtimeKind = kind,
            selectedJavaVendor = vendor,
            selectedJavaVersion = version,
            prootDistro = distro,
            selectedPhpVersion = phpVersion.takeIf { it in PhpVersion.entries } ?: PhpVersion.Default
        )
    }
}

package com.mineserve.mobile.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.jar.JarFile

/**
 * 解析 installer jar 里声明的依赖库清单（纯函数，便于单测）。
 *
 * LegacyInstaller 会读两份清单：jar 内 `install_profile.json` 与它引用的 version json
 * （profile 的 `json` 字段，新版是文件名、老版是内联对象）。两者**不是包含关系**：
 * NeoForge 21.1.80 的 profile 有 79 库，version.json 另独占 `bootstraplauncher`；
 * 20.4.237 独占 2 个、20.6.119 独占 11 个。只按 profile 预下载，installer 仍会在
 * 「差 1~11 个库」上失败（2026-11-21 审查实测：补上 bootstraplauncher 后离线环境装通）。
 */
internal object InstallerLibraryManifest {

    data class Entry(val path: String, val url: String, val sha1: String?)

    private val json = Json { ignoreUnknownKeys = true }

    fun libraries(installerJar: File): List<Entry> {
        val roots = mutableListOf<JsonObject>()
        JarFile(installerJar).use { jar ->
            fun read(name: String): JsonObject? = runCatching {
                jar.getEntry(name.removePrefix("/"))?.let { entry ->
                    json.parseToJsonElement(
                        jar.getInputStream(entry).bufferedReader().use { it.readText() }
                    ).jsonObject
                }
            }.getOrNull()
            val profile = read("install_profile.json") ?: return emptyList()
            roots.add(profile)
            when (val jsonField = profile["json"]) {
                is JsonPrimitive -> jsonField.contentOrNull?.let { read(it)?.let(roots::add) }
                is JsonObject -> roots.add(jsonField)
                else -> Unit
            }
            // 兜底：profile 没给 json 字段但 jar 里确实带 version.json 的构建
            if (roots.size == 1) read("version.json")?.let(roots::add)
        }
        return merge(roots)
    }

    /** 合并多份清单：只保留带直链 URL 的 artifact，按 path 去重。 */
    fun merge(roots: List<JsonObject>): List<Entry> = roots.flatMap { root ->
        runCatching { root["libraries"]?.jsonArray }.getOrNull() ?: emptyList()
    }.mapNotNull { element ->
        runCatching {
            val artifact = element.jsonObject["downloads"]?.jsonObject
                ?.get("artifact")?.jsonObject ?: return@runCatching null
            val path = artifact["path"]?.jsonPrimitive?.contentOrNull
                ?: return@runCatching null
            val url = artifact["url"]?.jsonPrimitive?.contentOrNull
                ?: return@runCatching null
            if (url.isBlank()) return@runCatching null
            Entry(path, url, artifact["sha1"]?.jsonPrimitive?.contentOrNull)
        }.getOrNull()
    }.distinctBy { it.path }

    /**
     * 把清单里的相对路径解析到 `libraries/` 下；越界（`../` 穿越、绝对路径）返回 null。
     *
     * 这条链路的安全性依赖于此：`path` 完全由远端 jar 内的 JSON 决定，而 jar 本身
     * 经过镜像/代理，不能假定它没被改过。
     */
    fun resolveTarget(libsRoot: File, path: String): File? {
        // 绝对路径显式拒绝，不依赖 canonical 比较：POSIX 上 `File(parent, "/abs")` 会逃出
        // parent，Windows 上同样写法却是相对路径 —— 显式判前缀能让行为与运行平台无关
        // （单测在 Windows 跑、装机在 Android 跑）。反斜杠前缀在 Unix 只是普通字符，
        // 盘符（`C:\...` / `C:/...`）在 Unix 同样不是绝对路径，这里一并拒掉属于无害的
        // 过度防御，省得跨平台差异回来咬人。
        val looksLikeWindowsAbsolute =
            path.length >= 2 && path[1] == ':' && path[0].isLetter()
        if (path.startsWith("/") || path.startsWith("\\") || looksLikeWindowsAbsolute ||
            File(path).isAbsolute
        ) {
            return null
        }
        val root = libsRoot.canonicalFile
        val target = File(root, path).canonicalFile
        return if (target.path.startsWith(root.path + File.separator)) target else null
    }
}
